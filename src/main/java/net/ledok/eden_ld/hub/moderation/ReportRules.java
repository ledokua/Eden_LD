package net.ledok.eden_ld.hub.moderation;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

/**
 * The rules of filing and keeping reports, apart from the game (tested): a few reports per player per ten minutes,
 * text and log cut to their caps rather than refused, and the newest {@link #KEEP} kept — old handled ones go first.
 */
public final class ReportRules {

    public static final int MAX_TEXT = 500;
    public static final int MAX_LOG_LINES = 200;
    public static final int MAX_LOG_LINE = 300;
    public static final int MAX_NOTE = 200;
    /** Category and reference: ids, not prose. */
    public static final int MAX_LABEL = 64;
    /** Reports one player may file within {@link #RATE_WINDOW_MILLIS}. */
    public static final int RATE_LIMIT = 5;
    public static final long RATE_WINDOW_MILLIS = 10 * 60_000L;
    /** Reports kept at most; past that, handled ones older than {@link #HANDLED_AGE_MILLIS} drop first, then the oldest. */
    public static final int KEEP = 2000;
    public static final long HANDLED_AGE_MILLIS = 90L * 24 * 60 * 60_000L;

    private ReportRules() {
    }

    /** Trimmed and cut to {@code max} characters (never splitting a surrogate pair); "" for null. */
    public static String clip(String value, int max) {
        if (value == null) {
            return "";
        }
        String text = value.strip();
        if (text.length() <= max) {
            return text;
        }
        int end = Character.isHighSurrogate(text.charAt(max - 1)) ? max - 1 : max;
        return text.substring(0, end);
    }

    /** The first {@link #MAX_LOG_LINES} lines, each cut to {@link #MAX_LOG_LINE} (only their line ends trimmed). */
    public static List<String> log(List<String> lines) {
        List<String> out = new ArrayList<>();
        if (lines == null) {
            return out;
        }
        for (String line : lines) {
            if (out.size() >= MAX_LOG_LINES) {
                break;
            }
            String value = line == null ? "" : line.stripTrailing();
            if (value.length() > MAX_LOG_LINE) {
                int end = Character.isHighSurrogate(value.charAt(MAX_LOG_LINE - 1)) ? MAX_LOG_LINE - 1 : MAX_LOG_LINE;
                value = value.substring(0, end);
            }
            out.add(value);
        }
        return out;
    }

    /** Whether {@code reporter} may file another report now ({@code reports} oldest first). */
    public static boolean mayFile(List<Report> reports, UUID reporter, long now) {
        int recent = 0;
        for (int i = reports.size() - 1; i >= 0; i--) {
            Report report = reports.get(i);
            if (report.time <= now - RATE_WINDOW_MILLIS) {
                // Oldest first: everything further back is older still.
                break;
            }
            if (report.reporter.equals(reporter) && ++recent >= RATE_LIMIT) {
                return false;
            }
        }
        return true;
    }

    /** Drops reports past {@code keep} ({@code reports} oldest first): handled ones older than the age first, then the oldest. */
    public static void prune(List<Report> reports, long now, int keep) {
        int excess = reports.size() - keep;
        if (excess <= 0) {
            return;
        }
        for (Iterator<Report> it = reports.iterator(); it.hasNext() && excess > 0; ) {
            Report report = it.next();
            if (report.handled && now - report.time > HANDLED_AGE_MILLIS) {
                it.remove();
                excess--;
            }
        }
        if (excess > 0) {
            reports.subList(0, excess).clear();
        }
    }
}
