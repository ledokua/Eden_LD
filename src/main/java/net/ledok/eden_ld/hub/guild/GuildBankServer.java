package net.ledok.eden_ld.hub.guild;

import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.ledok.eden_ld.EdenLD;
import net.ledok.eden_ld.compat.EconomyCompat;
import net.ledok.eden_ld.compat.opac.OpacCompat;
import net.ledok.eden_ld.hub.HubPayloads;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
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
                send(player);
            }
        });
        ServerPlayNetworking.registerGlobalReceiver(HubPayloads.BankAction.TYPE,
                (payload, context) -> handle(context.player(), payload));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                VIEWERS.remove(handler.getPlayer().getUUID()));
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
        List<ItemStack> contents = viewTab < 0 ? List.of() : List.copyOf(bank.tabs.get(viewTab));

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
        int from = Math.max(0, bank.log.size() - LOG_SENT);
        for (GuildBank.LogEntry entry : bank.log.subList(from, bank.log.size())) {
            log.add(new HubPayloads.BankLog(entry.time(), entry.name(), entry.type().ordinal(), entry.tab(),
                    entry.stack(), entry.amount(), entry.detail()));
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
        });
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
                    after(server, player, false);
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
                    || action.kind() == HubPayloads.BankAction.Action.BUY_CLAIMS);
        }
    }

    /**
     * Bank viewers see the change; member-limit upgrades also change the guild's directory row,
     * and claim upgrades reach OPAC through the guild sync.
     */
    private static void after(MinecraftServer server, ServerPlayer player, boolean limitChanged) {
        Guild guild = GuildManager.get(server).guildOf(player.getUUID());
        if (guild != null) {
            pushToViewers(server, guild);
        }
        if (limitChanged) {
            GuildServer.syncAll(server);
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
