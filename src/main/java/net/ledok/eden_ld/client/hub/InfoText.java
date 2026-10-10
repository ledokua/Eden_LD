package net.ledok.eden_ld.client.hub;

import net.ledok.eden_ld.client.EdenParchment;
import net.ledok.vectorlib.client.canvas.Shapes;
import net.ledok.vectorlib.client.canvas.Stroke;
import net.ledok.vectorlib.client.canvas.TextNode;
import net.ledok.vectorlib.client.canvas.layout.Sizing;
import net.ledok.vectorlib.client.canvas.widget.ContextMenu;
import net.ledok.vectorlib.client.canvas.widget.Widget;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.function.Supplier;

/**
 * A list row's title and an optional second line, with its rarer actions in a right-click menu —
 * so rows keep only their main button. Shows a hover outline while it has a menu.
 */
final class InfoText extends Widget {

    private final HubScreen screen;
    private final Component title;
    @Nullable
    private final Component subtitle;
    private int titleColor = EdenParchment.INK;
    private int subtitleColor = EdenParchment.INK_DIM;
    @Nullable
    private Supplier<List<ContextMenu.Item>> menu;

    InfoText(HubScreen screen, float width, Component title, @Nullable Component subtitle) {
        super(width, subtitle == null ? 10 : 21);
        this.screen = screen;
        this.title = title;
        this.subtitle = subtitle;
        sizing(Sizing.fixed(width), Sizing.fixed(height));
    }

    InfoText colors(int title, int subtitle) {
        this.titleColor = title;
        this.subtitleColor = subtitle;
        return this;
    }

    /** The right-click menu's items, built when it opens (so they show the current state). */
    InfoText menu(Supplier<List<ContextMenu.Item>> items) {
        this.menu = items;
        return this;
    }

    @Override
    protected void rebuild() {
        if (hovered && menu != null) {
            add(Shapes.rect(0.5f, 0.5f, width - 1, height - 1).fill(0x18FFFFFF)
                    .stroke(Stroke.of(EdenParchment.HAIRLINE_HI, 1)));
        }
        int textWidth = (int) width - 4;
        add(TextNode.of(title).at(2, 1).maxWidth(textWidth).ellipsis(true)
                .color(titleColor).shadow(EdenParchment.TEXT_SHADOW));
        if (subtitle != null) {
            add(TextNode.of(subtitle).at(2, 12).maxWidth(textWidth).ellipsis(true)
                    .color(subtitleColor).shadow(EdenParchment.TEXT_SHADOW));
        }
    }

    @Override
    public boolean onMouseDown(float x, float y, int button) {
        if (button != 1 || menu == null) {
            return false;
        }
        ContextMenu context = ContextMenu.create();
        menu.get().forEach(context::item);
        float[] at = localToRoot(x, y);
        context.open(screen.canvas(), at[0], at[1], this);
        return true;
    }
}
