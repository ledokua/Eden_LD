package net.ledok.eden_ld.hub.guild;

import net.ledok.eden_ld.EdenLD;
import net.ledok.eden_ld.config.EdenConfig;
import net.ledok.eden_ld.hub.profile.LevelPerks;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What guild levels give (config {@code guilds.level_perks}), in the rule format of the Profile
 * level perks ({@link LevelPerks.Rule}). Member perks (favorite slots, daily rerolls) count for every member;
 * the rest change the guild's board and bank prices. Shared by the server and the Level sub-tab.
 */
public final class GuildPerks {

    public enum Kind {
        /** Emoji favorite slots for every member. */
        EMOJI_SLOTS,
        /** Saved sticker slots for every member. */
        STICKER_SLOTS,
        /** Free daily quest rerolls per day for every member. */
        DAILY_REROLLS,
        /** Extra daily guild quests on the board. */
        DAILY_QUESTS,
        /** Extra weekly guild quests on the board. */
        WEEKLY_QUESTS,
        /** Percent off vault tabs, member upgrades and claim upgrades (90 at most). */
        TAB_DISCOUNT,
        MEMBER_UPGRADE_DISCOUNT,
        CLAIM_UPGRADE_DISCOUNT,
        /** Purchases allowed above the configured caps. */
        EXTRA_TABS,
        EXTRA_MEMBER_UPGRADES,
        EXTRA_CLAIM_UPGRADES;

        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }

        public boolean discount() {
            return this == TAB_DISCOUNT || this == MEMBER_UPGRADE_DISCOUNT || this == CLAIM_UPGRADE_DISCOUNT;
        }

        @Nullable
        public static Kind of(String id) {
            for (Kind kind : values()) {
                if (kind.id().equals(id)) {
                    return kind;
                }
            }
            return null;
        }
    }

    private GuildPerks() {
    }

    /** The server's rules (config); unknown perk names are left out. */
    public static List<LevelPerks.Rule> rules() {
        List<LevelPerks.Rule> rules = new ArrayList<>();
        for (EdenConfig.Profile.LevelPerk perk : EdenLD.CONFIG.guilds.level_perks) {
            if (perk != null && Kind.of(perk.perk) != null && perk.amount != 0) {
                rules.add(new LevelPerks.Rule(perk.perk, perk.from, perk.every, perk.amount, perk.until));
            }
        }
        return rules;
    }

    /** A perk's total at this guild level; discounts are capped at 90 percent. */
    public static int total(List<LevelPerks.Rule> rules, Kind kind, int level) {
        int total = LevelPerks.sum(rules, kind.id(), level);
        return kind.discount() ? Math.min(90, total) : total;
    }

    public static int total(Kind kind, int level) {
        return total(rules(), kind, level);
    }

    /** Every perk's total at this level (perks with nothing left out). */
    public static Map<Kind, Integer> totals(List<LevelPerks.Rule> rules, int level) {
        Map<Kind, Integer> totals = new EnumMap<>(Kind.class);
        for (Kind kind : Kind.values()) {
            int total = total(rules, kind, level);
            if (total != 0) {
                totals.put(kind, total);
            }
        }
        return totals;
    }

    /** What reaching exactly this level gives. */
    public static Map<Kind, Integer> at(List<LevelPerks.Rule> rules, int level) {
        Map<Kind, Integer> gains = new EnumMap<>(Kind.class);
        for (Kind kind : Kind.values()) {
            int gained = total(rules, kind, level) - total(rules, kind, level - 1);
            if (gained != 0) {
                gains.put(kind, gained);
            }
        }
        return gains;
    }

    /** "+1 emoji slot for members", "10% off vault tabs" (translated on the client). */
    public static Component describe(Kind kind, int amount) {
        return Component.translatable("gui.eden_ld.hub.guild.perk." + kind.id(),
                kind.discount() ? String.valueOf(amount) : (amount > 0 ? "+" : "") + amount);
    }

    /** Several perks as one line. */
    public static Component describe(Map<Kind, Integer> perks) {
        MutableComponent line = Component.empty();
        boolean first = true;
        for (Map.Entry<Kind, Integer> perk : perks.entrySet()) {
            if (!first) {
                line.append(", ");
            }
            line.append(describe(perk.getKey(), perk.getValue()));
            first = false;
        }
        return line;
    }
}
