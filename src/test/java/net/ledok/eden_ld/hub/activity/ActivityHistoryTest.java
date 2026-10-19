package net.ledok.eden_ld.hub.activity;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The server-wide run history: forced runs stay out of the stats, the 50-run ring, saving and loading. */
class ActivityHistoryTest {

    private static final ResourceLocation CRYPT = ResourceLocation.fromNamespaceAndPath("arenas_ld", "crypt");
    private static final ResourceLocation PIT = ResourceLocation.fromNamespaceAndPath("arenas_ld", "pit");
    private static final UUID ALEX = new UUID(1, 2);
    private static final UUID STEVE = new UUID(3, 4);

    private static ActivityHistory.Run run(long at, String difficulty, boolean won, boolean forced, long millis) {
        return new ActivityHistory.Run(at, difficulty, won, forced, millis, 0, List.of(ALEX, STEVE));
    }

    @Test
    void forcedRunsAreListedButNotCounted() {
        ActivityHistory history = new ActivityHistory();
        history.record(CRYPT, run(1, "normal", true, false, 60_000));
        history.record(CRYPT, run(2, "normal", false, false, 30_000));
        history.record(CRYPT, run(3, "normal", false, true, 5_000));
        history.record(CRYPT, run(4, "hard", false, true, 5_000));

        assertEquals(4, history.recent(CRYPT, 10).size());
        Map<String, RunStats> stats = history.stats(CRYPT);
        assertEquals(1, stats.size(), "a difficulty only played forced has no stats");
        RunStats normal = stats.get("normal");
        assertEquals(2, normal.runs());
        assertEquals(1, normal.wins());
        assertEquals(50, normal.winRate());
        assertEquals(60_000, normal.bestMillis());
        assertEquals(List.of(60_000L), normal.clears());
    }

    @Test
    void recentIsNewestFirstAndKeepsFifty() {
        ActivityHistory history = new ActivityHistory();
        for (int i = 1; i <= 60; i++) {
            history.record(CRYPT, run(i, "normal", i % 2 == 0, i % 10 == 0, i * 1000L));
        }
        List<ActivityHistory.Run> last = history.recent(CRYPT, 10);
        assertEquals(10, last.size());
        assertEquals(60, last.get(0).epochSeconds());
        assertEquals(51, last.get(9).epochSeconds());

        List<ActivityHistory.Run> all = history.recent(CRYPT, 1000);
        assertEquals(ActivityHistory.RECENT, all.size());
        assertEquals(11, all.get(all.size() - 1).epochSeconds(), "the oldest ten dropped");

        // 60 runs, 6 forced: 54 counted; wins are the even ones that weren't forced (30 - 6).
        RunStats normal = history.stats(CRYPT).get("normal");
        assertEquals(54, normal.runs());
        assertEquals(24, normal.wins());
        assertTrue(history.recent(PIT, 10).isEmpty());
        assertTrue(history.stats(PIT).isEmpty());
    }

    @Test
    void runsWithoutDifficultyShareOneKey() {
        assertEquals(ActivityHistory.NO_DIFFICULTY, ActivityHistory.key(null));
        assertEquals(ActivityHistory.NO_DIFFICULTY, ActivityHistory.key(" "));
        assertEquals("hard", ActivityHistory.key("hard"));

        ActivityHistory history = new ActivityHistory();
        history.record(PIT, new ActivityHistory.Run(1, null, false, false, 90_000, 7, List.of(ALEX)));
        history.record(PIT, new ActivityHistory.Run(2, "", false, false, 40_000, 3, List.of(ALEX)));
        RunStats arena = history.stats(PIT).get(ActivityHistory.NO_DIFFICULTY);
        assertEquals(2, arena.runs());
        assertEquals(7, arena.bestWave());
    }

    @Test
    void saveAndLoadKeepEverything() {
        ActivityHistory history = new ActivityHistory();
        history.record(CRYPT, run(10, "normal", true, false, 42_000));
        history.record(CRYPT, run(11, "normal", false, true, 1_000));
        history.record(PIT, new ActivityHistory.Run(12, null, false, false, 90_000, 7, List.of(STEVE)));

        ActivityHistory loaded = ActivityHistory.load(history.save(new CompoundTag(), null), null);

        List<ActivityHistory.Run> crypt = loaded.recent(CRYPT, 10);
        assertEquals(history.recent(CRYPT, 10), crypt);
        assertTrue(crypt.get(0).forced());
        assertFalse(crypt.get(1).forced());
        assertEquals(List.of(ALEX, STEVE), crypt.get(1).players());
        assertNull(loaded.recent(PIT, 1).get(0).difficulty());

        RunStats normal = loaded.stats(CRYPT).get("normal");
        assertEquals(1, normal.runs());
        assertEquals(1, normal.wins());
        assertEquals(42_000, normal.bestMillis());
        assertEquals(List.of(42_000L), normal.clears());
        assertEquals(7, loaded.stats(PIT).get(ActivityHistory.NO_DIFFICULTY).bestWave());
    }
}
