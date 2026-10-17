package net.ledok.eden_ld.client.chat.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The shared picker's geometry by column count: the hub's 8, the chat's 10 ({@link EmojiPicker}). */
class EmojiPickerLayoutTest {

    private static final float EPSILON = 1e-5f;
    /** Favorites, Stickers, Server, then the eight Discord categories: the tabs without server groups. */
    private static final int TABS = 11;

    @Test
    void theWidthIsTheColumnsPlusTheScrollbarLaneAndPadding() {
        assertEquals(108f, EmojiPicker.width(8), EPSILON, "the hub's side column");
        assertEquals(132f, EmojiPicker.width(10), EPSILON, "the chat");
        assertEquals(120f, EmojiPicker.width(9), EPSILON);
        for (int columns = 1; columns <= 12; columns++) {
            // The emoji rows fill VirtualList's row width exactly (inner width minus the bar 5 + 1).
            float rowWidth = EmojiPicker.width(columns) - 2 * EmojiPicker.PADDING - 5 - 1;
            assertEquals(columns * EmojiPicker.CELL, rowWidth, EPSILON);
        }
    }

    @Test
    void tabsWrapOverTheWholeInnerWidth() {
        assertEquals(8, EmojiPicker.tabColumns(8));
        assertEquals(10, EmojiPicker.tabColumns(10));
        assertEquals(9, EmojiPicker.tabColumns(9), "the lane's 6 units don't make a tenth tab cell");
        assertEquals(1, EmojiPicker.tabColumns(0), "never zero columns");
    }

    @Test
    void stickersAreThreeInTheHubAndFourInTheChat() {
        assertEquals(3, EmojiPicker.stickerColumns(8));
        assertEquals(32f, EmojiPicker.stickerCell(8), EPSILON, "the hub's sticker cells, as before");
        assertEquals(4, EmojiPicker.stickerColumns(10));
        assertEquals(30f, EmojiPicker.stickerCell(10), EPSILON, "the chat's sticker cells, as before");
        assertEquals(EmojiPicker.stickerColumns(10) * EmojiPicker.stickerCell(10), 10 * EmojiPicker.CELL, EPSILON,
                "a sticker row is as wide as an emoji row");
    }

    @Test
    void preferredHeightIsTabsSearchAndRows() {
        // 3 padding, 2 tab rows of 12, 3 gap, the search's 14, 3 gap, the rows, 3 padding.
        float header = 3 + 2 * 12 + 3 + 14 + 3 + 3;
        assertEquals(header + 12, EmojiPicker.preferredHeight(TABS, 10, 1, 12, 1000), EPSILON);
        assertEquals(header + 12, EmojiPicker.preferredHeight(TABS, 10, 0, 12, 1000), EPSILON,
                "an empty list keeps a row for its empty text");
        assertEquals(header + 25 * 12, EmojiPicker.preferredHeight(TABS, 10, 25, 12, 1000), EPSILON);
        assertEquals(header + 3 * 30, EmojiPicker.preferredHeight(TABS, 10, 3, 30, 1000), EPSILON, "sticker rows");
        assertEquals(header + 12, EmojiPicker.preferredHeight(TABS, 8, 1, 12, 1000), EPSILON,
                "11 tabs are two rows in the hub too");
    }

    @Test
    void moreTabsMakeMoreTabRows() {
        float oneRow = EmojiPicker.preferredHeight(TABS, 10, 1, 12, 1000);
        assertEquals(oneRow, EmojiPicker.preferredHeight(20, 10, 1, 12, 1000), EPSILON, "20 tabs still fit two rows");
        assertEquals(oneRow + 12, EmojiPicker.preferredHeight(21, 10, 1, 12, 1000), EPSILON);
        assertEquals(oneRow + 12, EmojiPicker.preferredHeight(17, 8, 1, 12, 1000), EPSILON,
                "the hub's 8 a row wrap sooner");
        assertEquals(oneRow - 24, EmojiPicker.preferredHeight(0, 10, 1, 12, 1000), EPSILON, "no tabs, no tab rows");
    }

    @Test
    void preferredHeightIsCappedByTheRoomThereIs() {
        assertEquals(120f, EmojiPicker.preferredHeight(TABS, 10, 400, 12, 120), EPSILON);
        assertEquals(EmojiPicker.preferredHeight(TABS, 10, 2, 12, 1000),
                EmojiPicker.preferredHeight(TABS, 10, 2, 12, 74 + 24), EPSILON, "a cap above the content changes nothing");
    }
}
