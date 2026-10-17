package net.ledok.eden_ld.client.chat.ui;

import net.ledok.eden_ld.client.chat.ChatActivity;
import net.ledok.eden_ld.client.chat.panel.ChatViews;
import net.ledok.eden_ld.client.hub.HubNotifications;
import net.ledok.vectorlib.client.canvas.BoxStyle;
import net.ledok.vectorlib.client.canvas.Motion;
import net.ledok.vectorlib.client.canvas.RoundedBox;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

/**
 * Eden's chat look on one chat screen ({@code docs/PLAN-chat-v2.md}, phase 4a) — vanilla's chat, the bed's, any
 * {@link ChatScreen}; ChatScreenMixin creates it the first time the screen draws itself. Everything stays where vanilla
 * puts it: the history ({@code ChatView}) and vanilla's own input line, which this frames with a translucent plate (in
 * place of vanilla's input strip) and fades in with the panels. Two pills sit in the history plate's bottom: "↓ New"
 * while the chat is scrolled up (back to the newest line) and "↓ Mentions" while mentions wait (to the oldest); they
 * stay out of the way of command suggestions and the lines under them can't be hovered or clicked through them.
 */
public final class EdenChatUi {

    /** The input plate: vanilla's input strip (h-14..h-2) grown one row down, docked to the left edge. */
    private static final int INPUT_TOP = 14;
    private static final int INPUT_BOTTOM = 1;
    /** How far the input row rises as the panels come in. */
    private static final float RISE = 4;
    private static final int PILL_HEIGHT = 12;

    private final ChatScreen screen;
    private final Motion.Presence newPill = new Motion.Presence(160, 120);
    private final Motion.Presence mentionsPill = new Motion.Presence(160, 120);
    private final Pill newRect = new Pill();
    private final Pill mentionsRect = new Pill();

    /** A pill's last frame: where it was (final place, no motion), what it said. */
    private static final class Pill {
        float x0;
        float y0;
        float x1;
        float y1;
        boolean live;
        Component label = Component.empty();

        boolean contains(double x, double y) {
            return live && x >= x0 && x < x1 && y >= y0 && y < y1;
        }

        /** Whether a panel {x0, y0, x1, y1} (null: none) covers any of it. */
        boolean under(int @Nullable [] panel) {
            return panel != null && panel[0] < x1 && panel[2] > x0 && panel[1] < y1 && panel[3] > y0;
        }
    }

    public EdenChatUi(ChatScreen screen) {
        this.screen = screen;
    }

    public ChatScreen screen() {
        return screen;
    }

    /** The start of every frame, before vanilla draws the chat. */
    public void frame() {
        ChatLook.showing(this);
    }

    /** How far below its place the input row is drawn while the panels come in. */
    public float rise() {
        return RISE * (1 - ChatLook.chrome());
    }

    // ---------------------------------------------------------------- the input row

    /** In place of vanilla's input strip: the input plate, lit while the line has the keyboard. */
    public void drawInputPlate(GuiGraphics graphics, boolean focused) {
        float x1 = screen.width - 2;
        float y0 = screen.height - INPUT_TOP;
        float y1 = screen.height - INPUT_BOTTOM;
        BoxStyle style = ChatTheme.current().input(ChatLook.panelOpacity(), focused);
        ChatLook.input(0, y0, x1, y1, style);
        graphics.pose().pushPose();
        graphics.pose().translate(0, rise(), 0);
        RoundedBox.draw(graphics, 0, y0, x1, y1 - y0, style, ChatLook.chrome(), false);
        graphics.pose().popPose();
    }

    /** The line's text in the theme's ink, faded with the panels (alpha never under 4: the font would draw it solid). */
    public void styleInput(EditBox input) {
        int color = ChatLook.textColor(ChatTheme.current().tokens.ink(), ChatLook.chrome());
        input.setTextColor(color == 0 ? 0x04000000 | ChatTheme.current().tokens.ink() & 0xFFFFFF : color);
    }

    // ---------------------------------------------------------------- the pills

