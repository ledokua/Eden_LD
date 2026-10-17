package net.ledok.eden_ld.client.chat.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A stale channel state can't flip a pick back, and the guard always ends. */
class SelectGuardTest {

    private static final int GLOBAL = 0;
    private static final int LOCAL = 1;
    private static final int PARTY = 2;

    @Test
    void withNoPickEveryStateApplies() {
        SelectGuard guard = new SelectGuard();
        assertTrue(guard.accept(GLOBAL, 0));
        assertTrue(guard.accept(LOCAL, false, 0));
        assertEquals(-1, guard.expire(10_000));
        assertEquals(-1, guard.pending());
    }

    @Test
    void theAnswerEndsTheGuard() {
        SelectGuard guard = new SelectGuard();
        guard.selected(LOCAL, 1000);
        assertEquals(LOCAL, guard.pending());
        assertTrue(guard.accept(LOCAL, 1100));
        assertEquals(-1, guard.pending());
        // Later states are the server's word again.
        assertTrue(guard.accept(GLOBAL, 1200));
    }

    @Test
    void aStaleStateIsHeldThenAppliedWhenTheHoldRunsOut() {
        SelectGuard guard = new SelectGuard();
        guard.selected(LOCAL, 1000);
        // Built before the server handled the pick (someone's party changed): its channel waits.
        assertFalse(guard.accept(GLOBAL, 1050));
        assertEquals(-1, guard.expire(1000 + SelectGuard.HOLD_MS - 1));
        // No answer came: the last held channel applies, and the guard ends.
        assertEquals(GLOBAL, guard.expire(1000 + SelectGuard.HOLD_MS));
        assertEquals(-1, guard.pending());
        assertEquals(-1, guard.expire(5000));
    }

    @Test
    void theAnswerAfterAStaleStateWins() {
        SelectGuard guard = new SelectGuard();
        guard.selected(PARTY, 0);
        assertFalse(guard.accept(GLOBAL, 100));
        assertTrue(guard.accept(PARTY, 200));
        // The held channel is dropped with the guard: nothing flips back later.
        assertEquals(-1, guard.expire(SelectGuard.HOLD_MS * 2));
    }

    @Test
    void aStateThatClosesThePickedChannelAppliesAtOnce() {
        SelectGuard guard = new SelectGuard();
        guard.selected(PARTY, 0);
        // The party is gone (the server refused the pick, or fell back to Global): no 1.5 s of a tab that isn't there.
        assertTrue(guard.accept(GLOBAL, false, 10));
        assertEquals(-1, guard.pending());
        assertEquals(-1, guard.expire(SelectGuard.HOLD_MS));
    }

    @Test
    void aStateAfterTheHoldAppliesAtOnce() {
        SelectGuard guard = new SelectGuard();
        guard.selected(LOCAL, 0);
        assertTrue(guard.accept(GLOBAL, SelectGuard.HOLD_MS));
        assertEquals(-1, guard.pending());
    }

    @Test
    void aSecondPickWaitsForItsOwnAnswer() {
        SelectGuard guard = new SelectGuard();
        guard.selected(LOCAL, 0);
        assertFalse(guard.accept(GLOBAL, 50));
        guard.selected(PARTY, 100);
        // The first pick's answer is stale now.
        assertFalse(guard.accept(LOCAL, 150));
        assertTrue(guard.accept(PARTY, 200));
    }

    @Test
    void aStaleStateNamingTheLatestPickWaitsForTheEarlierAnswer() {
        SelectGuard guard = new SelectGuard();
        // Ctrl+Tab to Local and Ctrl+Shift+Tab back to Global within one round trip.
        guard.selected(LOCAL, 0);
        guard.selected(GLOBAL, 50);
        // Someone's party changed: a state built from the old stored channel, which happens to be Global.
        assertFalse(guard.accept(GLOBAL, 100));
        // The answer to the Local pick: Local is never applied.
        assertFalse(guard.accept(LOCAL, 150));
        // The answer to the Global pick ends the guard.
        assertTrue(guard.accept(GLOBAL, 200));
        assertEquals(-1, guard.pending());
        assertEquals(-1, guard.expire(SelectGuard.HOLD_MS * 2));
    }

    @Test
    void resetForgetsEverything() {
        SelectGuard guard = new SelectGuard();
        guard.selected(LOCAL, 0);
        assertFalse(guard.accept(GLOBAL, 10));
        guard.reset();
        assertEquals(-1, guard.pending());
        assertEquals(-1, guard.expire(SelectGuard.HOLD_MS * 2));
        assertTrue(guard.accept(GLOBAL, 20));
        // Unanswered picks are forgotten too: after a new server, one pick's answer ends the guard.
        guard.selected(LOCAL, 30);
        guard.selected(PARTY, 40);
        guard.reset();
        guard.selected(LOCAL, 50);
        assertTrue(guard.accept(LOCAL, 60));
        assertEquals(-1, guard.pending());
    }
}
