package net.ledok.eden_ld.hub.activity;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The run history's math (ActivityHistory): medians, the kept rings, win rates and the best clear. */
class RunStatsTest {

    @Test
    void medianOfOddAndEvenCounts() {
        assertEquals(-1, RunStats.median(List.of()));
        assertEquals(5, RunStats.median(List.of(5L)));
        assertEquals(7, RunStats.median(List.of(9L, 3L, 7L)));
        // Even: the mean of the two middle ones, rounded down.
        assertEquals(5, RunStats.median(List.of(8L, 2L, 4L, 6L)));
        assertEquals(4, RunStats.median(List.of(3L, 6L)));
        assertEquals(Long.MAX_VALUE - 1, RunStats.median(List.of(Long.MAX_VALUE, Long.MAX_VALUE - 2)), "no overflow");
    }

    @Test
    void ringKeepsTheNewest() {
        Deque<Integer> ring = new ArrayDeque<>();
        for (int i = 1; i <= 60; i++) {
            RunStats.push(ring, i, 50);
        }
        assertEquals(50, ring.size());
        assertEquals(11, ring.peekFirst());
        assertEquals(60, ring.peekLast());
        RunStats.push(ring, 61, 3);
        assertEquals(List.of(59, 60, 61), new ArrayList<>(ring));
    }

    @Test
    void clearTimesAreCappedForTheMedian() {
        RunStats stats = new RunStats();
        for (int i = 1; i <= RunStats.CLEARS + 50; i++) {
            stats.record(true, i * 1000L, 0);
        }
        assertEquals(RunStats.CLEARS, stats.clears().size());
        assertEquals(51_000L, stats.clears().get(0), "the oldest 50 dropped");
        // 51..250 s: the middle two are 150 and 151.
        assertEquals(150_500L, stats.medianMillis());
        assertEquals(1000L, stats.bestMillis(), "the best survives the ring");
        assertEquals(RunStats.CLEARS + 50, stats.runs());
    }

    @Test
    void winRateRoundsAndIgnoresLossesForTimes() {
        assertEquals(0, RunStats.winRate(0, 0));
        assertEquals(62, RunStats.winRate(34, 21));
        assertEquals(67, RunStats.winRate(3, 2));
        assertEquals(100, RunStats.winRate(2, 5), "wins never exceed runs");

        RunStats stats = new RunStats();
        stats.record(false, 3000, 4);
        stats.record(true, 0, 7);
        stats.record(true, 9000, 2);
        assertEquals(3, stats.runs());
        assertEquals(2, stats.wins());
        assertEquals(67, stats.winRate());
        // A loss and an untimed win add no clear time.
        assertEquals(List.of(9000L), stats.clears());
        assertEquals(9000L, stats.bestMillis());
        assertEquals(7, stats.bestWave());
    }

    @Test
    void restoredStatsAreClampedAndTrimmed() {
        List<Long> clears = new ArrayList<>();
        for (long i = 0; i < RunStats.CLEARS + 10; i++) {
            clears.add(i);
        }
        RunStats stats = new RunStats(5, 9, 0, -3, clears);
        assertEquals(5, stats.wins(), "wins clamped to runs");
        assertEquals(-1, stats.bestMillis(), "0 means never timed");
        assertEquals(0, stats.bestWave());
        assertEquals(RunStats.CLEARS, stats.clears().size());
        assertEquals(10L, stats.clears().get(0));
    }
}
