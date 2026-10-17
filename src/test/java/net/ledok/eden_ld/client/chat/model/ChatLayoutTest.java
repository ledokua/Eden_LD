package net.ledok.eden_ld.client.chat.model;

import net.ledok.eden_ld.client.chat.model.ChatLayout.Anchor;
import net.ledok.eden_ld.client.chat.model.ChatLayout.Rect;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ChatLayoutTest {

    @Test
    void anchoredCornerSitsAtItsFractions() {
        ChatLayout layout = new ChatLayout(Anchor.BOTTOM_LEFT, 0.0F, 1.0F, 320, 180, 100);
        assertEquals(new Rect(0, 360 - 180, 320, 180), layout.place(640, 360));
        ChatLayout right = new ChatLayout(Anchor.TOP_RIGHT, 1.0F, 0.0F, 200, 100, 100);
        assertEquals(new Rect(640 - 200, 0, 200, 100), right.place(640, 360));
    }

    @Test
    void zoomScalesTheSizeWithinItsRange() {
        ChatLayout layout = new ChatLayout(Anchor.TOP_LEFT, 0.0F, 0.0F, 200, 100, 150);
        assertEquals(new Rect(0, 0, 300, 150), layout.place(640, 360));
        ChatLayout tooSmall = new ChatLayout(Anchor.TOP_LEFT, 0.0F, 0.0F, 200, 100, 10);
        assertEquals(new Rect(0, 0, 100, 50), tooSmall.place(640, 360));
    }

    @Test
    void aSmallerScreenKeepsTheWindowWholeOnIt() {
        ChatLayout layout = new ChatLayout(Anchor.BOTTOM_RIGHT, 0.9F, 0.9F, 300, 200, 100);
        Rect rect = layout.place(320, 180);
        assertEquals(300, rect.width());
        assertEquals(180, rect.height());
        // Its corner would push it off the left and top edges: it's moved back on.
        assertEquals(0, rect.x());
        assertEquals(0, rect.y());
        Rect tiny = layout.place(50, 30);
        assertEquals(new Rect(0, 0, 50, 30), tiny);
    }

    @Test
    void neverSmallerThanTheMinimumWhileTheScreenAllows() {
        ChatLayout layout = new ChatLayout(Anchor.TOP_LEFT, 0.5F, 0.5F, 10, 10, 100);
        Rect rect = layout.place(640, 360);
        assertEquals(ChatLayout.MIN_WIDTH, rect.width());
        assertEquals(ChatLayout.MIN_HEIGHT, rect.height());
    }

    @Test
    void theOpenChatGivesUpLinesOnlyWhereTheTabStripNeedsThem() {
        // 426×240 (GUI 3 at 1280×720): the newest line's bottom at 200, 20 lines a page, 9 a line.
        double oneRow = 18 + 8;
        double twoRows = 37 + 8;
        assertEquals(19, ChatLayout.capLines(20, 200, oneRow, 9, 1.0));
        assertEquals(17, ChatLayout.capLines(20, 200, twoRows, 9, 1.0));
        // 455×256: room for all 20.
        assertEquals(20, ChatLayout.capLines(20, 216, oneRow, 9, 1.0));
        // Chat scale 0.5: half-height lines, the page is the limit.
        assertEquals(20, ChatLayout.capLines(20, 200, oneRow, 9, 0.5));
        // Never fewer than one line, however short the screen.
        assertEquals(1, ChatLayout.capLines(20, 20, twoRows, 9, 1.0));
        assertEquals(1, ChatLayout.capLines(20, -5, oneRow, 9, 1.0));
    }

    @Test
    void aDroppedWindowAnchorsToTheNearestCornerAndComesBackInPlace() {
        Rect dropped = new Rect(400, 250, 200, 100);
        ChatLayout layout = ChatLayout.of(dropped, 125, 640, 360);
        assertEquals(Anchor.BOTTOM_RIGHT, layout.anchor());
        assertEquals(160, layout.width());
        assertEquals(80, layout.height());
        assertEquals(dropped, layout.place(640, 360));
        assertEquals(Anchor.TOP_LEFT, ChatLayout.of(new Rect(10, 10, 100, 50), 100, 640, 360).anchor());
    }
}
