package net.ledok.eden_ld.hub.profile;

import net.ledok.eden_ld.EdenLD;
import net.ledok.eden_ld.config.EdenConfig;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What the Minestar activity level gives (config {@code profile.level_perks}): each rule gives
 * {@code amount} of a perk at level {@code from}, then every {@code every} levels, up to
 * {@code until}. The math is shared by the server (limits, messages) and the client (the Profile
 * tab's track, from the rules the server sends).
 */
public final class LevelPerks {

    public enum Kind {
        /** Emoji favorite slots. */
        EMOJI_SLOTS,
        /** Saved sticker slots. */
        STICKER_SLOTS,
        /** Open Parties and Claims bonus claims on the player's own limit. */
        CLAIMS,
        /** Free daily quest rerolls per day. */
        DAILY_REROLLS;

        public String id() {
            return name().toLowerCase(Locale.ROOT);
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

    /** One rule: {@code amount} of {@code perk} at {@code from}, every {@code every} levels (0 = once), up to {@code until} (0 = no end). */
    public record Rule(String perk, int from, int every, int amount, int until) {
    }

    /** Reaching {@code level} gives {@code amount} of {@code kind}. */
    public record Gain(int level, Kind kind, int amount) {
    }

    private LevelPerks() {
    }

    /** The server's rules (config). */
    public static List<Rule> rules() {
        List<Rule> rules = new ArrayList<>();
        for (EdenConfig.Profile.LevelPerk perk : EdenLD.CONFIG.profile.level_perks) {
            if (perk != null && Kind.of(perk.perk) != null && perk.amount != 0) {
                rules.add(new Rule(perk.perk, perk.from, perk.every, perk.amount, perk.until));
            }
        }
        return rules;
    }

    /** A perk's total at this level under the server's rules. */
    public static int total(Kind kind, int level) {
        return total(rules(), kind, level);
    }

    public static int total(List<Rule> rules, Kind kind, int level) {
        return sum(rules, kind.id(), level);
    }

    /** A perk id's total at this level under these rules (profile or guild perks alike). */
    public static int sum(List<Rule> rules, String perk, int level) {
        int sum = 0;
        for (Rule rule : rules) {
            if (perk.equals(rule.perk())) {
                sum += rule.amount() * count(rule, level);
            }
        }
        return Math.max(0, sum);
    }

    /** Every perk's total at this level (perks with nothing left out). */
    public static Map<Kind, Integer> totals(List<Rule> rules, int level) {
        Map<Kind, Integer> totals = new EnumMap<>(Kind.class);
        for (Kind kind : Kind.values()) {
            int total = total(rules, kind, level);
            if (total != 0) {
                totals.put(kind, total);
            }
        }
        return totals;
    }

    /** How often the rule fired up to and including this level. */
    private static int count(Rule rule, int level) {
        int from = Math.max(1, rule.from());
        int top = rule.until() > 0 ? Math.min(level, rule.until()) : level;
        if (top < from) {
            return 0;
        }
        return rule.every() > 0 ? (top - from) / rule.every() + 1 : 1;
    }

    /** What reaching exactly this level gives. */
    public static List<Gain> at(List<Rule> rules, int level) {
        List<Gain> gains = new ArrayList<>();
        for (Kind kind : Kind.values()) {
            int gained = total(rules, kind, level) - total(rules, kind, level - 1);
            if (gained != 0) {
                gains.add(new Gain(level, kind, gained));
            }
        }
        return gains;
    }

    /** The next level above {@code level} (searched up to 200 further) that gives anything, or -1. */
    public static int nextPerkLevel(List<Rule> rules, int level) {
        for (int next = level + 1; next <= level + 200; next++) {
            if (!at(rules, next).isEmpty()) {
                return next;
            }
        }
        return -1;
    }

    /** "+1 emoji slot" (translated on the client). */
    public static Component describe(Kind kind, int amount) {
        return Component.translatable("gui.eden_ld.hub.profile.perk." + kind.id(), (amount > 0 ? "+" : "") + amount);
    }

    /** Several gains as one line: "+1 emoji slot, +5 claims". */
    public static Component describe(Map<Kind, Integer> gains) {
        net.minecraft.network.chat.MutableComponent line = Component.empty();
        boolean first = true;
        for (Map.Entry<Kind, Integer> gain : gains.entrySet()) {
            if (!first) {
                line.append(", ");
            }
            line.append(describe(gain.getKey(), gain.getValue()));
            first = false;
        }
        return line;
    }
}
