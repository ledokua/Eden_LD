package net.ledok.eden_ld.client.chat.panel;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** The closed chat's strips: gap, geometry and alpha ({@link HudStrips}). */
class HudStripTest {

    private static final float EPSILON = 1e-5f;

    @Test
    void theGapNeverDropsUnderOnePixel() {
        assertEquals(2f, HudStrips.gap(1, 0.5), EPSILON, "GUI 1, chat scale 0.5: half a pixel per unit");
        assertEquals(1.2f, HudStrips.gap(3, 1), EPSILON);
        assertEquals(1.2f, HudStrips.gap(1, 1), EPSILON, "a pixel is under 1.2 units");
        assertEquals(4f, HudStrips.gap(1, 0.25), EPSILON);
        assertEquals(1.2f, HudStrips.gap(2, 0), EPSILON, "chat scale 0 (nothing drawn): no division by zero");
    }

    @Test
    void aStripCoversItsMessagesRowsDockedLeftToThePlatesRightEdge() {
        float[] strip = new float[4];
        // Rows 0 and 1 (a message wrapped onto two lines), base 200, 9 units a line, box 320 wide.
        HudStrips.rect(strip, 200, 0, 1, 9, 1.2f, 320);
        assertArrayEquals(new float[]{-4f, 200 - 18 + 0.6f, 328f, 200 - 0.6f}, strip, EPSILON);
        // The open history plate ends at (boxWidth + 12) × scale from the view's left: boxWidth + 8 after the 4-unit shift.
        assertEquals(320 + 12 - 4, strip[2], EPSILON);
    }

    @Test
    void neighborsLeaveExactlyTheGap() {
        float[] newer = new float[4];
        float[] older = new float[4];
        HudStrips.rect(newer, 200, 0, 0, 9, 2f, 320);
        HudStrips.rect(older, 200, 1, 3, 9, 2f, 320);
        assertEquals(2f, newer[1] - older[3], EPSILON);
        assertEquals(9 - 2f, newer[3] - newer[1], EPSILON);
        assertEquals(3 * 9 - 2f, older[3] - older[1], EPSILON);
    }

    @Test
    void theMarksSitOnTheOpenLooksBandNotOnTheStrip() {
        float[] band = new float[4];
        HudStrips.band(band, 200, 2, 3, 9, 320);
        // drawEntryChrome's rect in 4a: x -3, width + 10, the rows' full height.
        assertArrayEquals(new float[]{-3f, 200 - 36f, 327f, 200 - 18f}, band, EPSILON);
        float[] strip = new float[4];
        HudStrips.rect(strip, 200, 2, 3, 9, 1.2f, 320);
        assertEquals(1f, band[0] - strip[0], EPSILON);
        assertEquals(1f, strip[2] - band[2], EPSILON);
    }

    @Test
    void theQueueLineSitsUnderTheNewestLine() {
        float[] queue = new float[4];
        HudStrips.queue(queue, 1.2f, 320);
        assertArrayEquals(new float[]{-4f, 0.6f, 328f, 9 - 0.6f}, queue, EPSILON);
    }

    @Test
    void alphaFollowsTextBackgroundOpacityFadeAndHandover() {
        assertEquals(150f / 255f, HudStrips.alpha(0.5, 1, 1), EPSILON, "vanilla's default opacity: alpha 150");
        assertEquals(1f, HudStrips.alpha(1.0, 1, 1), EPSILON, "raising the option still darkens it");
        assertEquals(1f, HudStrips.alpha(0.9, 1, 1), EPSILON, "capped at 255");
        assertEquals(0f, HudStrips.alpha(0, 1, 1), EPSILON);
        assertEquals(150f / 255f * 0.25f, HudStrips.alpha(0.5, 0.5, 0.5f), EPSILON, "the product");
        assertEquals(90f / 255f * 0.8f * 0.5f, HudStrips.alpha(0.3, 0.8, 0.5f), EPSILON, "300 × 0.3 = 90");
    }

    @Test
    void tooFaintIsNotDrawn() {
        assertEquals(0f, HudStrips.alpha(0.5, 0.005, 1), "under 1 of 255");
        assertEquals(0f, HudStrips.alpha(0.5, 1, 0.003f));
        assertEquals(0f, HudStrips.alpha(0.5, 1, 0));
        float justOver = HudStrips.alpha(1.0, 1, HudStrips.MIN_ALPHA);
        assertEquals(HudStrips.MIN_ALPHA, justOver, EPSILON, "the threshold itself still draws");
    }
}
