package net.ledok.eden_ld.client.hub;

import net.ledok.vectorlib.client.canvas.Animation;
import net.ledok.vectorlib.client.canvas.CanvasGroup;
import net.ledok.vectorlib.client.canvas.CanvasNode;
import net.ledok.vectorlib.client.canvas.CanvasRenderContext;
import net.ledok.vectorlib.client.canvas.Easing;
import net.ledok.vectorlib.client.canvas.Rect;
import net.ledok.vectorlib.client.canvas.VectorCanvas;
import net.ledok.vectorlib.client.canvas.layout.Resizable;
import net.ledok.vectorlib.client.canvas.layout.Sizing;
import org.jetbrains.annotations.Nullable;

/**
 * A side panel a bar folds away (Friends: "On this server" and the requests; Guild without one: the create form and
 * the invites) that slides instead of jumping. How far it's out (0..1) is kept here across the tab's rebuilds and
 * animates toward open or shut whenever the wanted state changes — a click on the bar, or the requests panel opening
 * by itself. While it slides shut it stays in the layout; the tab is built once more at the end, without it.
 */
final class HubDrawer {

    private static final long MS = 160;

    private final String key;
    /** The left panel: its content rides on the box's right edge, so it slides out from the tab's left edge. */
    private final boolean left;
    /** How far it's out; -1 before the first build. */
    private float shown = -1;
    private boolean open;
    @Nullable
    private VectorCanvas canvas;
    @Nullable
    private Box box;

    HubDrawer(String key, boolean left) {
        this.key = key;
        this.left = left;
    }

    /**
     * This build's wanted state; whether the panel belongs in the layout (open, or still sliding shut — then
     * {@code rebuild} builds the tab again once it's in). The first build, a hub opened anew and reduced motion
     * show it as wanted at once.
     */
    boolean present(HubScreen screen, boolean wanted, Runnable rebuild) {
        VectorCanvas now = screen.canvas();
        if (shown < 0 || now != canvas || !Animation.enabled()) {
            if (canvas != null) {
                canvas.cancelAnimation(key);
            }
            canvas = now;
            open = wanted;
            shown = wanted ? 1 : 0;
        } else if (wanted != open) {
            open = wanted;
            now.animate(key, Animation.between(shown, wanted ? 1 : 0, MS, Easing.OUT_CUBIC, value -> {
                shown = value;
                if (box != null) {
                    box.reveal(value);
                }
            }).onDone(() -> {
                if (!open) {
                    rebuild.run();
                }
            }));
        }
        return open || shown > 0;
    }

    /** Whether the bar's state is open (the panel may still be sliding either way). */
    boolean open() {
        return open;
    }

    /**
     * The panel, {@code width} wide and as tall as its place, in a box showing the part that's out. {@code gap}: the
     * space between it and what it slides against, inside the box (so a shut panel leaves no gap behind).
     */
    CanvasNode wrap(CanvasNode panel, float width, float gap) {
        box = new Box(panel, width, gap);
        box.reveal(shown);
        return box;
    }

    /** Clips the panel to its own width; the panel itself is always laid out at full width. */
    private final class Box extends CanvasGroup implements Resizable {
        private final CanvasNode panel;
        private final float panelWidth;
        private final float gap;
        private float width;
        private float height;

        Box(CanvasNode panel, float panelWidth, float gap) {
            this.panel = panel;
            this.panelWidth = panelWidth;
            this.gap = gap;
            add(panel);
        }

        void reveal(float part) {
            sizing(Sizing.fixed(Math.max(0f, (panelWidth + gap) * part)), Sizing.fill());
        }

        @Override
        public void resize(float w, float h) {
            width = w;
            height = h;
            if (panel instanceof Resizable resizable) {
                resizable.resize(panelWidth, h);
            }
            // Right panel: the gap first, content on the box's left edge. Left panel: content then the gap, both
            // riding on the right edge.
            panel.transform.translate(left ? w - panelWidth - gap : gap, 0);
        }

        @Override
        public Rect bounds() {
            return new Rect(0, 0, width, height);
        }

        @Override
        protected boolean clipsChildrenForPicking() {
            return true;
        }

        @Override
        protected void draw(CanvasRenderContext ctx) {
            if (width <= 0) {
                return;
            }
            ctx.pushClip(new Rect(0, 0, width, height));
            super.draw(ctx);
            ctx.popClip();
        }
    }
}
