package net.ledok.eden_ld.chat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The ChatFlags word: switches in the low half, what the server knows in the high half (HubPayloads.ChatFlags). */
class ChatFlagBitsTest {

    /**
     * {@code HubPayloads.ChatFlags.NEW_LOOK}, {@code HUD_STRIPS}, {@code SYSTEM_IN_TABS} and {@code ALL_TAB} (bits 0 to
     * 3 of HubSettings' CHAT_FLAGS).
     */
    private static final int NEW_LOOK = 1;
    private static final int HUD_STRIPS = 2;
    private static final int SYSTEM_IN_TABS = 4;
    private static final int ALL_TAB = 8;

    @Test
    void aPackedWordIsNeverTheUnsentMark() {
        for (int n = 0; n <= ChatFlagBits.MAX_FLAGS; n++) {
            boolean[] all = new boolean[n];
            java.util.Arrays.fill(all, true);
            int flags = ChatFlagBits.pack(all);
            assertNotEquals(ChatFlagBits.UNSENT, flags, n + " switches, all on");
            assertTrue(flags >= 0, "the known half stays clear of the sign bit");
        }
        assertThrows(IllegalArgumentException.class, () -> ChatFlagBits.pack(new boolean[ChatFlagBits.MAX_FLAGS + 1]));
    }

    @Test
    void unsentMeansEverythingOnAndNothingKnown() {
        for (int bit = 1; bit != 1 << ChatFlagBits.KNOWN_SHIFT; bit <<= 1) {
            assertTrue(ChatFlagBits.on(ChatFlagBits.UNSENT, bit));
            assertFalse(ChatFlagBits.known(ChatFlagBits.UNSENT, bit));
        }
    }

    @Test
    void anUnknownSwitchReadsOnAndAKnownZeroReadsOff() {
        // A server with only the new look, turned off: the strips are news to it.
        int older = ChatFlagBits.pack(new boolean[]{false});
        assertFalse(ChatFlagBits.on(older, NEW_LOOK));
        assertTrue(ChatFlagBits.known(older, NEW_LOOK));
        assertTrue(ChatFlagBits.on(older, HUD_STRIPS), "unknown: its default");
        assertFalse(ChatFlagBits.known(older, HUD_STRIPS), "its control stays hidden");
        // This server: Classic picked.
        int flags = ChatFlagBits.pack(new boolean[]{true, false});
        assertTrue(ChatFlagBits.on(flags, NEW_LOOK));
        assertFalse(ChatFlagBits.on(flags, HUD_STRIPS));
        assertTrue(ChatFlagBits.known(flags, HUD_STRIPS));
    }

    @Test
    void twoSwitchesRoundTrip() {
        for (int mask = 0; mask < 4; mask++) {
            boolean look = (mask & 1) != 0;
            boolean strips = (mask & 2) != 0;
            int flags = ChatFlagBits.pack(new boolean[]{look, strips});
            assertEquals(mask | 3 << ChatFlagBits.KNOWN_SHIFT, flags);
            assertEquals(look, ChatFlagBits.on(flags, NEW_LOOK));
            assertEquals(strips, ChatFlagBits.on(flags, HUD_STRIPS));
            assertTrue(ChatFlagBits.known(flags, NEW_LOOK));
            assertTrue(ChatFlagBits.known(flags, HUD_STRIPS));
            assertFalse(ChatFlagBits.known(flags, 4), "a third switch is news to this server");
            assertTrue(ChatFlagBits.on(flags, 4));
        }
    }

    @Test
    void fourSwitchesRoundTrip() {
        int[] bits = {NEW_LOOK, HUD_STRIPS, SYSTEM_IN_TABS, ALL_TAB};
        for (int mask = 0; mask < 16; mask++) {
            boolean[] values = new boolean[bits.length];
            for (int i = 0; i < bits.length; i++) {
                values[i] = (mask & bits[i]) != 0;
            }
            int flags = ChatFlagBits.pack(values);
            assertEquals(mask | 15 << ChatFlagBits.KNOWN_SHIFT, flags);
            for (int i = 0; i < bits.length; i++) {
                assertEquals(values[i], ChatFlagBits.on(flags, bits[i]), mask + " bit " + i);
                assertTrue(ChatFlagBits.known(flags, bits[i]));
            }
            assertFalse(ChatFlagBits.known(flags, 16), "a fifth switch is news to this server");
        }
    }

    @Test
    void aOneThreeZeroServerKnowsNeitherTabSwitch() {
        // Eden 1.3.0: the new look and the strips only. The tab switches read on, their options stay hidden.
        int flags = ChatFlagBits.pack(new boolean[]{true, false});
        for (int bit : new int[]{SYSTEM_IN_TABS, ALL_TAB}) {
            assertTrue(ChatFlagBits.on(flags, bit));
            assertFalse(ChatFlagBits.known(flags, bit));
        }
        // Nothing sent at all (a 1.2.x server): on and unknown too.
        assertTrue(ChatFlagBits.on(ChatFlagBits.UNSENT, ALL_TAB));
        assertFalse(ChatFlagBits.known(ChatFlagBits.UNSENT, SYSTEM_IN_TABS));
        // This server, both turned off.
        int off = ChatFlagBits.pack(new boolean[]{true, true, false, false});
        assertFalse(ChatFlagBits.on(off, SYSTEM_IN_TABS));
        assertFalse(ChatFlagBits.on(off, ALL_TAB));
        assertTrue(ChatFlagBits.on(off, HUD_STRIPS));
    }

    @Test
    void theNewLookIsKnownWithoutTheKnownHalf() {
        // The first 1.3.0 word carried the new look alone, with no known half: it still reads as sent.
        assertFalse(ChatFlagBits.on(0, NEW_LOOK));
        assertTrue(ChatFlagBits.known(0, NEW_LOOK));
        assertTrue(ChatFlagBits.on(1, NEW_LOOK));
        assertTrue(ChatFlagBits.on(0, HUD_STRIPS));
        assertFalse(ChatFlagBits.known(1, HUD_STRIPS));
    }
}
