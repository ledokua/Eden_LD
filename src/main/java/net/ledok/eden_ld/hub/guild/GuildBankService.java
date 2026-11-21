package net.ledok.eden_ld.hub.guild;

import io.netty.buffer.Unpooled;
import net.ledok.eden_ld.EdenLD;
import net.ledok.eden_ld.compat.EconomyCompat;
import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Every guild bank rule. Item moves are atomic server-side clicks — no cursor item ever
 * exists — so nothing can be duplicated or lost mid-drag. Methods return null on success or a
 * player-facing error; the caller re-sends bank data. Server thread only.
 */
public final class GuildBankService {

    public enum Upgrade { MEMBERS, CLAIMS }

    private GuildBankService() {
    }

    private static Component error(String key, Object... args) {
        return Component.translatable("message.eden_ld.guild.bank." + key, args);
    }

    private static long today() {
        return LocalDate.now().toEpochDay();
    }

    /** The player's usage row for today (reset when the day changed). */
    private static GuildBank.Usage usage(GuildBank bank, UUID player) {
        GuildBank.Usage usage = bank.usage.computeIfAbsent(player, p -> new GuildBank.Usage());
        if (usage.day != today()) {
            usage.day = today();
            usage.lc = 0;
            usage.items.clear();
        }
        return usage;
    }

    /** Items the player may still take from this tab today; -1 = unlimited. */
    public static int itemsLeft(Guild guild, UUID player, int tab) {
        GuildBank.TabRights rights = guild.bank.tabRights(guild.rank(player), tab);
        if (rights.access() != GuildBank.Access.WITHDRAW) {
            return 0;
        }
        if (rights.itemsPerDay() < 0) {
            return -1;
        }
        return Math.max(0, rights.itemsPerDay() - usage(guild.bank, player).items.getOrDefault(tab, 0));
    }

    /** LC the player may still withdraw today; -1 = unlimited. */
    public static long lcLeft(Guild guild, UUID player) {
        long perDay = guild.bank.lcPerDay(guild.rank(player));
        if (perDay < 0) {
            return -1;
        }
        return Math.max(0, perDay - usage(guild.bank, player).lc);
    }

    public static boolean canView(Guild guild, UUID player, int tab) {
        return guild.bank.tabRights(guild.rank(player), tab).access().ordinal() >= GuildBank.Access.VIEW.ordinal();
    }

    private static void log(Guild guild, ServerPlayer player, GuildBank.LogType type, int tab, ItemStack stack,
                            long amount, String detail) {
        log(guild, player.getUUID(), player.getGameProfile().getName(), type, tab,
                forLog(stack, player.registryAccess()), amount, detail);
    }

    // ---------------------------------------------------------------- heavy stacks (review finding 25)

    /**
     * Encoded size above which a stack is logged, and sent in the log, as item, count and name only. A log row only
     * shows an icon and a name, and full copies of heavy items (big books, filled shulkers) made guilds.dat and the
     * bank packet grow without limit (review finding 25).
     */
    static final int HEAVY_STACK_BYTES = 8 * 1024;
    /**
     * One deposited stack may not encode larger than this (review finding 25): a few such items used to push the
     * bank packet past the network limit and kick every member who opened it. A full vanilla book in Cyrillic stays
     * under it.
     */
    static final int MAX_DEPOSIT_BYTES = 256 * 1024;

