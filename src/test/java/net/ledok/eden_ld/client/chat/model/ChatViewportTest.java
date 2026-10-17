package net.ledok.eden_ld.client.chat.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ChatViewportTest {

    @Test
    void scrollingStaysWithinTheLinesAndTheBottomClearsNew() {
        ChatViewport viewport = new ChatViewport();
        viewport.scrollBy(5, 20);
        assertEquals(5, viewport.scroll());
        viewport.scrollBy(100, 20);
        assertEquals(20, viewport.scroll());
        viewport.hold(2);
        assertEquals(22, viewport.scroll());
        assertEquals(1, viewport.newCount());
        viewport.scrollBy(-100, 22);
        assertEquals(0, viewport.scroll());
        assertEquals(0, viewport.newCount());
    }

    @Test
    void newLinesAtTheBottomJustShow() {
        ChatViewport viewport = new ChatViewport();
        viewport.hold(3);
        assertEquals(0, viewport.scroll());
        assertEquals(0, viewport.newCount());
    }

    @Test
    void fewerLinesClampTheScroll() {
        ChatViewport viewport = new ChatViewport();
        viewport.scrollBy(30, 40);
        viewport.clamp(25, 10);
        assertEquals(15, viewport.scroll());
        viewport.clamp(5, 10);
        assertEquals(0, viewport.scroll());
    }

    @Test
    void centeringPutsTheLineMidPageWithinBounds() {
        ChatViewport viewport = new ChatViewport();
        viewport.hold(1);
        viewport.center(30, 10, 100);
        assertEquals(25, viewport.scroll());
        viewport.center(98, 10, 100);
        assertEquals(90, viewport.scroll());
        viewport.center(2, 10, 100);
        assertEquals(0, viewport.scroll());
        assertEquals(0, viewport.newCount());
    }

    @Test
    void aNewSearchStartsAtTheNewestLine() {
        ChatViewport viewport = new ChatViewport();
        viewport.scrollBy(8, 20);
        viewport.search("Арена");
        assertEquals("арена", viewport.search());
        assertEquals(0, viewport.scroll());
        viewport.scrollBy(4, 20);
        viewport.search("АРЕНА");
        assertEquals(4, viewport.scroll());
    }
}
