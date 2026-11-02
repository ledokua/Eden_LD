package net.ledok.eden_ld.client;

import org.jetbrains.annotations.Nullable;

/**
 * HD player skins: every size the Minestar website accepts is kept instead of discarded (vanilla 1.21.1 only takes
 * 64×64 and legacy 64×32). Square skins of 64·k and the 2:1 legacy layout 64·k × 32·k, k = 2, 4, 8 or 16 (128…1024).
 * Anything wider than {@link #MAX_SIZE} is shrunk to it on load (owner, 2026-11-02: video memory; no visible gain at
 * normal distance), a legacy skin is turned square the way vanilla turns 64×32 into 64×64 (the right arm and leg
 * mirrored into the left ones), and vanilla's transparency fixes run — every coordinate scaled by width / 64. Model
 * UVs are proportional, so the bigger texture just renders sharper. Plain code: {@code HttpTextureHdSkinMixin} applies
 * it, the rectangles are unit-tested.
 */
public final class HdSkins {

    /** Skins wider than this are shrunk to this width when they load. */
    public static final int MAX_SIZE = 256;
    /** The widest skin accepted at all (before shrinking). */
    public static final int MAX_ACCEPTED = 1024;

    /** Vanilla's base-layer areas on a 64×64 skin (x0, y0, x1, y1; exclusive ends): head, body/arms/legs row, lower limbs. */
    private static final int[][] OPAQUE_64 = {{0, 0, 32, 16}, {0, 16, 64, 32}, {16, 48, 48, 64}};
    /** The hat area vanilla clears on a legacy skin when it's fully opaque ("Notch transparency hack"). */
    private static final int[] HAT_64 = {32, 0, 64, 32};
    /**
     * Vanilla's 64×32 → 64×64 copies (x, y, dx, dy, width, height; mirrored horizontally): the right leg and arm into
     * the left leg's and left arm's places.
     */
    private static final int[][] LEGACY_COPIES_64 = {
            {4, 16, 16, 32, 4, 4}, {8, 16, 16, 32, 4, 4}, {0, 20, 24, 32, 4, 12}, {4, 20, 16, 32, 4, 12},
            {8, 20, 8, 32, 4, 12}, {12, 20, 16, 32, 4, 12}, {44, 16, -8, 32, 4, 4}, {48, 16, -8, 32, 4, 4},
            {40, 20, 0, 32, 4, 12}, {44, 20, -8, 32, 4, 12}, {48, 20, -16, 32, 4, 12}, {52, 20, -8, 32, 4, 12}};

    /**
     * What to do with a downloaded skin: {@code width} / {@code height} after shrinking, and whether it's the legacy
     * 2:1 layout (made square afterwards).
     */
    public record Plan(int width, int height, boolean legacy) {
        /** The square side the finished skin has. */
        public int size() {
            return width;
        }
    }

    private HdSkins() {
    }

    /**
     * The plan for a skin this size, or null for vanilla to handle it (64×64, 64×32, and everything it discards: other
     * shapes, wider than {@link #MAX_ACCEPTED}, not 64 times a power of two).
     */
    @Nullable
    public static Plan plan(int width, int height) {
        if (width < 128 || width > MAX_ACCEPTED || width % 64 != 0 || Integer.bitCount(width / 64) != 1) {
            return null;
        }
        boolean legacy = height * 2 == width;
        if (height != width && !legacy) {
            return null;
        }
        int target = Math.min(width, MAX_SIZE);
        return new Plan(target, legacy ? target / 2 : target, legacy);
    }

    /** The areas made opaque on a square skin this wide: vanilla's 64×64 ones scaled by width / 64. */
    public static int[][] opaqueAreas(int width) {
        return scaled(OPAQUE_64, width / 64);
    }

    /** The hat area of a legacy skin this wide (cleared when it has no transparency at all, as vanilla does). */
    public static int[] hatArea(int width) {
        return scaled(new int[][]{HAT_64}, width / 64)[0];
    }

    /** The mirrored copies that make a legacy skin this wide square: vanilla's scaled by width / 64. */
    public static int[][] legacyCopies(int width) {
        return scaled(LEGACY_COPIES_64, width / 64);
    }

    private static int[][] scaled(int[][] rects, int scale) {
        int[][] out = new int[rects.length][];
        for (int i = 0; i < rects.length; i++) {
            out[i] = new int[rects[i].length];
            for (int j = 0; j < rects[i].length; j++) {
                out[i][j] = rects[i][j] * scale;
            }
        }
        return out;
    }
}
