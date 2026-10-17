package net.ledok.eden_ld.client.chat.ui;

import org.jetbrains.annotations.Nullable;

/**
 * Where the new look's tab strip puts its chips ({@code docs/PLAN-chat-v2.md}, phase 5), as plain code: GM's walk —
 * chips left to right from the margin, a new row when the next one would cross the wrap edge (a chip wider than a row
 * stays alone on its own), rows top to bottom — and what the pointer is on. Adapted from GardenMyth (ProstoMaslo),
 * used with permission: GM's numbers and order ({@code ChatPane.renderTabs}), Eden's code.
 * <p>
 * Everything works on arrays the caller owns: nothing is allocated.
 */
public final class TabStripLayout {

    /** A chip's height, the gap between chips, between rows, a label's padding (each side). */
    public static final float TAB_H = 18;
    public static final float GAP = 2;
    public static final float ROW_GAP = 1;
    public static final float PAD = 6;
    /** The strip's left edge, and how far in from the wrap edge chips stay. */
    public static final float MARGIN = 2;
    /** Room kept over the top row for the @N badges, which overhang a chip's top. */
    public static final float BADGE_ROOM = 6;
    /** Rows of vanilla's command suggestion list at most (ChatScreen asks CommandSuggestions for 10). */
    private static final int SUGGESTION_ROWS = 10;

    private TabStripLayout() {
    }

    /**
     * Lays out {@code count} chips ({@code widths}, in strip order) from {@code x0}, wrapping before {@code x1}: each
     * chip's left edge goes to {@code outX}, its row (0 = top) to {@code outRow}.
     *
     * @return how many rows (0 for no chips)
     */
    public static int layout(float[] widths, int count, float x0, float x1, float[] outX, int[] outRow) {
        float x = x0;
        int row = 0;
        for (int i = 0; i < count; i++) {
            float w = widths[i];
            if (x > x0 && x + w > x1) {
                x = x0;
                row++;
            }
            outX[i] = x;
            outRow[i] = row;
            x += w + GAP;
        }
        return count == 0 ? 0 : row + 1;
    }

    /** The strip's height for {@code rows} rows: 18 each, a 1-unit gap between them; 0 for none. */
    public static float height(int rows) {
        return rows <= 0 ? 0 : rows * TAB_H + (rows - 1) * ROW_GAP;
    }

    /** The top of a row, the strip's top being {@code top}. */
    public static float rowTop(float top, int row) {
        return top + row * (TAB_H + ROW_GAP);
    }

    /** Where the strip wraps: the history plate's right edge, never past the screen's, less the margin. */
    public static float wrapEdge(float plateRight, int screenWidth) {
        return Math.min(plateRight, screenWidth) - MARGIN;
    }

    /**
     * Which way the history slides on a tab change from {@code fromSlot} to {@code toSlot} (ChatTabs slots): 1 in from
     * the right when the new tab is right of the old one, -1 from the left, 0 without both. The strip is always in slot
     * order, so slots compare even when a hidden channel's tab came or went with the change (strip indexes taken from
     * the layouts before and after it wouldn't).
     */
    public static int slideDir(int fromSlot, int toSlot) {
        return fromSlot < 0 || toSlot < 0 ? 0 : Integer.signum(toSlot - fromSlot);
    }

    /**
     * The chip at ({@code mx}, {@code my}), as laid out ({@link #layout}) with the strip's top at {@code top}; -1 in a
     * gap or off the strip.
     */
    public static int hit(float[] x, int[] row, float[] w, int count, float top, double mx, double my) {
        for (int i = 0; i < count; i++) {
            float y0 = rowTop(top, row[i]);
            if (mx >= x[i] && mx < x[i] + w[i] && my >= y0 && my < y0 + TAB_H) {
                return i;
            }
        }
        return -1;
    }

    /**
     * The highest top vanilla's command suggestion list can have on a screen this tall: it ends at the input row
     * (h-15) and shows at most 10 rows of 12, plus its 1-unit frame.
     */
    public static int suggestTop(int screenHeight) {
        return screenHeight - 15 - 12 * SUGGESTION_ROWS - 1;
    }

    /**
     * Whether something over the strip has the pointer there: the emoji picker ({@code coverA}) or the GIF viewer
     * ({@code coverB}) — rects {x0, y0, x1, y1}, null when closed — or vanilla's suggestion list while it shows
     * ({@code listVisible}), which may reach up to {@code suggestTop} and sits above the strip (z 200 over 150).
     */
    public static boolean blocked(double mx, double my, int @Nullable [] coverA, int @Nullable [] coverB,
                                  boolean listVisible, double suggestTop) {
        return inside(coverA, mx, my) || inside(coverB, mx, my) || listVisible && my >= suggestTop;
    }

    private static boolean inside(int @Nullable [] rect, double x, double y) {
        return rect != null && x >= rect[0] && x < rect[2] && y >= rect[1] && y < rect[3];
    }
}