    /**
     * The stack's size on the wire ({@link ItemStack#OPTIONAL_STREAM_CODEC}); a stack without component changes is
     * counted as small without encoding it. {@link Integer#MAX_VALUE} when it can't be encoded at all.
     */
    static int encodedSize(ItemStack stack, RegistryAccess registries) {
        if (stack.isEmpty() || stack.getComponentsPatch().isEmpty()) {
            return 16;
        }
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), registries);
        try {
            ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, stack);
            return buf.readableBytes();
        } catch (RuntimeException e) {
            return Integer.MAX_VALUE;
        } finally {
            buf.release();
        }
    }

    /**
     * Item, count and display name only, nothing else of the stack (review finding 25). A plain translated name
     * (potions, tipped arrows) stays translatable so it reads in the viewer's language; anything else becomes a
     * short literal in the name's color.
     */
    static ItemStack lightCopy(ItemStack stack) {
        if (stack.isEmpty()) {
            return ItemStack.EMPTY;
        }
        ItemStack light = new ItemStack(stack.getItem(), stack.getCount());
        Component name = stack.getHoverName();
        if (!name.equals(light.getHoverName())) {
            Component kept;
            if (name.getContents() instanceof TranslatableContents translatable && translatable.getArgs().length == 0
                    && name.getSiblings().isEmpty() && translatable.getKey().length() <= 128) {
                kept = Component.translatable(translatable.getKey());
            } else {
                String text = name.getString();
                kept = Component.literal(text.length() > 64 ? text.substring(0, 64) : text);
            }
            light.set(DataComponents.CUSTOM_NAME,
                    kept.copy().withStyle(Style.EMPTY.withColor(name.getStyle().getColor())));
        }
        return light;
    }

    /** What the log keeps of a moved stack: the stack itself, or {@link #lightCopy} when it is heavy. */
    private static ItemStack forLog(ItemStack stack, RegistryAccess registries) {
        return encodedSize(stack, registries) <= HEAVY_STACK_BYTES ? stack : lightCopy(stack);
    }

    private static void log(Guild guild, UUID player, String name, GuildBank.LogType type, int tab, ItemStack stack,
                            long amount, String detail) {
        GuildRules.addCapped(guild.bank.log, new GuildBank.LogEntry(System.currentTimeMillis(), player, name, type,
                tab, stack.copy(), amount, detail), GuildBank.LOG_LIMIT);
    }

    /**
     * The refusal when staff froze the bank and {@code move} takes something out ("The guild bank is frozen by
     * staff: reason"); null otherwise. Every withdrawal and purchase path asks first.
     */
    @Nullable
    public static Component frozen(Guild guild, GuildRules.BankMove move) {
        Guild.BankFreeze freeze = guild.freeze;
        if (!GuildRules.refused(freeze != null, move)) {
            return null;
        }
        return error("frozen", freeze.reason());
    }

    // ---------------------------------------------------------------- items

    /** Moves {@code count} items (0 = the whole stack) from a main-inventory slot into the tab. */
    @Nullable
    public static Component depositItem(GuildManager manager, ServerPlayer player, int tab, int inventorySlot, int count) {
        Guild guild = manager.guildOf(player.getUUID());
        if (guild == null || tab < 0 || tab >= guild.bank.tabs.size()) {
            return error("no_tab");
        }
        if (guild.bank.tabRights(guild.rank(player.getUUID()), tab).access().ordinal()
                < GuildBank.Access.DEPOSIT.ordinal()) {
            return error("no_deposit");
        }
        if (inventorySlot < 0 || inventorySlot >= player.getInventory().items.size()) {
            return null;
        }
        ItemStack source = player.getInventory().items.get(inventorySlot);
        if (source.isEmpty()) {
            return null;
        }
        if (encodedSize(source, player.registryAccess()) > MAX_DEPOSIT_BYTES) {
            return error("too_large");
        }
        ItemStack moving = source.copyWithCount(count <= 0 ? source.getCount() : Math.min(count, source.getCount()));
        int wanted = moving.getCount();
        ItemStack rest = insert(guild.bank.tabs.get(tab), moving);
        int moved = wanted - rest.getCount();
        if (moved <= 0) {
            return error("tab_full");
        }
        ItemStack logged = source.copyWithCount(moved);
        source.shrink(moved);
        log(guild, player, GuildBank.LogType.DEPOSIT_ITEM, tab, logged, moved, "");
        manager.markChanged();
        return null;
    }

    /** Moves {@code count} items (0 = the whole stack) from a vault slot into the inventory, within today's limit. */
    @Nullable
    public static Component withdrawItem(GuildManager manager, ServerPlayer player, int tab, int slot, int count) {
        Guild guild = manager.guildOf(player.getUUID());
        if (guild == null || tab < 0 || tab >= guild.bank.tabs.size() || slot < 0 || slot >= GuildBank.TAB_SIZE) {
            return error("no_tab");
        }
        Component frozen = frozen(guild, GuildRules.BankMove.WITHDRAW);
        if (frozen != null) {
            return frozen;
        }
        int left = itemsLeft(guild, player.getUUID(), tab);
        if (left == 0) {
            return guild.bank.tabRights(guild.rank(player.getUUID()), tab).access() == GuildBank.Access.WITHDRAW
                    ? error("limit_reached") : error("no_withdraw");
        }
        NonNullList<ItemStack> slots = guild.bank.tabs.get(tab);
        ItemStack stored = slots.get(slot);
        if (stored.isEmpty()) {
            return null;
        }
        int wantedCount = count <= 0 ? stored.getCount() : Math.min(count, stored.getCount());
        if (left > 0) {
            wantedCount = Math.min(wantedCount, left);
        }
        ItemStack taking = stored.copyWithCount(wantedCount);
        int wanted = taking.getCount();
        player.getInventory().add(taking);
        int moved = wanted - taking.getCount();
        if (moved <= 0) {
            return error("inventory_full");
        }
        ItemStack logged = stored.copyWithCount(moved);
        stored.shrink(moved);
        if (stored.isEmpty()) {
            slots.set(slot, ItemStack.EMPTY);
        }
        GuildBank.Usage usage = usage(guild.bank, player.getUUID());
        usage.items.merge(tab, moved, Integer::sum);
        log(guild, player, GuildBank.LogType.WITHDRAW_ITEM, tab, logged, moved, "");
        manager.markChanged();
        return null;
    }

    /** Merges into matching stacks first, then empty slots; returns what didn't fit. */
    private static ItemStack insert(NonNullList<ItemStack> slots, ItemStack stack) {
        for (int i = 0; i < slots.size() && !stack.isEmpty(); i++) {
            ItemStack existing = slots.get(i);
            if (!existing.isEmpty() && ItemStack.isSameItemSameComponents(existing, stack)
                    && existing.getCount() < existing.getMaxStackSize()) {
                int room = existing.getMaxStackSize() - existing.getCount();
                int moved = Math.min(room, stack.getCount());
                existing.grow(moved);
                stack.shrink(moved);
            }
        }
        for (int i = 0; i < slots.size() && !stack.isEmpty(); i++) {
            if (slots.get(i).isEmpty()) {
                int moved = Math.min(stack.getMaxStackSize(), stack.getCount());
                slots.set(i, stack.copyWithCount(moved));
                stack.shrink(moved);
            }
        }
        return stack;
    }

    // ---------------------------------------------------------------- LC

    /** Any member may deposit; the LC leaves their balance first (async), then lands in the bank. */
    public static void depositLc(GuildManager manager, ServerPlayer player, long amount, Consumer<Component> done) {
        Guild guild = manager.guildOf(player.getUUID());
        if (guild == null || amount <= 0) {
            done.accept(null);
            return;
        }
        if (!EconomyCompat.available()) {
            done.accept(error("no_economy"));
            return;
        }
        UUID guildId = guild.id;
        EconomyCompat.take(player, amount, paid -> {
            if (!paid) {
                done.accept(error("not_enough_lc", amount));
                return;
            }
            Guild current = manager.byId(guildId);
            if (current == null) {
                // A refund that fails is only logged by EconomyCompat; this line says what it was for, so staff can
                // pay it back by hand (review finding 15).
                EdenLD.LOGGER.warn("[Guild] Refunding {} LC to {} ({}): their guild was gone when the deposit landed.",
                        amount, player.getGameProfile().getName(), player.getUUID());
                EconomyCompat.give(player, amount);
                done.accept(error("no_tab"));
                return;
            }
            current.bank.balance += amount;
            log(current, player, GuildBank.LogType.DEPOSIT_LC, -1, ItemStack.EMPTY, amount, "");
            manager.markChanged();
            done.accept(null);
        });
    }

    @Nullable
    public static Component withdrawLc(GuildManager manager, ServerPlayer player, long amount) {
        Guild guild = manager.guildOf(player.getUUID());
        if (guild == null || amount <= 0) {
            return null;
        }
        if (!EconomyCompat.available()) {
            return error("no_economy");
        }
        Component frozen = frozen(guild, GuildRules.BankMove.WITHDRAW);
        if (frozen != null) {
            return frozen;
        }
        long left = lcLeft(guild, player.getUUID());
        if (left == 0) {
            return error("no_lc_withdraw");
        }
        if (left > 0 && amount > left) {
            return error("lc_limit", left);
        }
        if (amount > guild.bank.balance) {
            return error("bank_short", guild.bank.balance);
        }
        guild.bank.balance -= amount;
        GuildBank.Usage usage = usage(guild.bank, player.getUUID());
        usage.lc += amount;
        // Logged before the payout, so a failed one can take its own row back out (review finding 15).
        log(guild, player, GuildBank.LogType.WITHDRAW_LC, -1, ItemStack.EMPTY, amount, "");
        manager.markChanged();
        GuildBank.LogEntry entry = guild.bank.log.isEmpty() ? null : guild.bank.log.get(guild.bank.log.size() - 1);
        UUID guildId = guild.id;
        UUID uuid = player.getUUID();
        long day = usage.day;
        net.minecraft.server.MinecraftServer server = player.getServer();
        // Review finding 15: the give used to be fire-and-forget, so a failed one left the bank debited and the LC
        // nowhere. Now a failure puts the balance, the usage and the log back, and says so.
        EconomyCompat.give(player, amount, given -> {
            if (given) {
                return;
            }
            Component message = restoreLcWithdrawal(manager, guildId, uuid, amount, day, entry);
            ServerPlayer online = server == null ? null : server.getPlayerList().getPlayer(uuid);
            if (online != null) {
                GuildBankServer.fail(online, message);
            }
            Guild current = manager.byId(guildId);
            if (server != null && current != null) {
                GuildBankServer.pushToViewers(server, current);
            }
        });
        return null;
    }

    /**
     * Puts back an LC withdrawal whose payout failed (review finding 15): the bank balance and the member's daily
     * usage are restored and the withdrawal's log row is dropped, so nothing was lost but the attempt. The failure
     * branch of {@link #withdrawLc}'s give; the caller pushes the bank and tells the player with the returned message.
     * Server thread only.
     *
     * @param entry the WITHDRAW_LC row {@link #withdrawLc} logged (removed by identity; gone already when the log
     *              rolled past it)
     */
    static Component restoreLcWithdrawal(GuildManager manager, UUID guildId, UUID player, long amount, long day,
                                         @Nullable GuildBank.LogEntry entry) {
        EdenLD.LOGGER.error("[Guild] Paying out {} LC from guild {} to {} failed; the LC went back to the bank.",
                amount, guildId, player);
        Guild guild = manager.byId(guildId);
        if (guild == null) {
            EdenLD.LOGGER.error("[Guild] Guild {} is gone; {} LC for {} must be returned by hand.", guildId, amount,
                    player);
            // Not "back in the guild bank": there is no bank any more (review of 1.3.133).
            return error("withdraw_failed_no_guild");
        }
        guild.bank.balance += amount;
        GuildBank.Usage usage = guild.bank.usage.get(player);
        if (usage != null && usage.day == day) {
            usage.lc = Math.max(0, usage.lc - amount);
        }
        if (entry != null) {
            guild.bank.log.removeIf(row -> row == entry);
        }
        manager.markChanged();
        return error("withdraw_failed");
    }

    // ---------------------------------------------------------------- purchases (leader, from bank LC)

    /** Price of the next tab (guild level discount applied), or -1 when no more may be bought. */
    public static long nextTabCost(Guild guild) {
        return price(EdenLD.CONFIG.hub.guild_tab_costs, guild.bank.tabs.size(),
                cap(guild, EdenLD.CONFIG.guilds.tab_cap, GuildPerks.Kind.EXTRA_TABS),
                GuildLevels.perk(guild, GuildPerks.Kind.TAB_DISCOUNT));
    }

    public static long nextUpgradeCost(Guild guild, Upgrade upgrade) {
        if (upgrade == Upgrade.MEMBERS) {
            return price(EdenLD.CONFIG.hub.guild_member_upgrade_costs, guild.bank.memberLevel,
                    cap(guild, EdenLD.CONFIG.guilds.member_upgrade_cap, GuildPerks.Kind.EXTRA_MEMBER_UPGRADES),
                    GuildLevels.perk(guild, GuildPerks.Kind.MEMBER_UPGRADE_DISCOUNT));
        }
        return price(EdenLD.CONFIG.hub.guild_claim_upgrade_costs, guild.bank.claimLevel,
                cap(guild, EdenLD.CONFIG.guilds.claim_upgrade_cap, GuildPerks.Kind.EXTRA_CLAIM_UPGRADES),
                GuildLevels.perk(guild, GuildPerks.Kind.CLAIM_UPGRADE_DISCOUNT));
    }

    /** Vault tabs the guild may own at its level: the price list's length, or the config cap plus the level's extra. */
    public static int tabCap(Guild guild) {
        return Math.min(EdenLD.CONFIG.hub.guild_tab_costs.size(),
                cap(guild, EdenLD.CONFIG.guilds.tab_cap, GuildPerks.Kind.EXTRA_TABS));
    }

    /** How many may be bought: the base cap (-1 = no cap of its own) plus the level's extra. */
    private static int cap(Guild guild, int base, GuildPerks.Kind extra) {
        return base < 0 ? Integer.MAX_VALUE : base + GuildLevels.perk(guild, extra);
    }

    /** The {@code bought}-th price of the list less {@code discount} percent; -1 past the list or the cap. */
    private static long price(List<Long> costs, int bought, int cap, int discount) {
        if (bought >= costs.size() || bought >= cap) {
            return -1;
        }
        long cost = Math.max(0, costs.get(bought));
        return cost - cost * Math.max(0, Math.min(90, discount)) / 100;
    }

    @Nullable
    public static Component buyTab(GuildManager manager, ServerPlayer player) {
        Guild guild = leaderGuild(manager, player);
        if (guild == null) {
            return error("leader_only");
        }
        Component frozen = frozen(guild, GuildRules.BankMove.PURCHASE);
        if (frozen != null) {
            return frozen;
        }
        long cost = nextTabCost(guild);
        if (cost < 0) {
            return error("max_tabs");
        }
        if (cost > guild.bank.balance) {
            return error("bank_short", guild.bank.balance);
        }
        guild.bank.balance -= cost;
        guild.bank.tabs.add(NonNullList.withSize(GuildBank.TAB_SIZE, ItemStack.EMPTY));
        guild.bank.tabNames.add("");
        int index = guild.bank.tabs.size() - 1;
        for (GuildRank rank : List.of(GuildRank.OFFICER, GuildRank.MEMBER, GuildRank.RECRUIT)) {
            List<GuildBank.TabRights> tabs = guild.bank.rights(rank).tabs;
            while (tabs.size() <= index) {
                tabs.add(GuildBank.defaultTabRights(rank));
            }
        }
        log(guild, player, GuildBank.LogType.BUY_TAB, index, ItemStack.EMPTY, cost, "");
        manager.markChanged();
        return null;
    }

    @Nullable
    public static Component buyUpgrade(GuildManager manager, ServerPlayer player, Upgrade upgrade) {
        Guild guild = leaderGuild(manager, player);
        if (guild == null) {
            return error("leader_only");
        }
        Component frozen = frozen(guild, GuildRules.BankMove.PURCHASE);
        if (frozen != null) {
            return frozen;
        }
        long cost = nextUpgradeCost(guild, upgrade);
        if (cost < 0) {
            return error("max_upgrade");
        }
        if (cost > guild.bank.balance) {
            return error("bank_short", guild.bank.balance);
        }
        guild.bank.balance -= cost;
        if (upgrade == Upgrade.MEMBERS) {
            guild.bank.memberLevel++;
        } else {
            guild.bank.claimLevel++;
        }
        log(guild, player, GuildBank.LogType.BUY_UPGRADE, -1, ItemStack.EMPTY, cost, upgrade.name());
        manager.markChanged();
        return null;
    }

    // ---------------------------------------------------------------- rights (leader)

    @Nullable
    public static Component setTabRights(GuildManager manager, ServerPlayer player, GuildRank rank, int tab,
                                         GuildBank.Access access, int itemsPerDay) {
        Guild guild = leaderGuild(manager, player);
        if (guild == null) {
            return error("leader_only");
        }
        if (rank.manages() || tab < 0 || tab >= guild.bank.tabs.size()) {
            return null;
        }
        List<GuildBank.TabRights> tabs = guild.bank.rights(rank).tabs;
        while (tabs.size() <= tab) {
            tabs.add(GuildBank.defaultTabRights(rank));
        }
        tabs.set(tab, new GuildBank.TabRights(access, Math.max(-1, itemsPerDay)));
        log(guild, player, GuildBank.LogType.RIGHTS, tab, ItemStack.EMPTY, 0, rank.name());
        manager.markChanged();
        return null;
    }

    @Nullable
    public static Component setLcLimit(GuildManager manager, ServerPlayer player, GuildRank rank, long perDay) {
        Guild guild = leaderGuild(manager, player);
        if (guild == null) {
            return error("leader_only");
        }
        if (rank.manages()) {
            return null;
        }
        guild.bank.rights(rank).lcPerDay = Math.max(-1, perDay);
        log(guild, player, GuildBank.LogType.RIGHTS, -1, ItemStack.EMPTY, perDay, rank.name());
        manager.markChanged();
        return null;
    }

    @Nullable
    public static Component renameTab(GuildManager manager, ServerPlayer player, int tab, String name) {
        Guild guild = leaderGuild(manager, player);
        if (guild == null) {
            return error("leader_only");
        }
        if (tab >= 0 && tab < guild.bank.tabNames.size()) {
            String trimmed = name.trim();
            if (!net.ledok.eden_ld.chat.ChatGuard.allowedText(trimmed)) {
                return Component.translatable("message.eden_ld.text.illegal_characters");
            }
            guild.bank.tabNames.set(tab, trimmed.length() > 16 ? trimmed.substring(0, 16) : trimmed);
            manager.markChanged();
        }
        return null;
    }

    @Nullable
    private static Guild leaderGuild(GuildManager manager, ServerPlayer player) {
        Guild guild = manager.guildOf(player.getUUID());
        return guild != null && guild.rank(player.getUUID()).manages() ? guild : null;
    }

    // ---------------------------------------------------------------- staff (Moderation → Guilds)

    /**
     * Staff add ({@code delta} &gt; 0) or take (&lt; 0) bank LC; never below 0. The bank's own log gets a staff
     * entry with the staff member's name and reason, so the members see it too.
     */
    @Nullable
    public static Component staffLc(GuildManager manager, Guild guild, UUID staff, String staffName, long delta,
                                    String reason) {
        if (delta == 0) {
            return null;
        }
        if (delta < 0 && -delta > guild.bank.balance) {
            return error("bank_short", guild.bank.balance);
        }
        guild.bank.balance += delta;
        log(guild, staff, staffName, GuildBank.LogType.STAFF_LC, -1, ItemStack.EMPTY, delta, reason);
        manager.markChanged();
        return null;
    }

    /** Freezes ({@code on}) or thaws the bank; logged in the bank's log as well. */
    public static void staffFreeze(GuildManager manager, Guild guild, UUID staff, String staffName, boolean on,
                                   String reason) {
        guild.freeze = on ? new Guild.BankFreeze(staff, staffName, reason, System.currentTimeMillis()) : null;
        log(guild, staff, staffName, on ? GuildBank.LogType.STAFF_FREEZE : GuildBank.LogType.STAFF_UNFREEZE, -1,
                ItemStack.EMPTY, 0, reason);
        manager.markChanged();
    }

    /** Items (all tabs) and LC {@code player} took out today: {items, lc}. */
    public static long[] takenToday(Guild guild, UUID player) {
        GuildBank.Usage usage = guild.bank.usage.get(player);
        if (usage == null || usage.day != today()) {
            return new long[]{0, 0};
        }
        long items = 0;
        for (int count : usage.items.values()) {
            items += count;
        }
        return new long[]{items, usage.lc};
    }

    /** True while the bank still holds LC or items — the guild can't be disbanded then. */
    public static boolean holdsAnything(Guild guild) {
        if (guild.bank.balance > 0) {
            return true;
        }
        for (NonNullList<ItemStack> tab : guild.bank.tabs) {
            for (ItemStack stack : tab) {
                if (!stack.isEmpty()) {
                    return true;
                }
            }
        }
        return false;
    }
}
