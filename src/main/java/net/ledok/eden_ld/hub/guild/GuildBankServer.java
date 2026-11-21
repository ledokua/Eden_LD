package net.ledok.eden_ld.hub.guild;

import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.ledok.eden_ld.EdenLD;
import net.ledok.eden_ld.compat.EconomyCompat;
import net.ledok.eden_ld.compat.opac.OpacCompat;
import net.ledok.eden_ld.hub.HubPayloads;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Network side of the guild bank. Tracks who has the bank open (and on which tab) so every
 * change reaches exactly the members looking at it, live. Contents are only ever sent for a
 * tab the viewer's rank may view.
 */
public final class GuildBankServer {

    private static final int LOG_SENT = 60;
    /**
     * Encoded bytes of tab contents sent in one BankData (review finding 25), like the staff vault's budget; well
     * under the 2 MB compressed frame limit with the rest of the payload.
     */
    private static final int CONTENTS_BUDGET = 512 * 1024;

    /** Viewer → tab they look at (-1 = bank overview without a tab). */
    private static final Map<UUID, Integer> VIEWERS = new ConcurrentHashMap<>();

    private GuildBankServer() {
    }

    public static void initialize() {
        ServerPlayNetworking.registerGlobalReceiver(HubPayloads.BankView.TYPE, (payload, context) -> {
            ServerPlayer player = context.player();
            if (payload.tab() == HubPayloads.BankView.CLOSED) {
                VIEWERS.remove(player.getUUID());
            } else {
                VIEWERS.put(player.getUUID(), payload.tab());
                // Each view encodes a tab for this player: limited like bank actions (review finding 25 follow-up).
                if (net.ledok.eden_ld.hub.HubRateLimit.BANK_ACTION.allow(player)) {
                    send(player);
                }
            }
        });
        ServerPlayNetworking.registerGlobalReceiver(HubPayloads.BankAction.TYPE, (payload, context) -> {
            if (net.ledok.eden_ld.hub.HubRateLimit.BANK_ACTION.allow(context.player())) {
                handle(context.player(), payload);
            }
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                net.ledok.eden_ld.util.OnServerThread.run(server, () -> VIEWERS.remove(handler.getPlayer().getUUID())));
    }

    /** Re-sends the bank to every member of this guild who has it open. */
    public static void pushToViewers(MinecraftServer server, Guild guild) {
        for (UUID viewer : VIEWERS.keySet()) {
            if (guild.isMember(viewer)) {
                ServerPlayer online = server.getPlayerList().getPlayer(viewer);
                if (online != null) {
                    send(online);
                }
            }
        }
    }

    /** Everyone viewing any bank (rank changes, disbanding). */
    public static void pushAll(MinecraftServer server) {
        for (UUID viewer : VIEWERS.keySet()) {
            ServerPlayer online = server.getPlayerList().getPlayer(viewer);
            if (online != null) {
                send(online);
            }
        }
    }

    private static void send(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null || player.hasDisconnected()) {
            return;
        }
        Guild guild = GuildManager.get(server).guildOf(player.getUUID());
        if (guild == null) {
            VIEWERS.remove(player.getUUID());
            return;
        }
        GuildBank bank = guild.bank;
        UUID me = player.getUUID();
        GuildRank rank = guild.rank(me);

        List<HubPayloads.BankTab> tabs = new ArrayList<>();
        for (int t = 0; t < bank.tabs.size(); t++) {
            tabs.add(new HubPayloads.BankTab(t < bank.tabNames.size() ? bank.tabNames.get(t) : "",
                    bank.tabRights(rank, t).access().ordinal(), GuildBankService.itemsLeft(guild, me, t)));
        }
        int wanted = VIEWERS.getOrDefault(me, -1);
        int viewTab = wanted >= 0 && wanted < bank.tabs.size() && GuildBankService.canView(guild, me, wanted) ? wanted : -1;
        List<ItemStack> contents = viewTab < 0 ? List.of() : budgeted(player, bank.tabs.get(viewTab));

        List<HubPayloads.RankRightsView> rights = new ArrayList<>();
        if (rank.manages()) {
            for (GuildRank r : List.of(GuildRank.OFFICER, GuildRank.MEMBER, GuildRank.RECRUIT)) {
                List<Integer> access = new ArrayList<>();
                List<Integer> items = new ArrayList<>();
                for (int t = 0; t < bank.tabs.size(); t++) {
                    GuildBank.TabRights tabRights = bank.tabRights(r, t);
                    access.add(tabRights.access().ordinal());
                    items.add(tabRights.itemsPerDay());
                }
                rights.add(new HubPayloads.RankRightsView(r.level, bank.lcPerDay(r), access, items));
            }
        }

        List<HubPayloads.BankLog> log = new ArrayList<>();
        List<HubPayloads.BankStaff.StaffNote> notes = new ArrayList<>();
        // Clients before 1.3.21 know no staff entries: LC moves show as plain deposits/withdrawals, the rest not at all.
        boolean staffAware = ServerPlayNetworking.canSend(player, HubPayloads.BankStaff.TYPE);
        int from = Math.max(0, bank.log.size() - LOG_SENT);
        for (GuildBank.LogEntry entry : bank.log.subList(from, bank.log.size())) {
            GuildBank.LogType type = entry.type();
            long amount = entry.amount();
            String detail = entry.detail();
            if (type.staff()) {
                // The reason travels in BankStaff (BankLog's detail is short).
                notes.add(new HubPayloads.BankStaff.StaffNote(entry.time(), clip(detail, 512)));
                detail = "";
                if (!staffAware) {
                    if (type != GuildBank.LogType.STAFF_LC) {
                        continue;
                    }
                    type = amount >= 0 ? GuildBank.LogType.DEPOSIT_LC : GuildBank.LogType.WITHDRAW_LC;
                    amount = Math.abs(amount);
                }
            }
            // Item, count and name only: the row shows nothing else, and full stacks here made the packet unbounded
            // (review finding 25).
            log.add(new HubPayloads.BankLog(entry.time(), entry.name(), type.ordinal(), entry.tab(),
                    GuildBankService.lightCopy(entry.stack()), amount,
                    detail.length() > 32 ? detail.substring(0, 32) : detail));
        }

        // The viewer's own LC (for the deposit box) comes from Economy_LD asynchronously.
        long balance = bank.balance;
        List<HubPayloads.BankTab> tabsView = tabs;
        List<ItemStack> contentsView = contents;
        List<HubPayloads.RankRightsView> rightsView = rights;
        List<HubPayloads.BankLog> logView = log;
        int viewed = viewTab;
        EconomyCompat.fetchBalance(player, (hasEconomy, own) -> {
            if (player.hasDisconnected()) {
                return;
            }
            sendData(player, guild, bank, rank, balance, hasEconomy ? own : -1, tabsView, viewed, contentsView,
                    rightsView, logView);
            if (staffAware) {
                Guild.BankFreeze freeze = guild.freeze;
                ServerPlayNetworking.send(player, new HubPayloads.BankStaff(freeze != null,
                        freeze == null ? "" : clip(freeze.byName(), 64), freeze == null ? "" : clip(freeze.reason(), 512),
                        freeze == null ? 0 : freeze.since(), notes));
            }
        });
    }

