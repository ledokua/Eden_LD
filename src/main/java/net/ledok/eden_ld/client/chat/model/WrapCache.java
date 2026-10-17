package net.ledok.eden_ld.client.chat.model;

import java.util.function.IntFunction;

/**
 * A message's lines wrapped for the two widths it was last drawn at (the open chat and the HUD, or two chat windows,
 * can differ), each under the font it was wrapped with ({@code fontEpoch}: a resource reload or a font switch wraps
 * again).
 */
public final class WrapCache<T> {

    /** An empty slot (no real width is this). */
    private static final int EMPTY = Integer.MIN_VALUE;

    private T first;
    private int firstWidth = EMPTY;
    private int firstEpoch;
    private T second;
    private int secondWidth = EMPTY;
    private int secondEpoch;

    /** The lines at this width, wrapped by {@code wrap} only when neither slot has them (the older slot goes). */
    public T get(int width, int fontEpoch, IntFunction<T> wrap) {
        if (firstWidth == width && firstEpoch == fontEpoch) {
            return first;
        }
        if (secondWidth == width && secondEpoch == fontEpoch) {
            T lines = second;
            second = first;
            secondWidth = firstWidth;
            secondEpoch = firstEpoch;
            first = lines;
            firstWidth = width;
            firstEpoch = fontEpoch;
            return lines;
        }
        second = first;
        secondWidth = firstWidth;
        secondEpoch = firstEpoch;
        first = wrap.apply(width);
        firstWidth = width;
        firstEpoch = fontEpoch;
        return first;
    }

    /** The text changed: both slots wrap again. */
    public void clear() {
        first = null;
        second = null;
        firstWidth = EMPTY;
        secondWidth = EMPTY;
    }
}
