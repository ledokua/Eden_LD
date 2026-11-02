package net.ledok.eden_ld.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Which skins are kept as HD (and shrunk), and vanilla's areas and legacy copies scaled by width / 64. */
class HdSkinsTest {

    @Test
    void everyWebsiteSizeIsKept() {
        // The Minestar website's sizes: squares 128…1024 and the 2:1 legacy layout up to 1024×512.
        assertEquals(new HdSkins.Plan(128, 128, false), HdSkins.plan(128, 128));
        assertEquals(new HdSkins.Plan(256, 256, false), HdSkins.plan(256, 256));
        assertEquals(new HdSkins.Plan(256, 128, true), HdSkins.plan(256, 128));
        assertEquals(new HdSkins.Plan(128, 64, true), HdSkins.plan(128, 64));
    }

    @Test
    void widerThan256IsShrunkTo256() {
        assertEquals(new HdSkins.Plan(256, 256, false), HdSkins.plan(512, 512));
        assertEquals(new HdSkins.Plan(256, 256, false), HdSkins.plan(1024, 1024));
        assertEquals(new HdSkins.Plan(256, 128, true), HdSkins.plan(512, 256));
        assertEquals(new HdSkins.Plan(256, 128, true), HdSkins.plan(1024, 512));
    }

    @Test
    void vanillaSizesStayWithVanilla() {
        assertNull(HdSkins.plan(64, 64));
        assertNull(HdSkins.plan(64, 32));
    }

    @Test
    void otherSizesAreLeftToVanillaToDiscard() {
        assertNull(HdSkins.plan(2048, 2048));
        assertNull(HdSkins.plan(192, 192));
        assertNull(HdSkins.plan(100, 100));
        assertNull(HdSkins.plan(128, 96));
        assertNull(HdSkins.plan(384, 384));
    }

    @Test
    void legacyCopiesScaleVanillas() {
        int[][] at64 = HdSkins.legacyCopies(64);
        int[][] at256 = HdSkins.legacyCopies(256);
        assertArrayEquals(new int[]{4, 16, 16, 32, 4, 4}, at64[0]);
        assertArrayEquals(new int[]{52, 20, -8, 32, 4, 12}, at64[11]);
        for (int i = 0; i < at64.length; i++) {
            for (int j = 0; j < at64[i].length; j++) {
                assertEquals(at64[i][j] * 4, at256[i][j]);
            }
        }
        assertArrayEquals(new int[]{64, 0, 128, 64}, HdSkins.hatArea(128));
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
