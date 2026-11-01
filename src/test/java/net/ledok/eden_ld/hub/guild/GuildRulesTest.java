package net.ledok.eden_ld.hub.guild;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Guild rules (GuildRules): names and tags as staff renames check them, reasons, the staff log cap, a frozen bank. */
class GuildRulesTest {

    // ---------------------------------------------------------------- names

    @Test
    void namesAreThreeToTwentyFourCharacters() {
        assertEquals("bad_name", GuildRules.nameProblem("ab", List.of()));
        assertNull(GuildRules.nameProblem("abc", List.of()));
        assertNull(GuildRules.nameProblem("a".repeat(24), List.of()));
        assertEquals("bad_name", GuildRules.nameProblem("a".repeat(25), List.of()));
        // Trimmed first: spaces around don't count.
        assertEquals("bad_name", GuildRules.nameProblem("  ab  ", List.of()));
        assertEquals("bad_name", GuildRules.nameProblem(null, List.of()));
    }

    @Test
    void namesAreUniqueIgnoringCase() {
        assertEquals("name_taken", GuildRules.nameProblem("Iron Wolves", List.of("iron wolves")));
        assertEquals("name_taken", GuildRules.nameProblem(" Iron Wolves ", List.of("IRON WOLVES")));
        assertNull(GuildRules.nameProblem("Iron Wolves", List.of("Iron Wolf", "Wolves")));
    }

    @Test
    void lengthIsReportedBeforeUniqueness() {
        assertEquals("bad_name", GuildRules.nameProblem("ab", List.of("ab")));
    }

    // ---------------------------------------------------------------- tags

    @Test
    void tagsAreOneToFiveLettersOrDigits() {
        assertEquals("bad_tag", GuildRules.tagProblem("", List.of()));
        assertNull(GuildRules.tagProblem("A", List.of()));
        assertNull(GuildRules.tagProblem("WOLF5", List.of()));
        assertEquals("bad_tag", GuildRules.tagProblem("WOLVES", List.of()));
        assertEquals("bad_tag", GuildRules.tagProblem("W-1", List.of()));
        assertEquals("bad_tag", GuildRules.tagProblem("W 1", List.of()));
    }

    @Test
    void tagsOfAnyAlphabetCountByCodePoints() {
        assertNull(GuildRules.tagProblem("ВОВК", List.of()));
        assertNull(GuildRules.tagProblem("ЇЖАКИ", List.of()));
        assertEquals("bad_tag", GuildRules.tagProblem("ЇЖАЧОК", List.of()));
    }

    @Test
    void tagsAreUniqueIgnoringCase() {
        assertEquals("tag_taken", GuildRules.tagProblem("wolf", List.of("WOLF")));
        assertEquals("tag_taken", GuildRules.tagProblem("вовк", List.of("ВОВК")));
        assertNull(GuildRules.tagProblem("WOLF", List.of("WOLF2")));
    }

    // ---------------------------------------------------------------- reasons

    @Test
    void aReasonIsRequiredAndAtMostTwoHundredCharacters() {
        assertEquals("reason_missing", GuildRules.reasonProblem(null));
        assertEquals("reason_missing", GuildRules.reasonProblem(""));
        assertEquals("reason_missing", GuildRules.reasonProblem("   "));
        assertNull(GuildRules.reasonProblem("Scam reported in ticket 12"));
        assertNull(GuildRules.reasonProblem("x".repeat(GuildRules.MAX_REASON)));
        assertEquals("reason_too_long", GuildRules.reasonProblem("x".repeat(GuildRules.MAX_REASON + 1)));
        // Surrounding blanks don't count toward the limit.
        assertNull(GuildRules.reasonProblem("  " + "x".repeat(GuildRules.MAX_REASON) + "  "));
    }

    // ---------------------------------------------------------------- staff log

    @Test
    void theStaffLogKeepsTheNewestTwoHundred() {
        List<Integer> log = new ArrayList<>();
        for (int i = 1; i <= GuildRules.STAFF_LOG_LIMIT + 5; i++) {
            GuildRules.addCapped(log, i, GuildRules.STAFF_LOG_LIMIT);
        }
        assertEquals(GuildRules.STAFF_LOG_LIMIT, log.size());
        assertEquals(6, log.get(0));
        assertEquals(GuildRules.STAFF_LOG_LIMIT + 5, log.get(log.size() - 1));
    }

    @Test
    void underTheCapNothingIsDropped() {
        List<String> log = new ArrayList<>(List.of("a", "b"));
        GuildRules.addCapped(log, "c", 3);
        assertEquals(List.of("a", "b", "c"), log);
    }

    @Test
    void aListAlreadyOverTheCapIsTrimmedToIt() {
        List<Integer> log = new ArrayList<>(List.of(1, 2, 3, 4, 5));
        GuildRules.addCapped(log, 6, 3);
        assertEquals(List.of(4, 5, 6), log);
    }

    // ---------------------------------------------------------------- freeze

    @Test
    void aFrozenBankRefusesWithdrawalsAndPurchasesButTakesDeposits() {
        assertTrue(GuildRules.refused(true, GuildRules.BankMove.WITHDRAW));
        assertTrue(GuildRules.refused(true, GuildRules.BankMove.PURCHASE));
        assertFalse(GuildRules.refused(true, GuildRules.BankMove.DEPOSIT));
    }

    @Test
    void aBankNotFrozenRefusesNothing() {
        for (GuildRules.BankMove move : GuildRules.BankMove.values()) {
            assertFalse(GuildRules.refused(false, move), move.name());
        }
    }
}
