package net.ledok.eden_ld.hub.guild;

import java.util.List;
import java.util.Locale;

/**
 * Guild rules apart from the game (tested): name and tag format and uniqueness, the reason every staff action needs,
 * the capped staff log, and what a frozen bank refuses. {@link GuildManager} and {@link GuildBankService} turn the
 * answers into messages.
 */
public final class GuildRules {

    public static final int MIN_NAME_LENGTH = 3;
    public static final int MAX_NAME_LENGTH = 24;
    public static final int MAX_TAG_LENGTH = 5;
    /** A staff action's reason: required, at most this long. */
    public static final int MAX_REASON = 200;
    /** Staff log entries a guild keeps (the newest). */
    public static final int STAFF_LOG_LIMIT = 200;

    /** What a bank action does with the bank's contents: only deposits get through a freeze. */
    public enum BankMove { DEPOSIT, WITHDRAW, PURCHASE }

    private GuildRules() {
    }

    /**
     * Null when {@code name} (trimmed) may be a guild's name beside {@code others} (the other guilds' names); else
     * {@code bad_name} (length) or {@code name_taken} (case-insensitive).
     */
    public static String nameProblem(String name, Iterable<String> others) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.length() < MIN_NAME_LENGTH || trimmed.length() > MAX_NAME_LENGTH) {
            return "bad_name";
        }
        return taken(trimmed, others) ? "name_taken" : null;
    }

    /** As {@link #nameProblem} for a tag: 1–{@value #MAX_TAG_LENGTH} letters or digits of any alphabet. */
    public static String tagProblem(String tag, Iterable<String> others) {
        String trimmed = tag == null ? "" : tag.trim();
        int length = trimmed.codePointCount(0, trimmed.length());
        if (length < 1 || length > MAX_TAG_LENGTH || !trimmed.codePoints().allMatch(Character::isLetterOrDigit)) {
            return "bad_tag";
        }
        return taken(trimmed, others) ? "tag_taken" : null;
    }

    private static boolean taken(String value, Iterable<String> others) {
        String wanted = value.toLowerCase(Locale.ROOT);
        for (String other : others) {
            if (other != null && other.trim().toLowerCase(Locale.ROOT).equals(wanted)) {
                return true;
            }
        }
        return false;
    }

    /** Null when the reason will do; else {@code reason_missing} (blank) or {@code reason_too_long}. */
    public static String reasonProblem(String reason) {
        String trimmed = reason == null ? "" : reason.strip();
        if (trimmed.isEmpty()) {
            return "reason_missing";
        }
        return trimmed.length() > MAX_REASON ? "reason_too_long" : null;
    }

    /** Appends {@code entry} and drops the oldest past {@code cap} ({@code list} oldest first). */
    public static <T> void addCapped(List<T> list, T entry, int cap) {
        list.add(entry);
        int excess = list.size() - Math.max(0, cap);
        if (excess > 0) {
            list.subList(0, excess).clear();
        }
    }

    /** Whether a frozen bank refuses this: everything that takes LC or items out (withdrawals, purchases). */
    public static boolean refused(boolean frozen, BankMove move) {
        return frozen && move != BankMove.DEPOSIT;
    }
}
