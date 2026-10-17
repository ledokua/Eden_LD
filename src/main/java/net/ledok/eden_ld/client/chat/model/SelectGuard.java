package net.ledok.eden_ld.client.chat.model;

/**
 * Keeps a channel the player just picked from being flipped back by a stale state ({@code docs/PLAN-chat-v2.md},
 * phase 5). The client switches at once and tells the server; the server may still send a state it built before it
 * handled that (any party, guild or permission change pushes one to everyone), which names the old channel. For at most
 * {@link #HOLD_MS} after a pick, a state naming another channel is held back — the rest of it still applies — until
 * the server's answer (its state with the picked channel) arrives; when the hold runs out the last held channel is
 * applied after all. A state that closes the picked channel (the party or guild is gone) is the server's answer: it
 * applies at once. Picks made one after another each wait for their answer (the server sends one state per pick), so
 * a stale state that happens to name the latest pick (Local and back to Global within one round trip) can't end the
 * guard and let the first pick's answer flip the channel. Plain code: the caller hands in the clock.
 */
public final class SelectGuard {

    /** How long a pick holds out against states naming another channel. */
    public static final long HOLD_MS = 1500;

    /** The channel picked (ordinal), -1 when no pick waits for its answer. */
    private int pending = -1;
    private long since;
    /** The last channel held back, -1 none. */
    private int held = -1;
    /**
     * Picks whose answer may still be on its way: one per pick, one less per state while a pick waits (a stale state
     * can't be told from an answer, so it counts too).
     */
    private int outstanding;

    /** The client switched to {@code channel} (ordinal) on its own, and told the server. */
    public void selected(int channel, long now) {
        pending = channel;
        since = now;
        held = -1;
        outstanding++;
    }

    /** The channel a pick waits for (ordinal), or -1. */
    public int pending() {
        return pending;
    }

    /** A state from the server, with the picked channel still open in it: see {@link #accept(int, boolean, long)}. */
    public boolean accept(int serverChannel, long now) {
        return accept(serverChannel, true, now);
    }

    /**
     * A state from the server naming {@code serverChannel}: whether its channel applies now. It does with no pick
     * waiting, when it is the picked channel and no earlier pick's answer can still come (the answer: the guard ends),
     * when the state closes the picked channel ({@code pickedStillOpen} false: the server refused or fell back, so
     * the guard ends), and once the hold ran out. Otherwise it is held as the latest, for {@link #expire} (the picked
     * channel too, while an earlier answer may follow: harmless, it is the one shown).
     */
    public boolean accept(int serverChannel, boolean pickedStillOpen, long now) {
        if (pending < 0) {
            return true;
        }
        if (outstanding > 0) {
            outstanding--;
        }
        if (serverChannel == pending && outstanding == 0 || !pickedStillOpen || now - since >= HOLD_MS) {
            reset();
            return true;
        }
        held = serverChannel;
        return false;
    }

    /** A held channel to apply now that the hold ran out (ordinal), or -1; the guard ends either way once it has. */
    public int expire(long now) {
        if (pending < 0 || now - since < HOLD_MS) {
            return -1;
        }
        int channel = held;
        reset();
        return channel;
    }

    /** Nothing waits any more (a new server, or the answer came). */
    public void reset() {
        pending = -1;
        held = -1;
        outstanding = 0;
    }
}
