package net.ledok.eden_ld.client.hub;

import net.ledok.vectorlib.client.canvas.Shapes;
import net.ledok.vectorlib.client.canvas.Stroke;
import net.ledok.vectorlib.client.canvas.TextNode;
import net.ledok.vectorlib.client.canvas.layout.Sizing;
import net.ledok.vectorlib.client.canvas.widget.UiSounds;
import net.ledok.vectorlib.client.canvas.widget.Widget;
import net.ledok.vectorlib.client.canvas.widget.WidgetStyle;
import net.minecraft.network.chat.Component;

/**
 * A small button showing a drawn hub icon (or a text symbol until its file exists), skinned like
 * the tab bookmarks — the tab bar's close button.
 */
final class HubIconButton extends Widget {

    private static final float ART = 16;

    private final String icon;
    private final String fallback;
    private final Runnable onClick;
    private boolean pressed;

    HubIconButton(String icon, String fallback, float width, float height, Component tooltip, Runnable onClick) {
        super(width, height);
        this.icon = icon;
        this.fallback = fallback;
        this.onClick = onClick;
        sizing(Sizing.fixed(width), Sizing.fixed(height));
        tooltip(tooltip);
    }

    @Override
    protected void rebuild() {
        boolean lit = hovered || focused;
        WidgetStyle.Skin skin = pressed ? style.buttonPressed() : lit ? style.buttonHover() : style.button();
        add(skin.create(0, 0, width, height));
        if (lit && !pressed) {
            add(Shapes.rect(0.5f, 0.5f, width - 1, height - 1).stroke(Stroke.of(style.accent(), 1)));
        }
        HubIcons.Icon art = HubIcons.get(icon);
        if (art != null) {
            add(art.node(ART).at((width - ART) / 2, (height - ART) / 2));
        } else {
            add(TextNode.of(fallback).at(width / 2, textY(height)).align(TextNode.Align.CENTER)
                    .color(lit ? style.textHover() : style.text()).shadow(style.textShadow()));
        }
    }

    @Override
    public boolean onMouseDown(float x, float y, int button) {
        if (button != 0) {
            return false;
        }
        pressed = true;
        refresh();
        return true;
    }

    @Override
    public void onMouseUp(float x, float y, int button) {
        boolean inside = pressed && bounds().contains(x, y);
        pressed = false;
        refresh();
        if (inside) {
            UiSounds.click();
            onClick.run();
        }
    }

    @Override
    public Cursor cursor() {
        return Cursor.HAND;
    }
}
