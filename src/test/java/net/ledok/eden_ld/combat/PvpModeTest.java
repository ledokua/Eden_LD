package net.ledok.eden_ld.combat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** World modes: dimensions.json's "pvp" (true / false from before the toggle, "toggle"), defaults, effective states. */
class PvpModeTest {

    @Test
    void dimensionsJsonKeepsReadingTrueAndFalse() {
        assertEquals(PvpMode.ON, PvpMode.parse("true"));
        assertEquals(PvpMode.OFF, PvpMode.parse("false"));
    }

    @Test
    void toggleAndTheModeWordsAreRead() {
        assertEquals(PvpMode.TOGGLE, PvpMode.parse("toggle"));
        assertEquals(PvpMode.TOGGLE, PvpMode.parse(" Toggle "));
        assertEquals(PvpMode.ON, PvpMode.parse("on"));
        assertEquals(PvpMode.OFF, PvpMode.parse("OFF"));
    }

    @Test
    void anythingElseIsNoMode() {
        assertNull(PvpMode.parse(null));
        assertNull(PvpMode.parse(""));
        assertNull(PvpMode.parse("yes"));
        assertNull(PvpMode.parse("1"));
    }

    @Test
    void theWordsRoundTrip() {
        for (PvpMode mode : PvpMode.values()) {
            assertEquals(mode, PvpMode.parse(mode.key));
            assertEquals(mode, PvpMode.byId(mode.ordinal()));
        }
        assertEquals(PvpMode.OFF, PvpMode.byId(-1));
        assertEquals(PvpMode.OFF, PvpMode.byId(99));
    }

    @Test
    void aDimensionWithoutItsOwnFollowsTheServer() {
        assertEquals(PvpMode.TOGGLE, PvpMode.serverDefault(true));
        assertEquals(PvpMode.OFF, PvpMode.serverDefault(false));
    }

    @Test
    void theEffectiveStateIsTheModeOrTheFlag() {
        assertFalse(PvpMode.OFF.effective(true));
        assertFalse(PvpMode.OFF.effective(false));
        assertTrue(PvpMode.ON.effective(false));
        assertTrue(PvpMode.ON.effective(true));
        assertTrue(PvpMode.TOGGLE.effective(true));
        assertFalse(PvpMode.TOGGLE.effective(false));
    }
}
