package net.ledok.eden_ld.client.hub;

import net.ledok.vectorlib.client.canvas.CanvasNode;
import net.ledok.vectorlib.client.canvas.CanvasRenderContext;
import net.ledok.vectorlib.client.canvas.widget.ScrollPanel;

import java.util.HashMap;
import java.util.Map;

/**
 * Scroll positions that survive a rebuild: a hub tab is built anew whenever fresh data arrives
 * (often the server's echo of the player's own click), and a new {@link ScrollPanel} would start
 * at the top. A panel made here starts where the last one with the same key was (for this
 * session).
 */
final class HubScroll {

    private static final Map<String, Kept> PANELS = new HashMap<>();

    private HubScroll() {
    }

    static ScrollPanel panel(String key, float width, float height, CanvasNode content) {
        Kept previous = PANELS.get(key);
        Kept panel = new Kept(width, height, content, previous == null ? 0 : previous.position());
        PANELS.put(key, panel);
        return panel;
    }

    /** Scrolls to the kept position on its first draw, once laid out at its real size. */
    private static final class Kept extends ScrollPanel {
        private float restore;

        Kept(float width, float height, CanvasNode content, float restore) {
            super(width, height, content);
            this.restore = restore;
        }

        /** Where it is, or will be once drawn (two rebuilds in a row keep the position). */
        float position() {
            return restore > 0 ? restore : scrollY();
        }

        @Override
        protected void draw(CanvasRenderContext ctx) {
            if (restore > 0) {
                scrollTo(restore);
                restore = 0;
            }
            super.draw(ctx);
        }
    }
}
