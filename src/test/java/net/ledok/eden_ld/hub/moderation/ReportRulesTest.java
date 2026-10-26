package net.ledok.eden_ld.hub.moderation;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Filing and keeping reports (ReportRules): the rate limit, the caps, and which reports go when there are too many. */
class ReportRulesTest {

    private static final long NOW = 1_800_000_000_000L;
    private static final UUID ALEX = new UUID(1, 1);
    private static final UUID BOB = new UUID(2, 2);

    private static long nextId = 1;

    private static Report report(UUID reporter, long time) {
        return new Report(nextId++, time, reporter, reporter.equals(ALEX) ? "Alex" : "Bob", "duels_ld:wager", "#1",
                "text", List.of());
    }

    private static Report handled(long time) {
        Report report = report(ALEX, time);
        report.handled = true;
        return report;
    }

    // ---------------------------------------------------------------- rate limit

    @Test
    void fiveReportsInTenMinutesThenNoMore() {
        List<Report> reports = new ArrayList<>();
        for (int i = 0; i < ReportRules.RATE_LIMIT; i++) {
            assertTrue(ReportRules.mayFile(reports, ALEX, NOW), "report " + (i + 1));
            reports.add(report(ALEX, NOW - 60_000L * (4 - i)));
        }
        assertFalse(ReportRules.mayFile(reports, ALEX, NOW));
    }

    @Test
    void theLimitIsPerReporter() {
        List<Report> reports = new ArrayList<>();
        for (int i = 0; i < ReportRules.RATE_LIMIT; i++) {
            reports.add(report(ALEX, NOW - 1000));
        }
        assertFalse(ReportRules.mayFile(reports, ALEX, NOW));
        assertTrue(ReportRules.mayFile(reports, BOB, NOW));
    }

    @Test
    void reportsOlderThanTheWindowDontCount() {
        List<Report> reports = new ArrayList<>();
        reports.add(report(ALEX, NOW - ReportRules.RATE_WINDOW_MILLIS));
        for (int i = 0; i < ReportRules.RATE_LIMIT - 1; i++) {
            reports.add(report(ALEX, NOW - 1000));
        }
        assertTrue(ReportRules.mayFile(reports, ALEX, NOW), "the oldest is exactly ten minutes old");
        reports.add(report(ALEX, NOW - 500));
        assertFalse(ReportRules.mayFile(reports, ALEX, NOW));
        assertTrue(ReportRules.mayFile(reports, ALEX, NOW + ReportRules.RATE_WINDOW_MILLIS));
    }

    // ---------------------------------------------------------------- caps

    @Test
    void textIsTrimmedAndCut() {
        assertEquals("", ReportRules.clip(null, ReportRules.MAX_TEXT));
        assertEquals("", ReportRules.clip("   \n ", ReportRules.MAX_TEXT));
        assertEquals("he cheated", ReportRules.clip("  he cheated \n", ReportRules.MAX_TEXT));
        String longText = "x".repeat(ReportRules.MAX_TEXT + 50);
        assertEquals(ReportRules.MAX_TEXT, ReportRules.clip(longText, ReportRules.MAX_TEXT).length());
    }

    @Test
    void cuttingNeverSplitsASurrogatePair() {
        String text = "a".repeat(ReportRules.MAX_TEXT - 1) + "😀" + "b";
        String clipped = ReportRules.clip(text, ReportRules.MAX_TEXT);
        assertEquals(ReportRules.MAX_TEXT - 1, clipped.length());
        assertFalse(Character.isHighSurrogate(clipped.charAt(clipped.length() - 1)));
    }

    @Test
    void theLogKeepsItsFirstLinesEachCut() {
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < ReportRules.MAX_LOG_LINES + 30; i++) {
            lines.add("line " + i);
        }
        lines.set(3, "y".repeat(ReportRules.MAX_LOG_LINE + 100));
        lines.set(4, null);
        List<String> log = ReportRules.log(lines);
        assertEquals(ReportRules.MAX_LOG_LINES, log.size());
        assertEquals("line 0", log.get(0));
        assertEquals(ReportRules.MAX_LOG_LINE, log.get(3).length());
        assertEquals("", log.get(4));
        assertEquals("line " + (ReportRules.MAX_LOG_LINES - 1), log.get(log.size() - 1));
        assertEquals(List.of(), ReportRules.log(null));
    }

    @Test
    void logLinesKeepTheirIndent() {
        assertEquals(List.of("  - Alex staked 50", ""), ReportRules.log(List.of("  - Alex staked 50  ", "   ")));
    }

    // ---------------------------------------------------------------- pruning

    @Test
    void nothingGoesUnderTheCap() {
        List<Report> reports = new ArrayList<>(List.of(handled(0), report(ALEX, 1)));
        ReportRules.prune(reports, NOW, 2);
        assertEquals(2, reports.size());
    }

    @Test
    void oldHandledReportsGoFirst() {
        long old = NOW - ReportRules.HANDLED_AGE_MILLIS - 1;
        Report oldestOpen = report(ALEX, old - 10);
        Report oldHandled = handled(old);
        Report recentHandled = handled(NOW - 1000);
        Report recentOpen = report(BOB, NOW);
        List<Report> reports = new ArrayList<>(List.of(oldestOpen, oldHandled, recentHandled, recentOpen));
        ReportRules.prune(reports, NOW, 3);
        assertEquals(List.of(oldestOpen, recentHandled, recentOpen), reports);
    }

    @Test
    void thenTheOldestWhateverTheirState() {
        Report a = report(ALEX, NOW - 3000);
        Report b = handled(NOW - 2000);
        Report c = report(BOB, NOW - 1000);
        List<Report> reports = new ArrayList<>(List.of(a, b, c));
        ReportRules.prune(reports, NOW, 1);
        assertEquals(List.of(c), reports);
    }

    @Test
    void theNewestTwoThousandStay() {
        List<Report> reports = new ArrayList<>();
        for (int i = 0; i < ReportRules.KEEP + 5; i++) {
            reports.add(report(ALEX, NOW - (ReportRules.KEEP + 5 - i)));
        }
        Report newest = reports.get(reports.size() - 1);
        Report firstKept = reports.get(5);
        ReportRules.prune(reports, NOW, ReportRules.KEEP);
        assertEquals(ReportRules.KEEP, reports.size());
        assertEquals(firstKept, reports.get(0));
        assertEquals(newest, reports.get(reports.size() - 1));
    }
}
