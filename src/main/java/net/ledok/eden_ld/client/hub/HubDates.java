package net.ledok.eden_ld.client.hub;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Dates in the hub, in the hub's language ({@link HubLanguage#code()}) — one format everywhere:
 * Ukrainian "04.10.2026" / "04.10", English "Oct 4, 2026" / "Oct 4" (never the ambiguous 10/4/26).
 */
final class HubDates {

    private static final DateTimeFormatter UK_FULL = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final DateTimeFormatter UK_SHORT = DateTimeFormatter.ofPattern("dd.MM");
    private static final DateTimeFormatter EN_FULL = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US);
    private static final DateTimeFormatter EN_SHORT = DateTimeFormatter.ofPattern("MMM d", Locale.US);

    private HubDates() {
    }

    /** Day, month and year. */
    static String full(long epochMillis) {
        return (ukrainian() ? UK_FULL : EN_FULL).format(at(epochMillis));
    }

    /** Day and month (lists where the year is obvious). */
    static String dayMonth(java.time.temporal.TemporalAccessor time) {
        return (ukrainian() ? UK_SHORT : EN_SHORT).format(time);
    }

    private static boolean ukrainian() {
        return HubLanguage.code().startsWith("uk");
    }

    private static ZonedDateTime at(long epochMillis) {
        return Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault());
    }
}
