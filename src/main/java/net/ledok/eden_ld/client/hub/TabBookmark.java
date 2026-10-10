package net.ledok.eden_ld.client.hub;

import net.ledok.eden_ld.hub.HubSettings;
import net.ledok.vectorlib.client.canvas.CanvasNode;
import net.ledok.vectorlib.client.canvas.Icons;
import net.ledok.vectorlib.client.canvas.Shapes;
import net.ledok.vectorlib.client.canvas.Stroke;
import net.ledok.vectorlib.client.canvas.TextNode;
import net.ledok.vectorlib.client.canvas.layout.Sizing;
import net.ledok.vectorlib.client.canvas.widget.UiSounds;
import net.ledok.vectorlib.client.canvas.widget.Widget;
import net.ledok.vectorlib.client.canvas.widget.WidgetStyle;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * One hub bookmark: an icon only, the same width open or closed (the open one stands taller and
 * the page shows its name as a title; every bookmark names itself in a tooltip). News shows as a Discord-style badge in the top right
 * corner — a count, or a dot for a tab never opened — or as a glow around the bookmark, in the
 * player's notification color (Settings → Visual, Notifications). Live: it redraws itself when
 * a count changes while the hub is open.
 */
final class TabBookmark extends Widget {

    /** Placeholder shapes; drawn icons are bigger. */
    private static final float ICON = 8;
    private static final float ART = 16;
    /** A bookmark's width: just the icon. */
    private static final float CLOSED_WIDTH = 24;
    private static final int OUTLINE = 0xC0000000;

    private final HubTab tab;
    private final boolean active;
    private final Runnable onClick;
    private boolean pressed;

    TabBookmark(HubTab tab, boolean active, float height, Runnable onClick, WidgetStyle activeStyle) {
        super(width(tab, active), height);
        this.tab = tab;
        this.active = active;
        this.onClick = onClick;
        sizing(Sizing.fixed(width), Sizing.fixed(height));
        if (active) {
            style(activeStyle);
        }
        tooltip(this::newsTooltip);
        bind(() -> List.of(HubNotifications.count(tab), HubNotifications.isNew(tab), HubNotifications.style(),
                HubNotifications.color(), tab == HubTab.DAILIES && HubIcons.dailiesDone(),
                tab == HubTab.MAIL && HubIcons.mailUnread()));
    }

    /** Every bookmark is icon-only and the same width, so no tab moves when another opens (the open
     *  tab's name is the page title instead). */
    static float width(HubTab tab, boolean active) {
        return CLOSED_WIDTH;
    }

    private static float iconSize(HubTab tab) {
        return HubIcons.tab(tab) != null ? ART : ICON;
    }

    private List<Component> newsTooltip() {
        List<Component> lines = new ArrayList<>();
        lines.add(tab.displayName());
        int count = HubNotifications.count(tab);
        if (count > 0) {
            lines.add(Component.translatable("gui.eden_ld.hub.tab.news." + tab.name().toLowerCase(), count));
        } else if (HubNotifications.isNew(tab)) {
            lines.add(Component.translatable("gui.eden_ld.hub.tab.new"));
        }
        return lines.isEmpty() ? null : lines;
    }

    @Override
    protected void rebuild() {
        boolean lit = hovered || focused;
        WidgetStyle.Skin skin = pressed ? style.buttonPressed() : lit ? style.buttonHover() : style.button();
        add(skin.create(0, 0, width, height));
        if (lit && !pressed && !active) {
            add(Shapes.rect(0.5f, 0.5f, width - 1, height - 1).stroke(Stroke.of(style.accent(), 1)));
        }
        int text = lit ? style.textHover() : style.text();
        float size = iconSize(tab);
        add(icon(text).at((width - size) / 2, (height - size) / 2));
        news();
    }

    /** The tab's drawn icon; until one exists a colored square or circle; the settings bookmark keeps its gear. */
    private CanvasNode icon(int textColor) {
        HubIcons.Icon art = HubIcons.tab(tab);
        if (art != null) {
            return art.node(ART);
        }
        if (tab == HubTab.SETTINGS) {
            return Icons.gear(ICON, textColor);
        }
        return tab.round
                ? Shapes.circle(ICON / 2, ICON / 2, ICON / 2).fill(tab.iconColor).stroke(OUTLINE, 1)
                : Shapes.rect(0, 0, ICON, ICON).fill(tab.iconColor).stroke(OUTLINE, 1);
    }

    private void news() {
        int count = HubNotifications.count(tab);
        boolean fresh = count == 0 && HubNotifications.isNew(tab);
        if (count == 0 && !fresh) {
            return;
        }
        int color = HubNotifications.color();
        HubSettings.NotifyStyle style = HubNotifications.style();
        if (style != HubSettings.NotifyStyle.BADGE) {
            // A glow inside the frame (bookmarks touch, so an outer one would run into the next),
            // fading one unit further in.
            add(Shapes.rect(1.5f, 1.5f, width - 3, height - 3).stroke(Stroke.of(color, 1)));
            add(Shapes.rect(2.5f, 2.5f, width - 5, height - 5).stroke(Stroke.of((color & 0x00FFFFFF) | 0x60000000, 1)));
        }
        if (style == HubSettings.NotifyStyle.GLOW) {
            return;
        }
        if (fresh) {
            add(Shapes.circle(width - 3, 3, 3).fill(color).stroke(OUTLINE, 1));
            return;
        }
        String number = count > 9 ? "9+" : String.valueOf(count);
        float scale = 0.75f;
        float textWidth = Minecraft.getInstance().font.width(number) * scale;
        float badgeHeight = 9;
        float badgeWidth = Math.max(badgeHeight, textWidth + 5);
        float x = width - badgeWidth + 2;
        float y = -3;
        add(Shapes.roundRect(x, y, badgeWidth, badgeHeight, badgeHeight / 2).fill(color).stroke(OUTLINE, 1));
        add(TextNode.of(number).scale(scale).align(TextNode.Align.CENTER)
                .at(x + badgeWidth / 2 + 0.5f, y + 2).color(0xFFFFFFFF).shadow(false));
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
    public boolean focusable() {
        return true;
    }

    @Override
    public boolean onKeyPressed(int key, int scanCode, int modifiers) {
        if (key == 257 || key == 335 || key == 32) {
            UiSounds.click();
            onClick.run();
            return true;
        }
        return false;
    }

    @Override
    public Cursor cursor() {
        return Cursor.HAND;
    }
}
