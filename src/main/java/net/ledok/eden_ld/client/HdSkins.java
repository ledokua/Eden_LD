package net.ledok.eden_ld.client;

/**
 * HD player skins: square 128×128 and 256×256 skins are kept instead of discarded (vanilla 1.21.1 only takes
 * 64×64 and legacy 64×32). Model UVs are proportional, so the bigger texture just renders sharper; the
 * transparency fix vanilla applies to 64×64 skins (base layer opaque, hat and second layer untouched) is
 * applied with every coordinate scaled by width / 64. Larger skins are still discarded, so a huge image can't
 * stall a client. Plain code: {@code HttpTextureHdSkinMixin} applies it, the rectangles are unit-tested.
 */
public final class HdSkins {

    /** The largest skin side kept. */
    public static final int MAX_SIZE = 256;

    /** Vanilla's base-layer areas on a 64×64 skin (x0, y0, x1, y1; exclusive ends): head, body/arms/legs row, lower limbs. */
    private static final int[][] OPAQUE_64 = {{0, 0, 32, 16}, {0, 16, 64, 32}, {16, 48, 48, 64}};

    private HdSkins() {
    }

    /** A square HD skin this mod keeps: 128×128 or 256×256. 64×64 and 64×32 stay with vanilla. */
    public static boolean isHd(int width, int height) {
        return width == height && (width == 128 || width == MAX_SIZE);
    }

    /** The areas made opaque on a skin this wide: vanilla's 64×64 ones scaled by width / 64. */
    public static int[][] opaqueAreas(int width) {
        int scale = width / 64;
        int[][] areas = new int[OPAQUE_64.length][];
        for (int i = 0; i < OPAQUE_64.length; i++) {
            int[] area = OPAQUE_64[i];
            areas[i] = new int[]{area[0] * scale, area[1] * scale, area[2] * scale, area[3] * scale};
        }
        return areas;
    }
}
