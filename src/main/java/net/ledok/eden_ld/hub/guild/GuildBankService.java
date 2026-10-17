package net.ledok.eden_ld.hub.guild;

import net.ledok.eden_ld.EdenLD;
import net.ledok.eden_ld.compat.EconomyCompat;
import net.minecraft.core.NonNullList;
import net.minecraft.network.chat.Component;
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
        List<GuildBank.LogEntry> log = guild.bank.log;
        log.add(new GuildBank.LogEntry(System.currentTimeMillis(), player.getUUID(),
                player.getGameProfile().getName(), type, tab, stack.copy(), amount, detail));
        while (log.size() > GuildBank.LOG_LIMIT) {
            log.remove(0);
        }
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
        usage(guild.bank, player.getUUID()).lc += amount;
        EconomyCompat.give(player, amount);
        log(guild, player, GuildBank.LogType.WITHDRAW_LC, -1, ItemStack.EMPTY, amount, "");
        manager.markChanged();
        return null;
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
