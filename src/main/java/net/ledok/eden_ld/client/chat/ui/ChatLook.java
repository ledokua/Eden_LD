package net.ledok.eden_ld.client.chat.ui;

import net.ledok.eden_ld.client.chat.ClientChatChannel;
import net.ledok.eden_ld.client.hub.HubState;
import net.ledok.vectorlib.client.canvas.BoxStyle;
import net.ledok.vectorlib.client.canvas.Motion;
import net.ledok.vectorlib.client.canvas.RoundedBox;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import org.jetbrains.annotations.Nullable;

/**
 * Eden's new chat look as a whole ({@code docs/PLAN-chat-v2.md}, phase 4): whether it's on, the chat screen showing it
 * ({@link EdenChatUi}), and the one motion its panels share — they fade in when a chat opens and out when it closes.
 * The close is decided where the screen changes ({@link #screenChanging}): back to the game fades the panels out
 * (drawn from the HUD chat for those 140 ms), another chat screen (bed → chat) keeps them, any other screen covers the
 * chat at once. Reopening mid-fade continues from where the fade was.
 */
public final class ChatLook {

    /** The panels: in over 200 ms, out over 140 ms. */
    private static final Motion.Presence CHROME = new Motion.Presence(200, 140);

    private static @Nullable EdenChatUi open;
    /** Last frame's panels (GUI units), for the close fade. */
    private static final float[] HISTORY = new float[4];
    private static boolean historyShown;
    private static final float[] INPUT = new float[4];
    private static boolean inputShown;
    private static @Nullable BoxStyle historyStyle;
    private static @Nullable BoxStyle inputStyle;

    private ChatLook() {
    }

    /** On an Eden server, unless the player turned the new look off. */
    public static boolean enabled() {
        return ClientChatChannel.current() != null && HubState.chatNewLook();
    }

    /** A chat screen with the new look is on screen (called every frame it draws). */
    static void showing(EdenChatUi ui) {
        if (open != ui) {
            open = ui;
            historyShown = false;
            inputShown = false;
        }
        CHROME.set(true);
    }

    /** The host of the chat screen showing now, or null (vanilla's look, another screen, no screen). */
    @Nullable
    public static EdenChatUi open() {
        EdenChatUi ui = open;
        return ui != null && Minecraft.getInstance().screen == ui.screen() ? ui : null;
    }

    /** How far the panels are in (0..1, eased). */
    public static float chrome() {
        return CHROME.value();
    }

    /** {@code Minecraft.setScreen}, before the old screen goes. */
    public static void screenChanging(@Nullable Screen from, @Nullable Screen to) {
        EdenChatUi ui = open;
        if (ui == null || from != ui.screen()) {
            return;
        }
        if (to instanceof ChatScreen) {
            // Bed chat turning into the normal chat: the panels stay (the new screen's host carries on).
            return;
        }
        open = null;
        if (to == null) {
            CHROME.set(false);
        } else {
            CHROME.snap(false);
        }
    }

    // ---------------------------------------------------------------- the panels, for the close fade

    /** The history plate drawn this frame (x0, y0, x1, y1), or null. */
    @Nullable
    static float[] historyRect() {
        return historyShown && open() != null ? HISTORY : null;
    }

    /** The open chat drew its history plate here (GUI units). */
    public static void history(float x0, float y0, float x1, float y1, BoxStyle style) {
        set(HISTORY, x0, y0, x1, y1);
        historyShown = true;
        historyStyle = style;
    }

    static void input(float x0, float y0, float x1, float y1, BoxStyle style) {
        set(INPUT, x0, y0, x1, y1);
        inputShown = true;
        inputStyle = style;
    }

    private static void set(float[] rect, float x0, float y0, float x1, float y1) {
        rect[0] = x0;
        rect[1] = y0;
        rect[2] = x1;
        rect[3] = y1;
    }

    /**
     * The closed chat (HUD), before its lines: the panels fading out after a close. Only with no screen open — the
     * HUD chat isn't drawn under F1 or with chat hidden, so neither is this.
     */
    public static void drawClosing(GuiGraphics graphics) {
        if (!closing()) {
            return;
        }
        float alpha = CHROME.value();
        if (historyShown && historyStyle != null) {
            RoundedBox.draw(graphics, HISTORY[0], HISTORY[1], HISTORY[2] - HISTORY[0], HISTORY[3] - HISTORY[1],
                    historyStyle, alpha, false);
        }
        if (inputShown && inputStyle != null) {
            RoundedBox.draw(graphics, INPUT[0], INPUT[1], INPUT[2] - INPUT[0], INPUT[3] - INPUT[1], inputStyle, alpha,
                    false);
        }
    }

    /** Whether {@link #drawClosing} draws the panels now (fading out, no screen over the HUD). */
    private static boolean closing() {
        return !CHROME.on() && open == null && Minecraft.getInstance().screen == null && CHROME.visible();
    }

    /**
     * How far the closed chat's own backgrounds (the HUD strips) are in, 0..1: the panels fading out hand over to
     * them — 1 minus the alpha {@link #drawClosing} draws the panels at, so the two never darken twice — and 1 when
     * nothing is fading out.
     */
    public static float handover() {
        return closing() ? 1f - CHROME.value() : 1f;
    }

    // ---------------------------------------------------------------- shared helpers

    /** {@code argb}'s alpha times {@code fade}; 0 when it would land under 4 (the font draws alpha 0..3 opaque). */
    public static int textColor(int argb, float fade) {
        int a = Math.round((argb >>> 24) * Math.max(0, Math.min(1, fade)));
        return a < 4 ? 0 : a << 24 | argb & 0xFFFFFF;
    }

    /** How strong the panels are drawn: vanilla's Text Background Opacity, its default (0.5) giving the full look. */
    public static float panelOpacity() {
        double opacity = Minecraft.getInstance().options.textBackgroundOpacity().get();
        return (float) Math.max(0, Math.min(1, opacity / 0.5));
    }

    /** Black or white text, whichever reads on {@code background}. */
    public static int textOn(int background) {
        return ChatTheme.light(background) ? 0xFF1A1A1A : 0xFFFFFFFF;
    }
}
