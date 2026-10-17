package net.ledok.eden_ld.chat;

/**
 * The word the {@code ChatFlags} payload carries: the chat switches that are on until the player turns them off
 * ({@code HubSettings.chatFlags}), one bit each in the low half, and in the high half which of them the server knows.
 * A switch the server doesn't know (a client newer than the server) reads as on, its default, and the client hides its
 * control — the server couldn't keep the choice. -1 is what the client holds until the server says (and on a server
 * that never does): everything on, nothing known; a packed word never equals it.
 */
public final class ChatFlagBits {

    /** Where the known half starts. */
    public static final int KNOWN_SHIFT = 16;
    /** The most switches a word holds: the known half stays clear of the sign bit, so a word is never -1. */
    public static final int MAX_FLAGS = 15;
    /** No word yet (or a server without the payload). */
    public static final int UNSENT = -1;
    /** Bits every word knows: the first one (the new look) came before the known half. */
    private static final int ALWAYS_KNOWN = 1;

    private ChatFlagBits() {
    }

    /** The word for these switches (bit {@code i} = {@code values[i]}), every one of them known. */
    public static int pack(boolean[] values) {
        if (values.length > MAX_FLAGS) {
            throw new IllegalArgumentException("at most " + MAX_FLAGS + " chat flags, got " + values.length);
        }
        int flags = 0;
        for (int i = 0; i < values.length; i++) {
            if (values[i]) {
                flags |= 1 << i;
            }
        }
        return flags | ((1 << values.length) - 1) << KNOWN_SHIFT;
    }

    /** Whether the server that sent {@code flags} knows the switch {@code bit} (it can keep the player's choice). */
    public static boolean known(int flags, int bit) {
        return flags != UNSENT && ((flags >>> KNOWN_SHIFT | ALWAYS_KNOWN) & bit) != 0;
    }

    /** Whether the switch {@code bit} is on: as sent when the server knows it, else its default (on). */
    public static boolean on(int flags, int bit) {
        return !known(flags, bit) || (flags & bit) != 0;
    }
}
