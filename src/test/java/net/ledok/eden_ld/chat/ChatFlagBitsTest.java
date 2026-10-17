package net.ledok.eden_ld.chat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The ChatFlags word: switches in the low half, what the server knows in the high half (HubPayloads.ChatFlags). */
class ChatFlagBitsTest {

    /** {@code HubPayloads.ChatFlags.NEW_LOOK} and {@code HUD_STRIPS} (bits 0 and 1 of HubSettings' CHAT_FLAGS). */
    private static final int NEW_LOOK = 1;
    private static final int HUD_STRIPS = 2;

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
    void theNewLookIsKnownWithoutTheKnownHalf() {
        // The first 1.3.0 word carried the new look alone, with no known half: it still reads as sent.
        assertFalse(ChatFlagBits.on(0, NEW_LOOK));
        assertTrue(ChatFlagBits.known(0, NEW_LOOK));
        assertTrue(ChatFlagBits.on(1, NEW_LOOK));
        assertTrue(ChatFlagBits.on(0, HUD_STRIPS));
        assertFalse(ChatFlagBits.known(1, HUD_STRIPS));
    }
}
