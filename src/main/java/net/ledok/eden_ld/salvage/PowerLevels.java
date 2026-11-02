package net.ledok.eden_ld.salvage;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

/**
 * Dungeon Difficulty's power level on loot gear: the int under {@code dd.rsf} in the item's
 * {@code minecraft:custom_data} (what its "Power Level: N" tooltip shows; DD's
 * {@code ItemScaling.getScaleFactor}). Crafted and vanilla items have no key. Plain code, so the
 * matching and the scrap tiers are unit-tested.
 */
public final class PowerLevels {

    /** The custom_data key Dungeon Difficulty stores the level under. */
    public static final String KEY = "dd.rsf";
    /** {@code max} of an open-ended range ("51+"). */
    public static final int OPEN = Integer.MAX_VALUE;
    /** Scrap tiers I–VI. */
    public static final int TIERS = 6;
    /** Levels per scrap tier: 1–10 is tier I, …, 51+ is tier VI. */
    public static final int BAND = 10;

    private PowerLevels() {
    }

    /** The power level in a custom_data tag, or -1 when it has none (crafted gear). */
    public static int of(CompoundTag customData) {
        if (customData == null || !customData.contains(KEY, Tag.TAG_ANY_NUMERIC)) {
            return -1;
        }
        return customData.getInt(KEY);
    }

    /** {@code level} in [min, max]; {@code max} may be {@link #OPEN}. A missing level (-1) never matches. */
    public static boolean inRange(int level, int min, int max) {
        return level >= 0 && level >= min && level <= max;
    }

    /** The scrap tier (1–6) for a power level, 0 below level 1. */
    public static int tier(int level) {
        if (level < 1) {
            return 0;
        }
        return Math.min(TIERS, (level - 1) / BAND + 1);
    }

    /** The lowest level of a tier's band (tier 2 → 11). */
    public static int bandMin(int tier) {
        return (tier - 1) * BAND + 1;
    }

    /** The highest level of a tier's band (tier 2 → 20); the last tier is open-ended ({@link #OPEN}). */
    public static int bandMax(int tier) {
        return tier >= TIERS ? OPEN : tier * BAND;
    }

    /** "11–20", or "51+" when {@code max} is {@link #OPEN}. */
    public static String rangeText(int min, int max) {
        return max == OPEN ? min + "+" : min + "–" + max;
    }
}
