package net.ledok.eden_ld.client.chat.ui;

import net.ledok.eden_ld.chat.ChatChannels.Channel;
import net.ledok.eden_ld.client.chat.ChatActivity;
import net.ledok.eden_ld.client.chat.ChatTabActions;
import net.ledok.eden_ld.client.chat.ClientChatChannel;
import net.ledok.eden_ld.client.chat.GifViewer;
import net.ledok.eden_ld.client.chat.model.ChatTabSpec;
import net.ledok.eden_ld.client.chat.panel.ChatEntry;
import net.ledok.eden_ld.client.chat.panel.ChatPanel;
import net.ledok.eden_ld.client.chat.panel.ChatView;
import net.ledok.eden_ld.client.chat.panel.ChatViews;
import net.ledok.eden_ld.client.hub.HubNotifications;
import net.ledok.vectorlib.client.canvas.BoxStyle;
import net.ledok.vectorlib.client.canvas.Motion;
import net.ledok.vectorlib.client.canvas.RoundedBox;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * Eden's chat look on one chat screen ({@code docs/PLAN-chat-v2.md}, phase 4a) — vanilla's chat, the bed's, any
 * {@link ChatScreen}; ChatScreenMixin creates it the first time the screen draws itself. Everything stays where vanilla
 * puts it: the history ({@code ChatView}) and vanilla's own input line, which this frames with a translucent plate (in
 * place of vanilla's input strip) and fades in with the panels. Two pills sit in the history plate's bottom: "↓ New"
 * while the chat is scrolled up (back to the newest line) and "↓ Mentions" while mentions wait (to the oldest); they
 * stay out of the way of command suggestions and the lines under them can't be hovered or clicked through them. The
 * menus and the emoji picker live on its {@link ChatCanvas} (phase 4b).
 * <p>
 * Phase 5: the tab strip ({@link ChatTabStrip}) sits above the history plate, which then always spans the chat's
 * height (the strip never jumps; a short screen shows a line or two fewer, {@code ChatView.reserve}), and a tab
 * switch slides the history in. Left of the input line a glyph says where the message goes — the channel typed into,
 * a typed "!l " prefix's, or a dim "/" for a command; it pops when the channel changes, a click picks the next channel
 * (Shift: back), a right-click opens that channel's menu. Ctrl+Tab steps through the tabs (Shift: back), and so does a
 * bare Shift+Tab on an empty line with no suggestion list; Page Up/Down page by the lines shown.
 */
public final class EdenChatUi {

    /** The input plate: vanilla's input strip (h-14..h-2) grown one row down, docked to the left edge. */
    private static final int INPUT_TOP = 14;
    private static final int INPUT_BOTTOM = 1;
    /** How far the input row rises as the panels come in. */
    private static final float RISE = 4;
    private static final int PILL_HEIGHT = 12;
    /** The glyph's chip in the input row: from x 2, rows h-13..h-3; its label 3 in from each side. */
    private static final int GLYPH_X = 2;
    private static final int GLYPH_TOP = 13;
    private static final int GLYPH_HEIGHT = 10;
    private static final int GLYPH_PAD = 3;
    /** The glyph for a command (the line goes to no channel). */
    private static final Component SLASH = Component.literal("/");
    private static final Channel[] CHANNELS = Channel.values();

