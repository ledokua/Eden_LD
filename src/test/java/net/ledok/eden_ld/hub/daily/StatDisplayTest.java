package net.ledok.eden_ld.hub.daily;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Stat quests (DailyObjectives): several stats in one target, and distances shown in blocks. */
class StatDisplayTest {

    private static final ResourceLocation STAT = ResourceLocation.fromNamespaceAndPath("eden_ld", "stat");
    private static final String WALK = "minecraft.custom:minecraft.walk_one_cm";
    private static final String SPRINT = "minecraft.custom:minecraft.sprint_one_cm";

    @Test
    void oneTargetCanCountSeveralStats() {
        assertTrue(DailyObjectives.statMatches(WALK, WALK));
        assertFalse(DailyObjectives.statMatches(WALK, SPRINT), "one stat: only that one");
        assertTrue(DailyObjectives.statMatches(WALK + "|" + SPRINT, SPRINT));
        assertTrue(DailyObjectives.statMatches(WALK + "|" + SPRINT, WALK));
        assertFalse(DailyObjectives.statMatches(WALK + "|" + SPRINT, "minecraft.custom:minecraft.jump"));
    }

    @Test
    void distancesShowInBlocksAndNeverLookDoneEarly() {
        Optional<String> walk = Optional.of(WALK + "|" + SPRINT);
        assertEquals(1500, DailyObjectives.shown(STAT, walk, 150_000, true));
        assertEquals(1499, DailyObjectives.shown(STAT, walk, 149_999, false), "progress rounds down");
        assertEquals(1501, DailyObjectives.shown(STAT, walk, 150_001, true), "goals round up");
        assertEquals(1500, DailyObjectives.shown(STAT, walk, 150_000, false));

        Optional<String> jumps = Optional.of("minecraft.custom:minecraft.jump");
        assertEquals(200, DailyObjectives.shown(STAT, jumps, 200, true), "other stats stay as they are");
        assertEquals(40, DailyObjectives.shown(ResourceLocation.fromNamespaceAndPath("eden_ld", "kill_mobs"),
                Optional.of("minecraft:zombie"), 40, true));
    }
}