    /**
     * Copies of a tab's 54 slots within {@link #CONTENTS_BUDGET} encoded bytes (review finding 25): past the budget a
     * stack goes as item, count and name with a "too large to show" lore line. Withdrawing works by slot, so the
     * placeholder still hands out the real item. Copies, since the send waits for the viewer's LC balance.
     */
    private static List<ItemStack> budgeted(ServerPlayer player, List<ItemStack> slots) {
        List<ItemStack> sent = new ArrayList<>(slots.size());
        int used = 0;
        Component note = null;
        for (ItemStack stack : slots) {
            if (stack.isEmpty()) {
                sent.add(ItemStack.EMPTY);
                continue;
            }
            if (stack.getComponentsPatch().isEmpty()) {
                // A plain stack: a few bytes, nothing to measure.
                used += 16;
                sent.add(stack.copy());
                continue;
            }
            // Once the budget is spent, the rest goes light without being encoded at all.
            if (used < CONTENTS_BUDGET) {
                int size = measured(stack, player);
                if (size <= CONTENTS_BUDGET - used) {
                    used += size;
                    sent.add(stack.copy());
                    continue;
                }
            }
            if (note == null) {
                note = net.ledok.eden_ld.hub.lang.ServerLang.localize(player,
                        Component.translatable("message.eden_ld.guild.bank.trimmed")).copy()
                        .withStyle(ChatFormatting.GRAY);
            }
            ItemStack light = GuildBankService.lightCopy(stack);
            light.set(DataComponents.LORE, new ItemLore(List.of(note)));
            used += 256;
            sent.add(light);
        }
        return sent;
    }

    /**
     * A stored stack's encoded size, measured once per stack object (review finding 25 follow-up): sends re-measured
     * every slot, so 54 heavy books cost megabytes of encoding per BankView packet and per push. Bank stacks only
     * change count after they're stored (a varint byte or two), so the first measure stays good. Weak keys: a stack
     * that left the bank drops out. ItemStack has no equals/hashCode, so the map is by identity. Server thread only.
     */
    private static final Map<ItemStack, Integer> MEASURED = new java.util.WeakHashMap<>();

    private static int measured(ItemStack stack, ServerPlayer player) {
        Integer known = MEASURED.get(stack);
        if (known != null) {
            return known;
        }
        int size = GuildBankService.encodedSize(stack, player.registryAccess());
        MEASURED.put(stack, size);
        return size;
    }

    private static String clip(String value, int max) {
        return value.length() > max ? value.substring(0, max) : value;
    }

