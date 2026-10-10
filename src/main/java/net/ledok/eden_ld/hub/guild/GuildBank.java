package net.ledok.eden_ld.hub.guild;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A guild's bank (WoW-style): pooled LC, item vault tabs, per-rank permissions with daily
 * limits, an audit log, and bought upgrades. Stored with its {@link Guild}; every mutation
 * goes through {@link GuildBankService}, which enforces the rules.
 */
public final class GuildBank {

    public static final int TAB_SIZE = 54;
    public static final int LOG_LIMIT = 300;

    /** What a rank may do in a vault tab. Each level includes the ones before it. */
    public enum Access { NONE, VIEW, DEPOSIT, WITHDRAW }

    /**
     * One rank's rights in one tab; {@code itemsPerDay} -1 = unlimited. Counted in items, not
     * stacks — stack sizes vary too much (unstackable gear, 16-stacks, modded 99+ stacks).
     */
    public record TabRights(Access access, int itemsPerDay) {
    }

    /** One rank's rights: LC withdrawal per day (-1 unlimited, 0 none) and per-tab rights. */
    public static final class RankRights {
        public long lcPerDay;
        public final List<TabRights> tabs = new ArrayList<>();

        RankRights(long lcPerDay) {
            this.lcPerDay = lcPerDay;
        }

        public TabRights tab(int index) {
            return index < tabs.size() ? tabs.get(index) : new TabRights(Access.NONE, 0);
        }
    }

    public enum LogType { DEPOSIT_ITEM, WITHDRAW_ITEM, DEPOSIT_LC, WITHDRAW_LC, BUY_TAB, BUY_UPGRADE, RIGHTS, QUEST_REWARD }

    /** One audit entry; {@code stack} for item moves, {@code amount} for LC and purchases. */
    public record LogEntry(long time, UUID player, String name, LogType type, int tab, ItemStack stack,
                           long amount, String detail) {
    }

    /** Today's usage by one player: the day it counts for, LC taken, items taken per tab. */
    public static final class Usage {
        public long day;
        public long lc;
        public final Map<Integer, Integer> items = new HashMap<>();
    }

    public long balance;
    public final List<NonNullList<ItemStack>> tabs = new ArrayList<>();
    public final List<String> tabNames = new ArrayList<>();
    /** Rights of officers and below (leader and vice-leader always have everything). */
    public final Map<GuildRank, RankRights> rights = new EnumMap<>(GuildRank.class);
    public final Map<UUID, Usage> usage = new HashMap<>();
    public final List<LogEntry> log = new ArrayList<>();
    /** Bought upgrade levels: member slots, and claim levels (OPAC bonus claims on the leader, see OpacCompat). */
    public int memberLevel;
    public int claimLevel;

    public GuildBank() {
        // Defaults for a new guild: officers withdraw freely, members a little, recruits look.
        rights.put(GuildRank.OFFICER, new RankRights(-1));
        rights.put(GuildRank.MEMBER, new RankRights(0));
        rights.put(GuildRank.RECRUIT, new RankRights(0));
    }

    /** Default rights a newly bought tab gets, per rank. */
    static TabRights defaultTabRights(GuildRank rank) {
        return switch (rank) {
            case OFFICER, VICE_LEADER, LEADER -> new TabRights(Access.WITHDRAW, -1);
            case MEMBER -> new TabRights(Access.WITHDRAW, 64);
            case RECRUIT -> new TabRights(Access.VIEW, 0);
        };
    }

    public RankRights rights(GuildRank rank) {
        return rights.computeIfAbsent(rank, r -> new RankRights(0));
    }

    public TabRights tabRights(GuildRank rank, int tab) {
        return rank.manages() ? new TabRights(Access.WITHDRAW, -1) : rights(rank).tab(tab);
    }

    public long lcPerDay(GuildRank rank) {
        return rank.manages() ? -1 : rights(rank).lcPerDay;
    }

    // ---------------------------------------------------------------- NBT

