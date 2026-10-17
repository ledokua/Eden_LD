package net.ledok.eden_ld.client.chat.model;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WrapCacheTest {

    private final List<Integer> wraps = new ArrayList<>();

    private String wrap(int width) {
        wraps.add(width);
        return "w" + width;
    }

    @Test
    void twoWidthsStayWrapped() {
        WrapCache<String> cache = new WrapCache<>();
        assertEquals("w100", cache.get(100, 0, this::wrap));
        assertEquals("w60", cache.get(60, 0, this::wrap));
        assertEquals("w100", cache.get(100, 0, this::wrap));
        assertEquals("w60", cache.get(60, 0, this::wrap));
        assertEquals(List.of(100, 60), wraps);
    }

    @Test
    void aThirdWidthDropsTheOlderOne() {
        WrapCache<String> cache = new WrapCache<>();
        cache.get(100, 0, this::wrap);
        cache.get(60, 0, this::wrap);
        cache.get(100, 0, this::wrap);
        cache.get(80, 0, this::wrap);
        cache.get(100, 0, this::wrap);
        cache.get(60, 0, this::wrap);
        assertEquals(List.of(100, 60, 80, 60), wraps);
    }

    @Test
    void aNewFontOrClearWrapsAgain() {
        WrapCache<String> cache = new WrapCache<>();
        cache.get(100, 0, this::wrap);
        cache.get(100, 1, this::wrap);
        cache.clear();
        cache.get(100, 1, this::wrap);
        assertEquals(List.of(100, 100, 100), wraps);
    }

    @Test
    void anyWidthIsAWidth() {
        WrapCache<String> cache = new WrapCache<>();
        assertEquals("w-1", cache.get(-1, 0, this::wrap));
        assertEquals("w0", cache.get(0, 0, this::wrap));
        assertEquals(List.of(-1, 0), wraps);
    }
}
