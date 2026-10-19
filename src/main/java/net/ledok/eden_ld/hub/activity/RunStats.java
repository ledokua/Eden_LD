package net.ledok.eden_ld.hub.activity;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.List;

/**
 * One difficulty's run totals in {@link ActivityHistory}: runs, wins, the best clear time, the best
 * wave (arenas) and the last {@link #CLEARS} clear times for a median. Plain code (no game classes),
 * so it's unit-tested on its own.
 */
public final class RunStats {

    /** Clear times kept for the median. */
    public static final int CLEARS = 200;

    private int runs;
    private int wins;
    private long bestMillis = -1;
    private int bestWave;
    private final Deque<Long> clears = new ArrayDeque<>();

    public RunStats() {
    }

    /** Restored from the world's data ({@code clears} oldest first; trimmed to the newest {@link #CLEARS}). */
    public RunStats(int runs, int wins, long bestMillis, int bestWave, Collection<Long> clears) {
        this.runs = Math.max(0, runs);
        this.wins = Math.max(0, Math.min(this.runs, wins));
        this.bestMillis = bestMillis > 0 ? bestMillis : -1;
        this.bestWave = Math.max(0, bestWave);
        for (long clear : clears) {
            push(this.clears, clear, CLEARS);
        }
    }

    /** A finished run: a win with a time counts toward the best time and the median. */
    public void record(boolean won, long durationMillis, int wave) {
        runs++;
        if (won) {
            wins++;
            if (durationMillis > 0) {
                push(clears, durationMillis, CLEARS);
                if (bestMillis < 0 || durationMillis < bestMillis) {
                    bestMillis = durationMillis;
                }
            }
        }
        bestWave = Math.max(bestWave, wave);
    }

    public int runs() {
        return runs;
    }

    public int wins() {
        return wins;
    }

    /** The fastest clear (ms), -1 when none was timed. */
    public long bestMillis() {
        return bestMillis;
    }

    public int bestWave() {
        return bestWave;
    }

    /** The kept clear times, oldest first. */
    public List<Long> clears() {
        return new ArrayList<>(clears);
    }

    /** Wins in whole percent (0 without runs). */
    public int winRate() {
        return winRate(runs, wins);
    }

    /** The median of the kept clear times (ms), -1 without any. */
    public long medianMillis() {
        return median(clears);
    }

    // ---------------------------------------------------------------- math

    public static int winRate(int runs, int wins) {
        return runs <= 0 ? 0 : (int) Math.round(Math.max(0, Math.min(runs, wins)) * 100.0 / runs);
    }

    /** The middle value (the lower-rounded mean of the two middle ones for an even count); -1 when empty. */
    public static long median(Collection<Long> values) {
        if (values.isEmpty()) {
            return -1;
        }
        List<Long> sorted = new ArrayList<>(values);
        sorted.sort(null);
        int middle = sorted.size() / 2;
        if (sorted.size() % 2 == 1) {
            return sorted.get(middle);
        }
        long low = sorted.get(middle - 1);
        long high = sorted.get(middle);
        return low + (high - low) / 2;
    }

    /** Adds {@code value} last, dropping the oldest while there are more than {@code max}. */
    public static <T> void push(Deque<T> ring, T value, int max) {
        ring.addLast(value);
        while (ring.size() > Math.max(0, max)) {
            ring.removeFirst();
        }
    }
}