    CompoundTag save(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        tag.putLong("balance", balance);
        tag.putInt("member_level", memberLevel);
        tag.putInt("claim_level", claimLevel);
        ListTag tabList = new ListTag();
        for (int t = 0; t < tabs.size(); t++) {
            CompoundTag tabTag = new CompoundTag();
            tabTag.putString("name", t < tabNames.size() ? tabNames.get(t) : "");
            ListTag items = new ListTag();
            NonNullList<ItemStack> slots = tabs.get(t);
            for (int i = 0; i < slots.size(); i++) {
                if (!slots.get(i).isEmpty()) {
                    CompoundTag item = new CompoundTag();
                    item.putInt("slot", i);
                    item.put("item", slots.get(i).save(registries));
                    items.add(item);
                }
            }
            tabTag.put("items", items);
            tabList.add(tabTag);
        }
        tag.put("tabs", tabList);
        CompoundTag rightsTag = new CompoundTag();
        rights.forEach((rank, rankRights) -> {
            CompoundTag r = new CompoundTag();
            r.putLong("lc", rankRights.lcPerDay);
            ListTag perTab = new ListTag();
            for (TabRights tabRights : rankRights.tabs) {
                CompoundTag tr = new CompoundTag();
                tr.putString("access", tabRights.access().name());
                tr.putInt("items", tabRights.itemsPerDay());
                perTab.add(tr);
            }
            r.put("tabs", perTab);
            rightsTag.put(rank.name(), r);
        });
        tag.put("rights", rightsTag);
        CompoundTag usageTag = new CompoundTag();
        usage.forEach((player, u) -> {
            CompoundTag ut = new CompoundTag();
            ut.putLong("day", u.day);
            ut.putLong("lc", u.lc);
            CompoundTag st = new CompoundTag();
            u.items.forEach((t, n) -> st.putInt(String.valueOf(t), n));
            ut.put("items", st);
            usageTag.put(player.toString(), ut);
        });
        tag.put("usage", usageTag);
        ListTag logList = new ListTag();
        for (LogEntry entry : log) {
            CompoundTag e = new CompoundTag();
            e.putLong("time", entry.time());
            e.putUUID("player", entry.player());
            e.putString("name", entry.name());
            e.putString("type", entry.type().name());
            e.putInt("tab", entry.tab());
            if (!entry.stack().isEmpty()) {
                e.put("stack", entry.stack().save(registries));
            }
            e.putLong("amount", entry.amount());
            e.putString("detail", entry.detail());
            logList.add(e);
        }
        tag.put("log", logList);
        return tag;
    }

    static GuildBank load(CompoundTag tag, HolderLookup.Provider registries) {
        GuildBank bank = new GuildBank();
        bank.balance = tag.getLong("balance");
        bank.memberLevel = tag.getInt("member_level");
        bank.claimLevel = tag.getInt("claim_level");
        ListTag tabList = tag.getList("tabs", Tag.TAG_COMPOUND);
        for (int t = 0; t < tabList.size(); t++) {
            CompoundTag tabTag = tabList.getCompound(t);
            NonNullList<ItemStack> slots = NonNullList.withSize(TAB_SIZE, ItemStack.EMPTY);
            ListTag items = tabTag.getList("items", Tag.TAG_COMPOUND);
            for (int i = 0; i < items.size(); i++) {
                CompoundTag item = items.getCompound(i);
                int slot = item.getInt("slot");
                if (slot >= 0 && slot < TAB_SIZE) {
                    slots.set(slot, ItemStack.parseOptional(registries, item.getCompound("item")));
                }
            }
            bank.tabs.add(slots);
            bank.tabNames.add(tabTag.getString("name"));
        }
        CompoundTag rightsTag = tag.getCompound("rights");
        for (String key : rightsTag.getAllKeys()) {
            GuildRank rank;
            try {
                rank = GuildRank.valueOf(key);
            } catch (IllegalArgumentException e) {
                continue;
            }
            CompoundTag r = rightsTag.getCompound(key);
            RankRights rankRights = new RankRights(r.getLong("lc"));
            ListTag perTab = r.getList("tabs", Tag.TAG_COMPOUND);
            for (int i = 0; i < perTab.size(); i++) {
                CompoundTag tr = perTab.getCompound(i);
                Access access;
                try {
                    access = Access.valueOf(tr.getString("access"));
                } catch (IllegalArgumentException e) {
                    access = Access.NONE;
                }
                rankRights.tabs.add(new TabRights(access, tr.getInt("items")));
            }
            bank.rights.put(rank, rankRights);
        }
        CompoundTag usageTag = tag.getCompound("usage");
        for (String key : usageTag.getAllKeys()) {
            try {
                CompoundTag ut = usageTag.getCompound(key);
                Usage u = new Usage();
                u.day = ut.getLong("day");
                u.lc = ut.getLong("lc");
                CompoundTag st = ut.getCompound("items");
                for (String t : st.getAllKeys()) {
                    u.items.put(Integer.parseInt(t), st.getInt(t));
                }
                bank.usage.put(UUID.fromString(key), u);
            } catch (IllegalArgumentException ignored) {
                // Unreadable usage row: the player just starts today fresh.
            }
        }
        ListTag logList = tag.getList("log", Tag.TAG_COMPOUND);
        for (int i = 0; i < logList.size(); i++) {
            CompoundTag e = logList.getCompound(i);
            LogType type;
            try {
                type = LogType.valueOf(e.getString("type"));
            } catch (IllegalArgumentException ex) {
                continue;
            }
            bank.log.add(new LogEntry(e.getLong("time"), e.getUUID("player"), e.getString("name"), type,
                    e.getInt("tab"), e.contains("stack") ? ItemStack.parseOptional(registries, e.getCompound("stack"))
                    : ItemStack.EMPTY, e.getLong("amount"), e.getString("detail")));
        }
        return bank;
    }
}
