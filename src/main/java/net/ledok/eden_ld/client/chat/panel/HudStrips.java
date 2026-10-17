package net.ledok.eden_ld.client.chat.panel;

/**
 * The closed chat's strips ({@code chat_hud_style} = Strips, {@code docs/PLAN-chat-v2.md} "Closed-chat HUD"): where
 * {@link ChatView} draws a message's strip and how strong, apart from the drawing so it can be tested. In chat units,
 * inside the view's {@code translate(left) · scale(s) · translate(4)} pose: x -4 is the screen's left edge.
 */
final class HudStrips {

    /** The gap between two messages' strips (chat units), unless a physical pixel is wider ({@link #gap}). */
    static final float GAP = 1.2f;
    /** A strip's alpha (of 255) per unit of Text Background Opacity: vanilla's default 0.5 gives 150. */
    static final double ALPHA_PER_OPACITY = 300.0;
    /** Fainter than this rounds to nothing (about 1 of 255): not drawn. */
    static final float MIN_ALPHA = 0.004f;
    /** Docked to the screen's left edge; the right edge is the open history plate's ({@code boxWidth + 8}). */
    static final float LEFT = -4f;
    static final int RIGHT = 8;
    /** The open look's band (hover light, mention tint): a unit in from the strip at each side. */
    static final float BAND_LEFT = -3f;
    static final int BAND_RIGHT = 7;
    /** The Chat Delay line's height (vanilla's band under the newest line). */
    static final int QUEUE_HEIGHT = 9;

    private HudStrips() {
    }

    /**
     * The gap: {@link #GAP}, but never under one physical pixel ({@code guiScale × chatScale} pixels per chat unit),
     * so the anti-aliased edges never blur it shut.
     */
    static float gap(double guiScale, double chatScale) {
        double pixels = guiScale * chatScale;
        return pixels > 0 ? (float) Math.max(GAP, 1.0 / pixels) : GAP;
    }

    /**
     * A message's strip ({@code out} = x0, y0, x1, y1) over its shown rows {@code first..last} (0 = the newest, at the
     * bottom), half the gap in from each end of its band.
     */
    static void rect(float[] out, int base, int first, int last, int lineHeight, float gap, int boxWidth) {
        out[0] = LEFT;
        out[1] = base - (last + 1) * lineHeight + gap / 2;
        out[2] = boxWidth + RIGHT;
        out[3] = base - first * lineHeight - gap / 2;
    }

    /**
     * A message's band as the open look lights it ({@code out} as {@link #rect}): x -3 to {@code boxWidth + 7}, the
     * rows' full height. A mention's tint and the stripes sit on it in the open chat and on the HUD alike, so nothing
     * shifts while the strips and the plate fade into each other.
     */
    static void band(float[] out, int base, int first, int last, int lineHeight, int boxWidth) {
        out[0] = BAND_LEFT;
        out[1] = base - (last + 1) * lineHeight;
        out[2] = boxWidth + BAND_RIGHT;
        out[3] = base - first * lineHeight;
    }

    /** The Chat Delay line's strip under the newest line ({@code out} as {@link #rect}; y from the lines' base). */
    static void queue(float[] out, float gap, int boxWidth) {
        out[0] = LEFT;
        out[1] = gap / 2;
        out[2] = boxWidth + RIGHT;
        out[3] = QUEUE_HEIGHT - gap / 2;
    }

    /**
     * A strip's alpha (0..1): {@code min(255, 300 × backgroundOpacity) / 255} — raising Text Background Opacity past
     * the default still darkens it, as the classic band — times the message's fade and the handover from the open
     * look's panels; 0 when it would round to nothing.
     */
    static float alpha(double backgroundOpacity, double fade, float handover) {
        double alpha = Math.min(255.0, ALPHA_PER_OPACITY * backgroundOpacity) / 255.0 * fade * handover;
        return alpha < MIN_ALPHA ? 0f : (float) alpha;
    }
}