    private static void sendData(ServerPlayer player, Guild guild, GuildBank bank, GuildRank rank, long balance,
                                 long myBalance, List<HubPayloads.BankTab> tabs, int viewTab, List<ItemStack> contents,
                                 List<HubPayloads.RankRightsView> rights, List<HubPayloads.BankLog> log) {
        UUID me = player.getUUID();
        OpacCompat.ClaimsInfo claims = OpacCompat.claims(player.server, guild);
        ServerPlayNetworking.send(player, new HubPayloads.BankData(balance, EconomyCompat.available(), myBalance,
                rank.level, GuildBankService.lcLeft(guild, me), tabs, viewTab, contents,
                GuildBankService.nextTabCost(guild),
                bank.memberLevel, GuildBankService.nextUpgradeCost(guild, GuildBankService.Upgrade.MEMBERS),
                Math.max(0, EdenLD.CONFIG.hub.guild_member_upgrade_step),
                bank.claimLevel, GuildBankService.nextUpgradeCost(guild, GuildBankService.Upgrade.CLAIMS),
                Math.max(0, EdenLD.CONFIG.hub.guild_claim_upgrade_step),
                claims == null ? 0 : claims.used(), claims == null ? -1 : claims.limit(),
                rights, log));
    }

    private static void handle(ServerPlayer player, HubPayloads.BankAction action) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }
        GuildManager manager = GuildManager.get(server);
        Guild guild = manager.guildOf(player.getUUID());
        if (guild == null) {
            return;
        }
        GuildRank rank = GuildRank.of(action.rank());
        switch (action.kind()) {
            case SET_TAB_RIGHTS, SET_LC_LIMIT, RENAME_TAB -> {
            }
            default -> {
                // Nothing in or out (items, LC, upgrades) mid-run or in the tutorial.
                if (net.ledok.eden_ld.hub.BusyLocks.refuse(player)) {
                    return;
                }
            }
        }
        Component error = switch (action.kind()) {
            case DEPOSIT_ITEM -> GuildBankService.depositItem(manager, player, action.tab(), action.slot(),
                    (int) Math.max(0, Math.min(Integer.MAX_VALUE, action.amount())));
            case WITHDRAW_ITEM -> GuildBankService.withdrawItem(manager, player, action.tab(), action.slot(),
                    (int) Math.max(0, Math.min(Integer.MAX_VALUE, action.amount())));
            case DEPOSIT_LC -> {
                GuildBankService.depositLc(manager, player, action.amount(), result -> {
                    fail(player, result);
                    after(server, player, false, result == null);
                });
                yield null;
            }
            case WITHDRAW_LC -> GuildBankService.withdrawLc(manager, player, action.amount());
            case BUY_TAB -> GuildBankService.buyTab(manager, player);
            case BUY_MEMBERS -> GuildBankService.buyUpgrade(manager, player, GuildBankService.Upgrade.MEMBERS);
            case BUY_CLAIMS -> GuildBankService.buyUpgrade(manager, player, GuildBankService.Upgrade.CLAIMS);
            case SET_TAB_RIGHTS -> GuildBankService.setTabRights(manager, player, rank, action.tab(),
                    GuildBank.Access.values()[Math.floorMod(action.slot(), GuildBank.Access.values().length)],
                    (int) Math.max(-1, Math.min(Integer.MAX_VALUE, action.amount())));
            case SET_LC_LIMIT -> GuildBankService.setLcLimit(manager, player, rank, action.amount());
            case RENAME_TAB -> GuildBankService.renameTab(manager, player, action.tab(), action.text());
        };
        fail(player, error);
        if (action.kind() != HubPayloads.BankAction.Action.DEPOSIT_LC) {
            after(server, player, action.kind() == HubPayloads.BankAction.Action.BUY_MEMBERS
                    || action.kind() == HubPayloads.BankAction.Action.BUY_CLAIMS, error == null);
        }
    }

    /**
     * Bank viewers see the change; member-limit upgrades also change the guild's directory row,
     * and claim upgrades reach OPAC through the guild sync. A refused action changed nothing: only the actor's view
     * refreshes, and no guild sync runs (review finding 38; the guild sync is coalesced to once per tick).
     */
    private static void after(MinecraftServer server, ServerPlayer player, boolean limitChanged, boolean succeeded) {
        if (!succeeded) {
            if (VIEWERS.containsKey(player.getUUID())) {
                send(player);
            }
            return;
        }
        Guild guild = GuildManager.get(server).guildOf(player.getUUID());
        if (guild != null) {
            pushToViewers(server, guild);
        }
        if (limitChanged) {
            GuildServer.scheduleSync(false);
        }
    }

    private static void fail(ServerPlayer player, @Nullable Component message) {
        if (message == null) {
            return;
        }
        player.sendSystemMessage(message.copy().withStyle(ChatFormatting.RED));
        ServerPlayNetworking.send(player, new HubPayloads.HubNotice(message));
    }
}