    private final ChatScreen screen;
    private final ChatCanvas canvas;
    private final ChatTabStrip strip = new ChatTabStrip();
    /** The screen's size last frame: a resize snaps the strip's lit plate. */
    private int width = -1;
    private int height = -1;
    /** The glyph: its pop when the channel typed into changes, the epoch it last showed, its widths, its tooltip. */
    private final Motion.Presence glyphPop = new Motion.Presence(260, 0).snap(true);
    private int glyphEpoch;
    private boolean glyphStarted;
    private final int[] glyphLabelWidth = new int[CHANNELS.length + 1];
    private int glyphWidth;
    private int glyphFonts = -1;
    private String glyphLanguage = "";
    private int glyphTipKey = -1;
    private List<Component> glyphTip = List.of();
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
        this.canvas = new ChatCanvas(screen);
    }

    public ChatScreen screen() {
        return screen;
    }

    /** The menus and the emoji picker. */
    public ChatCanvas canvas() {
        return canvas;
    }

    /**
     * The start of every frame, before vanilla draws the chat: the tabs follow the client's state, the strip lays out
     * (and notes a tab switch for the history's slide), and the history keeps the strip's room above its plate.
     */
    public void frame() {
        ChatLook.showing(this);
        canvas.sync(screen.width, screen.height, ChatTheme.current());
        if (screen.width != width || screen.height != height) {
            width = screen.width;
            height = screen.height;
            strip.snap();
        }
        ChatTabActions.refresh();
        ChatView view = ChatViews.main();
        strip.layout(view.tabs(), Minecraft.getInstance().font, view.plateRight(), screen.width);
        float stripHeight = strip.height();
        view.reserve(stripHeight > 0 ? stripHeight + TabStripLayout.GAP + TabStripLayout.BADGE_ROOM : 0);
    }

    /** The screen was set up again (shown after another screen over it, or resized): no slide for what changed. */
    public void reinit() {
        strip.snap();
    }

    /** How far right of its place the history is drawn this frame (GUI units; the slide after a tab switch). */
    public float slideDx() {
        return strip.slideDx();
    }

    /** How strong the history's lines are drawn this frame (the slide after a tab switch fades them in). */
    public float slideAlpha() {
        return strip.slideAlpha();
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
        // A panel opening over a pill takes its place at once (a pill fading out would show through the panel).
        if (mentionsRect.under(coverA) || mentionsRect.under(coverB)) {
            mentionsPill.snap(false);
        } else {
            mentionsPill.set(room && mentions > 0);
        }
        if (newRect.under(coverA) || newRect.under(coverB)) {
            newPill.snap(false);
        } else {
            newPill.set(room && viewport.scroll() > 0);
        }
        newRect.live = newPill.on() && newPill.visible();
        mentionsRect.live = mentionsPill.on() && mentionsPill.visible();

        ChatTheme theme = ChatTheme.current();
        float chrome = ChatLook.chrome();
        graphics.pose().pushPose();
        // Over the history's text (z 100, the font a little nearer) and its toolbar (120); under the GIF viewer (drawn
        // later at 150) and the chat's canvas (the picker 300, the menus 600).
        graphics.pose().translate(0, 0, 150);
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

    /**
     * A press the canvas didn't take: a pill (left: its action; right: nothing, but not the line under it), then a tab
     * of the strip, then the input glyph. {@code suggestions}: vanilla's suggestion list shows (it has the clicks
     * where it reaches over the strip).
     */
    public boolean clicked(double mouseX, double mouseY, int button, boolean suggestions) {
        if ((button == 0 || button == 1) && newRect.contains(mouseX, mouseY)) {
            if (button == 0) {
                ChatViews.main().resetScroll();
            }
            return true;
        }
        if ((button == 0 || button == 1) && mentionsRect.contains(mouseX, mouseY)) {
            if (button == 0) {
                ChatActivity.jumpToMention(null);
            }
            return true;
        }
        if (strip.clicked(mouseX, mouseY, button, canvas, suggestions, canvas.pickerRect(),
                GifViewer.bounds(screen.width, screen.height))) {
            return true;
        }
        return glyphClicked(mouseX, mouseY, button);
    }

    // ---------------------------------------------------------------- the tab strip

    /**
     * After the screen has drawn (render TAIL, z 150): the strip, right above this frame's history plate. Its tabs
     * aren't lit or explained while a menu is open ({@code ChatCanvas.menuOpen}) or where the picker, the GIF viewer
     * ({@code coverA}, {@code coverB}) or the suggestion list ({@code suggestions}) sits over them.
     */
    public void drawStrip(GuiGraphics graphics, Font font, int mouseX, int mouseY, boolean suggestions,
                          int @Nullable [] coverA, int @Nullable [] coverB) {
        strip.draw(graphics, font, mouseX, mouseY, canvas.menuOpen(), suggestions, coverA, coverB);
    }

    /**
     * A key before the screen's (after the canvas'): Ctrl+Tab shows the next tab (Ctrl+Shift+Tab: the one before),
     * a bare Shift+Tab the one before while the line is empty and no suggestion list shows (Tab and Shift+Tab stay
     * vanilla's otherwise: the list's own cycling, completing a name); Page Up/Down page by the lines the chat shows
     * (fewer than vanilla's page on a short screen with the strip).
     */
    public boolean keyPressed(int key, boolean suggestions, boolean emptyLine) {
        if (key == GLFW.GLFW_KEY_TAB) {
            if (Screen.hasControlDown()) {
                return ChatTabActions.cycle(Screen.hasShiftDown());
            }
            if (Screen.hasShiftDown() && !Screen.hasAltDown() && emptyLine && !suggestions) {
                return ChatTabActions.cycle(true);
            }
            return false;
        }
        if (key == GLFW.GLFW_KEY_PAGE_UP || key == GLFW.GLFW_KEY_PAGE_DOWN) {
            int page = Math.max(1, ChatViews.main().frame().lines() - 1);
            ChatPanel.scroll(key == GLFW.GLFW_KEY_PAGE_UP ? page : -page);
            return true;
        }
        return false;
    }

    // ---------------------------------------------------------------- the input glyph

    /**
     * How wide the input glyph's chip is for this font: its widest label (G, L, P, Gd — or the language's — and "/")
     * and its padding. The input line starts right after it (ChatScreenMixin, at init).
     */
    public static int glyphWidth(Font font) {
        int widest = font.width(SLASH);
        for (Channel channel : CHANNELS) {
            widest = Math.max(widest, font.width(ClientChatChannel.glyphLabel(channel)));
        }
        return widest + 2 * GLYPH_PAD;
    }

    /** The glyph's widths, measured again when the fonts or the language change. */
    private void measureGlyph(Font font) {
        String language = Minecraft.getInstance().options.languageCode;
        int fonts = ChatEntry.fontEpoch();
        if (fonts == glyphFonts && language.equals(glyphLanguage)) {
            return;
        }
        glyphFonts = fonts;
        glyphLanguage = language;
        for (Channel channel : CHANNELS) {
            glyphLabelWidth[channel.ordinal()] = font.width(ClientChatChannel.glyphLabel(channel));
        }
        glyphLabelWidth[CHANNELS.length] = font.width(SLASH);
        glyphWidth = glyphWidth(font);
    }

    private boolean overGlyph(double mouseX, double mouseY) {
        int y0 = screen.height - GLYPH_TOP;
        return glyphWidth > 0 && ClientChatChannel.current() != null && mouseX >= GLYPH_X
                && mouseX < GLYPH_X + glyphWidth && mouseY >= y0 && mouseY < y0 + GLYPH_HEIGHT;
    }

    /**
     * After the strip (render TAIL, z 150): the glyph left of the input line, rising and fading in with it — where the
     * message goes ({@code input}'s text: a "!l " prefix's channel, else the one typed into) in its color, a dim "/"
     * for a command. When the channel typed into changes, its letter pops in.
     */
    public void drawGlyph(GuiGraphics graphics, Font font, EditBox input, int mouseX, int mouseY) {
        Channel current = ClientChatChannel.current();
        if (current == null) {
            return;
        }
        measureGlyph(font);
        String value = input.getValue();
        boolean command = value.startsWith("/");
        Channel target = ClientChatChannel.effective(value);
        if (target == null) {
            return;
        }
        int epoch = ClientChatChannel.typingEpoch();
        if (!glyphStarted) {
            glyphStarted = true;
            glyphEpoch = epoch;
        } else if (epoch != glyphEpoch) {
            glyphEpoch = epoch;
            glyphPop.snap(false).set(true);
        }
        boolean hover = !canvas.menuOpen() && overGlyph(mouseX, mouseY);
        ChatTheme theme = ChatTheme.current();
        float chrome = ChatLook.chrome();
        int y0 = screen.height - GLYPH_TOP;
        graphics.pose().pushPose();
        graphics.pose().translate(0, rise(), 0);
        RoundedBox.draw(graphics, GLYPH_X, y0, glyphWidth, GLYPH_HEIGHT, hover ? theme.pipHover : theme.pip, chrome,
                false);
        graphics.flush();
        int color = ChatLook.textColor(command ? theme.tokens.inkDim() : ClientChatChannel.color(target), chrome);
        if (color != 0) {
            Component label = command ? SLASH : ClientChatChannel.glyphLabel(target);
            int labelWidth = glyphLabelWidth[command ? CHANNELS.length : target.ordinal()];
            // A new channel's letter grows in past its size and settles.
            float pop = glyphPop.on() ? 0.5f + 0.5f * Motion.outBack(glyphPop.raw(), 1.4f) : 1f;
            float cx = GLYPH_X + glyphWidth / 2f;
            float cy = y0 + GLYPH_HEIGHT / 2f;
            boolean scaled = pop != 1f;
            if (scaled) {
                graphics.pose().pushPose();
                graphics.pose().translate(cx, cy, 0);
                graphics.pose().scale(pop, pop, 1);
                graphics.pose().translate(-cx, -cy, 0);
            }
            graphics.drawString(font, label, GLYPH_X + (glyphWidth - labelWidth) / 2, y0 + 1, color, false);
            if (scaled) {
                graphics.pose().popPose();
            }
        }
        graphics.pose().popPose();
        if (hover) {
            graphics.renderComponentTooltip(font, glyphTooltip(current, target, command), mouseX, mouseY);
        }
    }

    /** Where the message goes, then what the mouse does; built again only when that changes. */
    private List<Component> glyphTooltip(Channel current, Channel target, boolean command) {
        int key = command ? 0 : 1 + target.ordinal() + 8 * (1 + current.ordinal());
        if (key != glyphTipKey) {
            glyphTipKey = key;
            List<Component> lines = new ArrayList<>();
            if (command) {
                lines.add(Component.translatable("chat.eden_ld.glyph.command"));
            } else {
                lines.add(Component.translatable(target == current ? "chat.eden_ld.glyph.target"
                        : "chat.eden_ld.glyph.once", Component.translatable(ChatTabSpec.of(target).labelKey())
                        .withColor(ClientChatChannel.color(target) & 0xFFFFFF)));
            }
            lines.add(Component.translatable("chat.eden_ld.glyph.hint").withStyle(ChatFormatting.DARK_GRAY));
            glyphTip = lines;
        }
        return glyphTip;
    }

    /**
     * A press on the glyph: left picks the next channel to write in (Shift: the one before; hidden channels are
     * skipped, and on a channel's tab the tab follows), right opens that channel's menu upward from the input row.
     */
    private boolean glyphClicked(double mouseX, double mouseY, int button) {
        if (button > 2 || !overGlyph(mouseX, mouseY)) {
            return false;
        }
        Channel current = ClientChatChannel.current();
        if (button == 0) {
            ClientChatChannel.cycle(Screen.hasShiftDown());
        } else if (button == 1 && current != null) {
            canvas.openTabMenu(ChatTabSpec.of(current), GLYPH_X, canvas.height(), canvas.height());
        }
        return true;
    }
}
