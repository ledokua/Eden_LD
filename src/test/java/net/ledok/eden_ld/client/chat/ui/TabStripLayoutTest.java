package net.ledok.eden_ld.client.chat.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The tab strip's walk (GM's): chips from the margin, wrapping before the edge, rows top to bottom; its height; what
 * the pointer is on; what over the strip has the pointer instead; and which way the history slides.
 */
class TabStripLayoutTest {

    /** All, Global, Local, Party, Guild, System in vanilla's font (label + 12). */
    private static final float[] EN = {24, 42, 39, 40, 35, 46};
    /** Усі, Загальний, Поруч, Група, Гільдія, Система. */
    private static final float[] UK = {26, 66, 42, 42, 46, 54};
    /** The default chat: plate 332 wide on a 426-wide screen. */
    private static final float EDGE = TabStripLayout.wrapEdge(332, 426);

    private static int layout(float[] widths, float edge, float[] x, int[] row) {
        return TabStripLayout.layout(widths, widths.length, TabStripLayout.MARGIN, edge, x, row);
    }

    @Test
    void englishFitsOneRow() {
        float[] x = new float[6];
        int[] row = new int[6];
        assertEquals(1, layout(EN, EDGE, x, row));
        assertArrayEquals(new float[]{2, 28, 72, 113, 155, 192}, x);
        assertArrayEquals(new int[6], row);
        assertEquals(236, x[5] + EN[5] - TabStripLayout.MARGIN, "the strip's width");
    }

    @Test
    void ukrainianFitsOneRowToo() {
        float[] x = new float[6];
        int[] row = new int[6];
        assertEquals(1, layout(UK, EDGE, x, row));
        assertEquals(286, x[5] + UK[5] - TabStripLayout.MARGIN, "the strip's width");
    }

    @Test
    void aNarrowChatWrapsIntoTwoRows() {
        float[] x = new float[6];
        int[] row = new int[6];
        // Chat Width 40%: a plate 164 wide.
        assertEquals(2, layout(EN, TabStripLayout.wrapEdge(164, 426), x, row));
        assertArrayEquals(new int[]{0, 0, 0, 0, 1, 1}, row);
        assertEquals(2, x[4], "a new row starts at the margin");
        assertEquals(39, x[5]);
    }

    @Test
    void aChipWiderThanARowStaysAloneOnIt() {
        float[] x = new float[3];
        int[] row = new int[3];
        assertEquals(3, layout(new float[]{50, 200, 30}, 100, x, row));
        assertArrayEquals(new int[]{0, 1, 2}, row);
        assertArrayEquals(new float[]{2, 2, 2}, x);
        // First of its row: never moved down (it would be first there too).
        assertEquals(1, layout(new float[]{200}, 100, x, row));
        assertEquals(0, row[0]);
    }

    @Test
    void noChipsNoRows() {
        assertEquals(0, TabStripLayout.layout(new float[0], 0, 2, 330, new float[0], new int[0]));
        assertEquals(0, TabStripLayout.height(0));
    }

    @Test
    void heightIsEighteenARowAndAGapBetween() {
        assertEquals(18, TabStripLayout.height(1));
        assertEquals(37, TabStripLayout.height(2));
        assertEquals(56, TabStripLayout.height(3));
        assertEquals(7, TabStripLayout.rowTop(7, 0));
        assertEquals(26, TabStripLayout.rowTop(7, 1));
    }

    @Test
    void theWrapEdgeNeverPassesTheScreen() {
        assertEquals(330, TabStripLayout.wrapEdge(332, 426));
        // 1280×1024 at GUI 4: a 320-wide screen narrower than the plate.
        assertEquals(318, TabStripLayout.wrapEdge(332, 320));
    }

    @Test
    void hitsLandOnChipsNotGaps() {
        float[] x = new float[6];
        int[] row = new int[6];
        layout(EN, TabStripLayout.wrapEdge(164, 426), x, row);
        float top = 6;
        assertEquals(0, TabStripLayout.hit(x, row, EN, 6, top, 2, 6));
        assertEquals(1, TabStripLayout.hit(x, row, EN, 6, top, 69.9, 23.9));
        assertEquals(-1, TabStripLayout.hit(x, row, EN, 6, top, 27, 10), "the gap between All and Global");
        assertEquals(-1, TabStripLayout.hit(x, row, EN, 6, top, 30, 24.5), "the gap between the rows");
        assertEquals(4, TabStripLayout.hit(x, row, EN, 6, top, 10, 30), "the second row's first chip");
        assertEquals(5, TabStripLayout.hit(x, row, EN, 6, top, 50, 42.9));
        assertEquals(-1, TabStripLayout.hit(x, row, EN, 6, top, 100, 30), "right of the second row's last chip");
        assertEquals(-1, TabStripLayout.hit(x, row, EN, 6, top, 10, 5), "above the strip");
    }

    @Test
    void theSlideFollowsTheStripOrderWhenAHiddenTabComesOrGoes() {
        // Slots: All 0, Global 1, Local 2, Party 3, Guild 4, System 5. Local is hidden: [All, Global, Party, System],
        // and while it's the active tab [All, Global, Local, Party, System] — Party and Local both sit at index 2.
        int local = 2;
        int party = 3;
        assertEquals(-1, TabStripLayout.slideDir(party, local), "Party to the hidden Local: in from the left");
        assertEquals(1, TabStripLayout.slideDir(local, party), "back to Party: in from the right");
        assertEquals(1, TabStripLayout.slideDir(0, 5), "All to System");
        assertEquals(0, TabStripLayout.slideDir(-1, party), "no tab before");
        assertEquals(0, TabStripLayout.slideDir(party, -1), "no tab now");
    }

    @Test
    void thePickerAndTheGifViewerTakeThePointer() {
        int[] picker = {290, 8, 422, 224};
        int[] gif = {258, 46, 422, 222};
        assertTrue(TabStripLayout.blocked(300, 20, picker, null, false, 104));
        assertTrue(TabStripLayout.blocked(260, 50, null, gif, false, 104));
        assertFalse(TabStripLayout.blocked(200, 20, picker, gif, false, 104), "left of both");
        assertFalse(TabStripLayout.blocked(300, 20, null, null, false, 104), "both closed");
    }

    @Test
    void theSuggestionListTakesThePointerWhereItCanReach() {
        int top = TabStripLayout.suggestTop(240);
        assertEquals(104, top, "h-15, 10 rows of 12, its frame");
        assertTrue(TabStripLayout.blocked(10, 104, null, null, true, top));
        assertFalse(TabStripLayout.blocked(10, 103.9, null, null, true, top), "above its highest top");
        assertFalse(TabStripLayout.blocked(10, 150, null, null, false, top), "not while it's hidden");
    }
}
