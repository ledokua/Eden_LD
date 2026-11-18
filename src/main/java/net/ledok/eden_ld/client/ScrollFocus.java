package net.ledok.eden_ld.client;

import net.ledok.vectorlib.client.canvas.CanvasNode;
import net.ledok.vectorlib.client.canvas.Rect;
import net.ledok.vectorlib.client.canvas.widget.ScrollPanel;
import org.jetbrains.annotations.Nullable;

/** Keeps keyboard focus visible inside VectorLib scroll panels, including nested panels. */
public final class ScrollFocus {

    private ScrollFocus() {
    }

    public static void reveal(@Nullable CanvasNode focused) {
        if (focused == null || focused.bounds() == null) {
            return;
        }
        CanvasNode child = focused;
        for (CanvasNode parent = child.parent(); parent != null; parent = parent.parent()) {
            if (parent instanceof ScrollPanel panel) {
                Rect bounds = child.bounds();
                float top = localY(child, panel, bounds.y());
                float bottom = localY(child, panel, bounds.bottom());
                float offset = top < 0 || bottom - top > panel.height() ? top : Math.max(0, bottom - panel.height());
                if (offset != 0) {
                    panel.scrollTo(panel.scrollY() + offset);
                }
                child = panel;
            }
        }
    }

    private static float localY(CanvasNode child, ScrollPanel panel, float y) {
        float[] root = child.localToRoot(0, y);
        return panel.rootToLocal(root[0], root[1])[1];
    }
}
