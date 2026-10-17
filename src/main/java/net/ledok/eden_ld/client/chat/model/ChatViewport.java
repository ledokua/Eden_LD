package net.ledok.eden_ld.client.chat.model;

import java.util.Locale;

/**
 * Where a chat view is scrolled to: lines up from the newest ({@code 0} = at the bottom, following new lines), how
 * many lines came in since it was scrolled up (the scroll bar's red, later the "↓ New: N" pill), and the search
 * filter (phase 7; empty = none).
 */
public final class ChatViewport {

    private int scroll;
    private int newCount;
    private String search = "";

    public int scroll() {
        return scroll;
    }

    public int newCount() {
        return newCount;
    }

    /** Lowercase ({@link Locale#ROOT}), as {@code ChatEntry.plain()}; empty = no search. */
    public String search() {
        return search;
    }

    /** A new query starts at the newest line. */
    public void search(String query) {
        String lower = query.toLowerCase(Locale.ROOT);
        if (!lower.equals(search)) {
            search = lower;
            reset();
        }
    }

    /** The mouse wheel: {@code lines} up (down when negative), at most {@code most}; at the bottom nothing is new. */
    public void scrollBy(int lines, int most) {
        scroll += lines;
        if (scroll > most) {
            scroll = most;
        }
        if (scroll <= 0) {
            scroll = 0;
            newCount = 0;
        }
    }

    /** Back to the newest line. */
    public void reset() {
        scroll = 0;
        newCount = 0;
    }

    /** The view's lines changed: the scroll stays within them. */
    public void clamp(int rows, int page) {
        scroll = Math.max(0, Math.min(scroll, rows - page));
    }

    /**
     * A shown line of {@code lines} rows came in: while scrolled up the view holds still (it moves up by the new rows),
     * and the line counts as new. At the bottom it simply shows.
     */
    public void hold(int lines) {
        if (scroll > 0) {
            scroll += lines;
            newCount++;
        }
    }

    /** The row at {@code index} (from the newest) in the middle of a {@code page}-row view of {@code rows} rows. */
    public void center(int index, int page, int rows) {
        scroll = Math.max(0, Math.min(index - page / 2, rows - page));
        newCount = 0;
    }
}
