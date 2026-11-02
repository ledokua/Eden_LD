package net.ledok.eden_ld.salvage;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Dungeon Difficulty's dd.rsf read from custom_data, the [min, max] match, and the scrap tier bands. */
class PowerLevelsTest {

    private static CompoundTag levelled(int level) {
        CompoundTag tag = new CompoundTag();
        tag.putInt(PowerLevels.KEY, level);
        return tag;
    }

    @Test
    void theLevelIsReadFromDdRsf() {
        assertEquals(14, PowerLevels.of(levelled(14)));
        assertEquals(63, PowerLevels.of(levelled(63)));
        CompoundTag other = levelled(7);
        other.putString("something", "else");
        assertEquals(7, PowerLevels.of(other));
    }

    @Test
    void gearWithoutALevelHasNone() {
        assertEquals(-1, PowerLevels.of(null));
        assertEquals(-1, PowerLevels.of(new CompoundTag()));
        CompoundTag text = new CompoundTag();
        text.putString(PowerLevels.KEY, "14");
        assertEquals(-1, PowerLevels.of(text));
    }

    @Test
    void anyNumberTypeCounts() {
        CompoundTag tag = new CompoundTag();
        tag.putShort(PowerLevels.KEY, (short) 21);
        assertEquals(21, PowerLevels.of(tag));
    }

    @Test
    void rangesIncludeBothEnds() {
        assertTrue(PowerLevels.inRange(11, 11, 20));
        assertTrue(PowerLevels.inRange(20, 11, 20));
        assertTrue(PowerLevels.inRange(14, 11, 20));
        assertFalse(PowerLevels.inRange(10, 11, 20));
        assertFalse(PowerLevels.inRange(21, 11, 20));
    }

    @Test
    void openRangesHaveNoTop() {
        assertTrue(PowerLevels.inRange(51, 51, PowerLevels.OPEN));
        assertTrue(PowerLevels.inRange(63, 51, PowerLevels.OPEN));
        assertTrue(PowerLevels.inRange(10_000, 51, PowerLevels.OPEN));
        assertFalse(PowerLevels.inRange(50, 51, PowerLevels.OPEN));
    }

    @Test
    void noLevelNeverMatches() {
        assertFalse(PowerLevels.inRange(-1, 0, PowerLevels.OPEN));
        assertFalse(PowerLevels.inRange(PowerLevels.of(new CompoundTag()), 1, 10));
    }

    @Test
    void levelsFallIntoTheirTier() {
        assertEquals(0, PowerLevels.tier(0));
        assertEquals(1, PowerLevels.tier(1));
        assertEquals(1, PowerLevels.tier(10));
        assertEquals(2, PowerLevels.tier(11));
        assertEquals(2, PowerLevels.tier(14));
        assertEquals(3, PowerLevels.tier(30));
        assertEquals(5, PowerLevels.tier(50));
        assertEquals(6, PowerLevels.tier(51));
        assertEquals(6, PowerLevels.tier(63));
        assertEquals(6, PowerLevels.tier(70));
        assertEquals(6, PowerLevels.tier(500));
    }

    @Test
    void bandsMatchTheTiers() {
        assertEquals(1, PowerLevels.bandMin(1));
        assertEquals(10, PowerLevels.bandMax(1));
        assertEquals(11, PowerLevels.bandMin(2));
        assertEquals(20, PowerLevels.bandMax(2));
        assertEquals(51, PowerLevels.bandMin(6));
        assertEquals(PowerLevels.OPEN, PowerLevels.bandMax(6));
        for (int level = 1; level <= 100; level++) {
            int tier = PowerLevels.tier(level);
            assertTrue(PowerLevels.inRange(level, PowerLevels.bandMin(tier), PowerLevels.bandMax(tier)),
                    "level " + level + " in tier " + tier);
        }
    }

    @Test
    void rangeTexts() {
        assertEquals("11–20", PowerLevels.rangeText(11, 20));
        assertEquals("51+", PowerLevels.rangeText(51, PowerLevels.OPEN));
    }
}
