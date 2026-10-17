package net.ledok.eden_ld.client.chat.panel;

import net.ledok.eden_ld.client.chat.ClientChatChannel;
import net.minecraft.client.GuiMessageTag;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Style;
import org.jetbrains.annotations.Nullable;

/**
 * Eden's chat window ({@code docs/PLAN-custom-chat.md}), drawn instead of vanilla's on Eden servers. Vanilla's chat
 * screen still asks the chat for the style under the mouse, to scroll, and so on — ChatComponentMixin hands those
 * questions here, and this class hands them to the main window's view ({@link ChatViews#main()}).
 */
public final class ChatPanel {

    /** How much longer a mention stays on the HUD (ticks). */
    public static final int MENTION_EXTRA = 400;

    /** The hover toolbar's buttons (phase 2): reply, copy, the full menu. */
    public enum Tool { REPLY, COPY, MORE }

    private ChatPanel() {
    }

    /** On an Eden server (it told the client its chat channels): this window, not vanilla's. */
    public static boolean active() {
        return ClientChatChannel.current() != null;
    }

    /** The rules changed (settings, channel): the lines are picked again on the next frame. */
    public static void invalidate() {
        ChatViews.invalidate();
    }

    public static void render(GuiGraphics graphics, int tickCount, int mouseX, int mouseY, boolean focused) {
        ChatViews.main().render(graphics, tickCount, mouseX, mouseY, focused);
    }

    /** The toolbar button under the mouse (screen coordinates), or null. */
    @Nullable
    public static Tool toolAt(double mouseX, double mouseY) {
        return ChatViews.main().toolAt(mouseX, mouseY);
    }

    /** The message the toolbar under the mouse belongs to. */
    @Nullable
    public static ChatEntry toolEntryAt(double mouseX, double mouseY) {
        return ChatViews.main().toolEntryAt(mouseX, mouseY);
    }

    /** The message under the mouse in the open chat (right-click: its menu). */
    @Nullable
    public static ChatEntry entryAt(double mouseX, double mouseY) {
        return ChatViews.main().entryAt(mouseX, mouseY);
    }

    /** The text style under the mouse: hovers and clicks (links, items, emojis, a channel tag). */
    @Nullable
    public static Style styleAt(double mouseX, double mouseY) {
        return ChatViews.main().styleAt(mouseX, mouseY);
    }

    /** The tag of the line whose stripe is under the mouse ("You were mentioned", vanilla's "Not Secure"…). */
    @Nullable
    public static GuiMessageTag tagAt(double mouseX, double mouseY) {
        return ChatViews.main().tagAt(mouseX, mouseY);
    }

    /** Whether the mouse is on the "N messages waiting" line (a click lets the next one through). */
    public static boolean queueLineAt(double mouseX, double mouseY) {
        return ChatViews.main().queueLineAt(mouseX, mouseY);
    }

    public static void scroll(int lines) {
        ChatViews.main().scroll(lines);
    }

    public static void resetScroll() {
        ChatViews.resetScroll();
    }

    /** Whether the main view shows the message now. */
    public static boolean shows(ChatEntry entry) {
        return ChatViews.main().showsNow(entry);
    }

    /** Scrolls the main view so the message sits in the middle of the open chat (when it shows it). */
    public static void scrollTo(ChatEntry entry) {
        ChatViews.main().scrollTo(entry);
    }
}
