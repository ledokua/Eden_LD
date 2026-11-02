package net.ledok.eden_ld.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Which skins are kept as HD, and vanilla's 64×64 opaque areas scaled by width / 64. */
class HdSkinsTest {

    @Test
    void squareHdSkinsAreKept() {
        assertTrue(HdSkins.isHd(128, 128));
        assertTrue(HdSkins.isHd(256, 256));
    }

    @Test
    void vanillaSizesStayWithVanilla() {
        assertFalse(HdSkins.isHd(64, 64));
        assertFalse(HdSkins.isHd(64, 32));
    }

    @Test
    void otherSizesAreNotKept() {
        assertFalse(HdSkins.isHd(128, 64));
        assertFalse(HdSkins.isHd(512, 512));
        assertFalse(HdSkins.isHd(1024, 1024));
        assertFalse(HdSkins.isHd(192, 192));
        assertFalse(HdSkins.isHd(100, 100));
    }

    @Test
    void at64TheAreasAreVanillas() {
        assertArrayEquals(new int[][]{{0, 0, 32, 16}, {0, 16, 64, 32}, {16, 48, 48, 64}}, HdSkins.opaqueAreas(64));
    }

    @Test
    void at128EveryCoordinateDoubles() {
        assertArrayEquals(new int[][]{{0, 0, 64, 32}, {0, 32, 128, 64}, {32, 96, 96, 128}}, HdSkins.opaqueAreas(128));
    }

    @Test
    void at256EveryCoordinateQuadruples() {
        assertArrayEquals(new int[][]{{0, 0, 128, 64}, {0, 64, 256, 128}, {64, 192, 192, 256}}, HdSkins.opaqueAreas(256));
    }

    @Test
    void theHatAndSecondLayerStayUntouched() {
        // Hat (32..64, 0..16 at 64 px) and the lower second-layer areas must not be inside any opaque area.
        for (int size : new int[]{128, 256}) {
            int s = size / 64;
            for (int[] area : HdSkins.opaqueAreas(size)) {
                assertFalse(overlaps(area, 32 * s, 0, 64 * s, 16 * s), "hat at " + size);
                assertFalse(overlaps(area, 0, 32 * s, 64 * s, 48 * s), "body/arm overlay at " + size);
                assertFalse(overlaps(area, 0, 48 * s, 16 * s, 64 * s), "left leg overlay at " + size);
                assertFalse(overlaps(area, 48 * s, 48 * s, 64 * s, 64 * s), "left arm overlay at " + size);
            }
        }
    }

    private static boolean overlaps(int[] a, int x0, int y0, int x1, int y1) {
        return a[0] < x1 && x0 < a[2] && a[1] < y1 && y0 < a[3];
    }
}
