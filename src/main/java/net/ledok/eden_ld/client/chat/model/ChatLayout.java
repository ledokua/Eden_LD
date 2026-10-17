package net.ledok.eden_ld.client.chat.model;

/**
 * Where a moved chat window sits (phase 9), independent of the screen's size: the corner it's anchored to, that corner
 * as fractions of the screen, its size in GUI units at 100% zoom, and the zoom (percent). Placed again — and kept whole
 * on screen — whenever the window or the GUI scale changes. Kept in the client's own file: it depends on the machine.
 */
public record ChatLayout(Anchor anchor, float fx, float fy, int width, int height, int zoom) {

    public static final int MIN_WIDTH = 80;
    public static final int MIN_HEIGHT = 40;
    public static final int MIN_ZOOM = 50;
    public static final int MAX_ZOOM = 200;

    public enum Anchor {
        TOP_LEFT(false, false), TOP_RIGHT(true, false), BOTTOM_LEFT(false, true), BOTTOM_RIGHT(true, true);

        final boolean right;
        final boolean bottom;

        Anchor(boolean right, boolean bottom) {
            this.right = right;
            this.bottom = bottom;
        }
    }

    /** A box on screen, in GUI units. */
    public record Rect(int x, int y, int width, int height) {
    }

    public static int clampZoom(int zoom) {
        return Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, zoom));
    }

    /**
     * How many lines the open chat shows so that {@code reserve} GUI units stay free above its history plate (the new
     * look's tab strip and its badges, phase 5): at most {@code perPage}, at least one. {@code bottom} is the newest
     * line's bottom (GUI units); the plate reaches 2 units above the top line; a line is {@code lineHeight} chat units
     * drawn at {@code scale}.
     */
    public static int capLines(int perPage, double bottom, double reserve, int lineHeight, double scale) {
        int fit = (int) Math.floor((bottom - 2 - reserve) / (lineHeight * scale));
        return Math.max(1, Math.min(perPage, fit));
    }

    /** Where the window goes on a screen this size: zoomed, no bigger than the screen, and wholly on it. */
    public Rect place(int screenWidth, int screenHeight) {
        double scale = clampZoom(zoom) / 100.0;
        int w = clamp((int) Math.round(width * scale), Math.min(MIN_WIDTH, screenWidth), screenWidth);
        int h = clamp((int) Math.round(height * scale), Math.min(MIN_HEIGHT, screenHeight), screenHeight);
        int cornerX = Math.round(fx * screenWidth);
        int cornerY = Math.round(fy * screenHeight);
        int x = clamp(anchor.right ? cornerX - w : cornerX, 0, screenWidth - w);
        int y = clamp(anchor.bottom ? cornerY - h : cornerY, 0, screenHeight - h);
        return new Rect(x, y, w, h);
    }

    /** The layout of a window dropped at {@code rect}: anchored to the screen corner nearest its center. */
    public static ChatLayout of(Rect rect, int zoom, int screenWidth, int screenHeight) {
        boolean right = rect.x() + rect.width() / 2.0 > screenWidth / 2.0;
        boolean bottom = rect.y() + rect.height() / 2.0 > screenHeight / 2.0;
        Anchor anchor = right ? bottom ? Anchor.BOTTOM_RIGHT : Anchor.TOP_RIGHT
                : bottom ? Anchor.BOTTOM_LEFT : Anchor.TOP_LEFT;
        int cornerX = right ? rect.x() + rect.width() : rect.x();
        int cornerY = bottom ? rect.y() + rect.height() : rect.y();
        double scale = clampZoom(zoom) / 100.0;
        return new ChatLayout(anchor, cornerX / (float) screenWidth, cornerY / (float) screenHeight,
                (int) Math.round(rect.width() / scale), (int) Math.round(rect.height() / scale), clampZoom(zoom));
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