    /**
     * After the screen has drawn: the pills, in the history plate's bottom row ("↓ Mentions" at its right end, "↓ New"
     * centered, or left of it when they'd meet). Hidden while command suggestions are open over them and while the
     * emoji picker or the GIF viewer ({@code coverA}, {@code coverB}: {x0, y0, x1, y1}, null when closed) sits on them;
     * not lit while a menu is open ({@code menus}: the pointer belongs to the menu).
     */
    public void drawPills(GuiGraphics graphics, Font font, int mouseX, int mouseY, boolean suggestions, boolean menus,
                          int @Nullable [] coverA, int @Nullable [] coverB) {
        float[] plate = ChatLook.historyRect();
        int mentions = ChatActivity.mentionsTotal();
        var viewport = ChatViews.main().viewport();
        if (plate == null) {
            mentionsPill.set(false);
            newPill.set(false);
            newRect.live = mentionsRect.live = false;
            return;
        }
        float y1 = plate[3] - 4;
        float y0 = y1 - PILL_HEIGHT;
        if (mentions > 0) {
            mentionsRect.label = Component.translatable("chat.eden_ld.mentions.jump", mentions);
        }
        float mentionsWidth = font.width(mentionsRect.label) + 12;
        mentionsRect.x1 = plate[2] - 6;
        mentionsRect.x0 = mentionsRect.x1 - mentionsWidth;
        if (viewport.scroll() > 0) {
            newRect.label = viewport.newCount() > 0
                    ? Component.translatable("chat.eden_ld.jump.new", viewport.newCount())
                    : Component.translatable("chat.eden_ld.jump");
        }
        float newWidth = font.width(newRect.label) + 12;
        float center = (plate[0] + plate[2]) / 2;
        newRect.x0 = center - newWidth / 2;
        newRect.x1 = newRect.x0 + newWidth;
        if (mentionsPill.visible() && newRect.x1 > mentionsRect.x0 - 4) {
            newRect.x1 = mentionsRect.x0 - 4;
            newRect.x0 = newRect.x1 - newWidth;
        }
        for (Pill pill : new Pill[]{newRect, mentionsRect}) {
            pill.y0 = y0;
            pill.y1 = y1;
        }
        boolean room = !suggestions;
        mentionsPill.set(room && mentions > 0 && !mentionsRect.under(coverA) && !mentionsRect.under(coverB));
        newPill.set(room && viewport.scroll() > 0 && !newRect.under(coverA) && !newRect.under(coverB));
        newRect.live = newPill.on() && newPill.visible();
        mentionsRect.live = mentionsPill.on() && mentionsPill.visible();

        ChatTheme theme = ChatTheme.current();
        float chrome = ChatLook.chrome();
        graphics.pose().pushPose();
        graphics.pose().translate(0, 0, 100);
        // ↓ New: the plate's color, a foot line in the unread color while new lines wait.
        float e = newPill.value() * chrome;
        if (e > 0.004f) {
            boolean hover = !menus && newRect.contains(mouseX, mouseY);
            float dy = 4 * (1 - newPill.value());
            RoundedBox.draw(graphics, newRect.x0, newRect.y0 + dy, newWidth, PILL_HEIGHT,
                    hover ? theme.pillHover : theme.pill, e, false);
            if (viewport.newCount() > 0) {
                RoundedBox.draw(graphics, newRect.x0 + 3, newRect.y1 + dy - 2, newWidth - 6, 1, theme.dot, e, false);
            }
            graphics.flush();
            int color = ChatLook.textColor(theme.tokens.ink(), e);
            if (color != 0) {
                graphics.drawString(font, newRect.label, Math.round(newRect.x0 + 6), Math.round(newRect.y0 + dy + 2),
                        color, false);
            }
        }
        // ↓ Mentions: filled in the notification color, so it reads as the alert it is.
        float m = mentionsPill.value() * chrome;
        if (m > 0.004f) {
            int badge = HubNotifications.color();
            boolean hover = !menus && mentionsRect.contains(mouseX, mouseY);
            float dy = 4 * (1 - mentionsPill.value());
            RoundedBox.draw(graphics, mentionsRect.x0, mentionsRect.y0 + dy, mentionsWidth, PILL_HEIGHT,
                    theme.badge(hover ? lighter(badge) : badge), m, false);
            graphics.flush();
            int color = ChatLook.textColor(ChatLook.textOn(badge), m);
            if (color != 0) {
                graphics.drawString(font, mentionsRect.label, Math.round(mentionsRect.x0 + 6),
                        Math.round(mentionsRect.y0 + dy + 2), color, false);
            }
            if (hover) {
                graphics.renderTooltip(font, Component.translatable("chat.eden_ld.mentions.jump.hint"), mouseX, mouseY);
            }
        }
        graphics.pose().popPose();
        ChatViews.main().block(newRect.live ? rect(newRect) : null, mentionsRect.live ? rect(mentionsRect) : null);
    }

    private static float[] rect(Pill pill) {
        return new float[]{pill.x0, pill.y0, pill.x1, pill.y1};
    }

    private static int lighter(int color) {
        return net.ledok.vectorlib.client.canvas.Colors.lerp(color | 0xFF000000, 0xFFFFFFFF, 0.2f);
    }

    /** A click on a pill (left: its action; right: nothing, but not the line under it). */
    public boolean clicked(double mouseX, double mouseY, int button) {
        if (button != 0 && button != 1) {
            return false;
        }
        if (newRect.contains(mouseX, mouseY)) {
            if (button == 0) {
                ChatViews.main().resetScroll();
            }
            return true;
        }
        if (mentionsRect.contains(mouseX, mouseY)) {
            if (button == 0) {
                ChatActivity.jumpToMention(null);
            }
            return true;
        }
        return false;
    }
}
