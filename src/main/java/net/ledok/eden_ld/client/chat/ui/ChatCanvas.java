package net.ledok.eden_ld.client.chat.ui;

import net.ledok.eden_ld.chat.ChatChannels.Channel;
import net.ledok.eden_ld.client.chat.ChannelMenu;
import net.ledok.eden_ld.client.chat.GifViewer;
import net.ledok.eden_ld.client.chat.MessageMenu;
import net.ledok.eden_ld.client.chat.panel.ChatEntry;
import net.ledok.eden_ld.mixin.ChatScreenAccessor;
import net.ledok.vectorlib.client.canvas.Animation;
import net.ledok.vectorlib.client.canvas.CanvasGroup;
import net.ledok.vectorlib.client.canvas.CanvasNode;
import net.ledok.vectorlib.client.canvas.Easing;
import net.ledok.vectorlib.client.canvas.Interactive;
import net.ledok.vectorlib.client.canvas.VectorCanvas;
import net.ledok.vectorlib.client.canvas.layout.Sizing;
import net.ledok.vectorlib.client.canvas.widget.ContextMenu;
import net.ledok.vectorlib.client.canvas.widget.TextField;
import net.ledok.vectorlib.client.presentation.CanvasEmbed;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/**
 * The new look's VectorLib canvas on one chat screen ({@code docs/PLAN-chat-v2.md}, phase 4b), owned by its
 * {@link EdenChatUi}: the channel and message menus ({@link ChannelMenu}, {@link MessageMenu} as the canvas' popups)
 * and the emoji picker ({@link EmojiPicker}, a panel in its tree). It covers the screen down to the input row — (0, 0)
 * to (w, h-16): the pips, the emoji button and the input stay hand-drawn below it, so clicks there never reach it —
 * at z 300: over the history and its toolbar (≤ 120), the pills (150) and command suggestions (200), under the
 * screen's tooltips (400–550); the menus, lifted 300 more by VectorLib's popup layer, cover those too.
 * <p>
 * ChatScreenMixin hands it the screen's input first, and each call says whether the canvas took it: a menu takes
 * every click while it is open (off it, the click only closes it), the picker takes what lands on it, and the empty
 * canvas lets everything through. One Esc closes all menus, then the picker. The keyboard stays on the chat line
 * except while the picker's search has it ({@link #typing()}). With nothing on it the canvas costs nothing: it isn't
 * drawn or fed the pointer.
 */
public final class ChatCanvas {

    /** How far over the screen's drawing the canvas sits. */
    private static final float Z = 300;
    /** The input row under the canvas. */
    private static final int INPUT_ROW = 16;
    /** The picker's emojis a row in the chat. */
    private static final int COLUMNS = 10;
    /** Room kept over the picker at the top of the screen. */
    private static final float TOP_ROOM = 8;
    /** The picker's right edge in from the screen's: in line with the emoji button and the GIF viewer. */
    private static final float RIGHT = 4;
    /** How far the picker rises as it opens, and lifts as it closes. */
    private static final float RISE = 5;
    private static final float LIFT = 3;

    private final ChatScreen screen;
    private final VectorCanvas canvas = VectorCanvas.create(0, 0);
    private final CanvasEmbed embed = CanvasEmbed.of(canvas).z(Z);
    /** This screen's picker: a new chat screen starts with it closed. */
    private final EmojiPicker.State state = new EmojiPicker.State();
    private final EmojiPicker.Host host = new PickerHost();
    @Nullable
    private EmojiPicker.Panel panel;
    @Nullable
    private ChatTheme theme;
    private int width = -1;
    private int height = -1;
    /** The picker's place without its motion, and that as a GUI rect {x0, y0, x1, y1} (the history's covers). */
    private float pickerX;
    private float pickerY;
    private final int[] pickerRect = new int[4];
    /** The picker is rising into place: its animation places it. */
    private boolean opening;
    /** The last frame drew the canvas idle (one frame is drawn after it goes idle: the pointer shape resets). */
    private boolean idleDrawn = true;
    /** Where the pointer was last handed on; NaN hands it on next frame (the content under it changed). */
    private double lastX = Double.NaN;
    private double lastY = Double.NaN;

    public ChatCanvas(ChatScreen screen) {
        this.screen = screen;
    }

    // ---------------------------------------------------------------- frame

    /**
     * The start of every frame (render HEAD): the screen's size and the chat theme. A size or theme change closes the
     * menus (they were placed and colored at open) and places the picker again, in the new theme's colors.
     */
    public void sync(int screenWidth, int screenHeight, ChatTheme current) {
        if (current != theme) {
            boolean first = theme == null;
            theme = current;
            canvas.theme(current.canvas);
            if (!first) {
                closeMenus();
                restylePicker();
            }
        }
        if (screenWidth != width || screenHeight != height) {
            width = screenWidth;
            height = screenHeight;
            canvas.size(screenWidth, Math.max(0, screenHeight - INPUT_ROW));
            closeMenus();
            placePicker();
        }
        if (panel != null) {
            // A star, a saved sticker, the server's list: caught up here, so the picker is never placed mid-draw.
            panel.poll();
        }
    }

    /**
     * After the screen has drawn (render TAIL, after Eden's z+150 block): the pointer handed on (hover, a submenu's
     * rest, a drag) when it moved or something holds it, then the canvas and its tooltip.
     */
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (idle()) {
            if (idleDrawn) {
                return;
            }
            idleDrawn = true;
        } else {
            idleDrawn = false;
            if (mouseX != lastX || mouseY != lastY || embed.input().capturedNode() != null) {
                lastX = mouseX;
                lastY = mouseY;
                embed.mouseMoved(mouseX, mouseY);
            }
        }
        // Boxes drawn so far go out first (amendment 12).
        graphics.flush();
        embed.render(graphics, mouseX, mouseY, partialTick);
    }

    /** Nothing on the canvas: no picker, no menu, nothing lifting away or animating, nothing pressed. */
    private boolean idle() {
        return canvas.children().isEmpty() && !canvas.hasPopup() && canvas.overlays().children().isEmpty()
                && !canvas.animating() && embed.input().capturedNode() == null;
    }

    /** The content changed under the pointer: hand it on next frame even if it didn't move. */
    private void touched() {
        lastX = Double.NaN;
        lastY = Double.NaN;
    }

    /** The screen went (closed, or another one over it): menus, focus, a held press and the pointer shape go. */
    public void removed() {
        embed.input().cancelCapture();
        embed.removed();
        touched();
    }

    // ---------------------------------------------------------------- what the screen asks

    /** A channel or message menu is open. */
    public boolean menuOpen() {
        return canvas.hasPopup();
    }

    public boolean pickerOpen() {
        return panel != null;
    }

    /** The picker's place {x0, y0, x1, y1} (GUI units, without its motion), or null while it is closed. */
    public int @Nullable [] pickerRect() {
        return panel != null ? pickerRect : null;
    }

    /** The picker's search has the keyboard: keys and typed characters go there. */
    public boolean typing() {
        return embed.input().focusedNode() instanceof Interactive node && node.consumesTyping();
    }

    // ---------------------------------------------------------------- input

    /**
     * A key, before the screen's: Esc closes all menus, then the GIF viewer, then the picker (even from its search);
     * while the search has the keyboard every other key goes there (Enter picks), and none reaches the chat line.
     */
    public boolean keyPressed(int key, int scanCode, int modifiers) {
        if (key == GLFW.GLFW_KEY_ESCAPE && canvas.hasPopup()) {
            closeMenus();
            return true;
        }
        if (key == GLFW.GLFW_KEY_ESCAPE && GifViewer.isOpen()) {
            GifViewer.close();
            return true;
        }
        if (typing()) {
            if (key == GLFW.GLFW_KEY_ESCAPE) {
                closePicker();
            } else if (key != GLFW.GLFW_KEY_TAB) {
                // Tab stays put (the search is the picker's only field; with a menu open it would focus a row).
                embed.keyPressed(key, scanCode, modifiers);
            }
            touched();
            return true;
        }
        if (key == GLFW.GLFW_KEY_ESCAPE && panel != null) {
            closePicker();
            return true;
        }
        return false;
    }

    /** A typed character: the search's while it has the keyboard. */
    public boolean charTyped(char c, int modifiers) {
        if (!typing()) {
            return false;
        }
        embed.charTyped(c, modifiers);
        return true;
    }

    /**
     * A press, before the screen's: while a menu is open every click is the menu's (on it, it goes there; anywhere
     * else it only closes it); else the picker takes what lands on it. The empty canvas lets the click through.
     */
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (idle()) {
            return false;
        }
        float[] at = embed.toCanvas(mouseX, mouseY);
        CanvasGroup.Hit hit = canvas.pick(at[0], at[1]);
        if (canvas.hasPopup()) {
            // Not CanvasInput's own outside-click: it would close the menus and still hand the press to the picker.
            if (hit != null && hit.path().contains(canvas.popups())) {
                embed.mouseClicked(mouseX, mouseY, button);
            } else {
                closeMenus();
            }
            touched();
            return true;
        }
        EmojiPicker.Panel picker = panel;
        if (picker == null || hit == null || !hit.path().contains(picker)) {
            return false;
        }
        boolean wasTyping = typing();
        TextField search = state.search();
        boolean onSearch = search != null && hit.path().contains(search);
        embed.mouseClicked(mouseX, mouseY, button);
        // CanvasInput drops the focus after a press on what can't hold it (a cell, a tab, the list, the panel): the
        // search keeps the keyboard it had while the panel stays (a tab, a star, a Shift pick), as the 1.2.x picker.
        if (wasTyping && !onSearch && panel == picker && search != null && search.canvas() == canvas) {
            canvas.focus(search);
        }
        touched();
        return true;
    }

    /**
     * A release (never skipped: it ends a press the canvas took — a menu row fires on it, a scroll bar drag ends).
     * The keyboard goes back to the chat line unless the search has it: a picked row never takes a later Enter.
     */
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        boolean taken = embed.mouseReleased(mouseX, mouseY, button);
        if (!typing()) {
            embed.input().setFocus(null);
        }
        if (taken) {
            touched();
        }
        return taken;
    }

    /** The wheel over the picker or a menu scrolls it, never the chat behind it. */
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollY) {
        if (idle() || !embed.mouseScrolled(mouseX, mouseY, scrollY)) {
            return false;
        }
        // Other cells under a still pointer now.
        touched();
        return true;
    }

    // ---------------------------------------------------------------- menus

    /** A right-click on a channel's pip: its menu, opening upward from the pip ({@code pipX}: its left edge). */
    public void openChannelMenu(Channel channel, float pipX) {
        if (theme == null) {
            return;
        }
        closeMenus();
        // At the canvas' bottom: ContextMenu clamps it to end there, on the input row.
        ChatMenus.build(ChannelMenu.entries(ChannelMenu.spec(channel, theme.tokens.inkDim()),
                ChannelMenu.actions(channel))).open(canvas, pipX, canvas.height());
        touched();
    }

    /** A right-click on a message (or its toolbar's ⋯): its menu, ending at the pointer (below it near the top). */
    public void openMessageMenu(ChatEntry entry, double mouseX, double mouseY) {
        if (theme == null) {
            return;
        }
        closeMenus();
        List<ContextMenu.Entry> entries = MessageMenu.entries(MessageMenu.spec(entry, theme.tokens.inkDim()),
                MessageMenu.actions(entry));
        float menuHeight = Math.min(ChatMenus.height(entries), canvas.height());
        ChatMenus.build(entries).open(canvas, (float) mouseX, ChatMenus.top((float) mouseY, menuHeight));
        touched();
    }

    /** Closes every menu at once; a row held down never fires after that (Esc, a resize, a click elsewhere). */
    public void closeMenus() {
        if (canvas.hasPopup()) {
            embed.input().cancelCapture();
            canvas.closePopups();
            touched();
        }
    }

    // ---------------------------------------------------------------- the picker

    /** The emoji button. */
    public void togglePicker() {
        if (panel != null) {
            closePicker();
        } else {
            openPicker();
        }
    }

    /** Opens on the first tab with something in it, without a search; rises and fades in. */
    public void openPicker() {
        if (panel != null || theme == null) {
            return;
        }
        // A panel still lifting away from a close a moment ago goes at once.
        for (CanvasNode node : List.copyOf(canvas.overlays().children())) {
            if (node instanceof EmojiPicker.Panel old) {
                canvas.cancelAnimation(old);
                canvas.overlays().remove(old);
            }
        }
        EmojiPicker.opened(state);
        state.open = true;
        EmojiPicker.Panel picker = EmojiPicker.panel(state, host, theme.pickerLook, COLUMNS, 1);
        panel = picker;
        canvas.add(picker);
        opening = true;
        placePicker();
        // Keyed by the panel: its close cancels it and reuses the key (a quick reopen never strands a panel).
        canvas.animate(picker, Animation.of(150, Easing.OUT_CUBIC, t -> {
            picker.opacity(t);
            picker.at(pickerX, pickerY + RISE * (1 - t));
        }).onDone(() -> {
            if (panel == picker) {
                opening = false;
            }
        }));
        touched();
    }

    /** Closes the picker (a pick, Esc, the button, a click elsewhere): it lifts and fades away; typing goes back. */
    public void closePicker() {
        state.open = false;
        EmojiPicker.Panel picker = panel;
        if (picker == null) {
            return;
        }
        panel = null;
        opening = false;
        embed.input().setFocus(null);
        canvas.cancelAnimation(picker);
        if (Animation.enabled()) {
            float from = picker.opacity();
            float x = picker.transform.x;
            float y = picker.transform.y;
            // Drawn over the rest and never picked while it goes.
            canvas.overlays().add(picker);
            canvas.animate(picker, Animation.of(110, Easing.IN_CUBIC, t -> {
                picker.opacity(from * (1 - t));
                picker.at(x, y - LIFT * t);
            }).onDone(() -> canvas.overlays().remove(picker)));
        } else {
            picker.removeFromParent();
        }
        touched();
    }

    /** A theme change while the picker is open: the same state (tab, search, its focus) in the new colors. */
    private void restylePicker() {
        EmojiPicker.Panel old = panel;
        if (old == null || theme == null) {
            return;
        }
        canvas.cancelAnimation(old);
        opening = false;
        // Built before the old one goes: it reads whether the old search had the keyboard.
        EmojiPicker.Panel picker = EmojiPicker.panel(state, host, theme.pickerLook, COLUMNS, 1);
        old.removeFromParent();
        panel = picker;
        canvas.add(picker);
        placePicker();
        touched();
    }

    /**
     * Right-aligned with the emoji button, its bottom on the input row, as tall as its content wants (at most the
     * canvas less a little room). Runs again when the content changes (a tab, a search) and on a resize.
     */
    private void placePicker() {
        EmojiPicker.Panel picker = panel;
        if (picker == null) {
            return;
        }
        float w = EmojiPicker.width(COLUMNS);
        float h = picker.preferredHeight(Math.max(0, canvas.height() - TOP_ROOM));
        picker.sizing(Sizing.fixed(w), Sizing.fixed(h));
        picker.layoutIn(w, h);
        pickerX = canvas.width() - RIGHT - w;
        pickerY = canvas.height() - h;
        if (!opening) {
            picker.at(pickerX, pickerY);
        }
        pickerRect[0] = (int) Math.floor(pickerX);
        pickerRect[1] = (int) Math.floor(pickerY);
        pickerRect[2] = (int) Math.ceil(pickerX + w);
        pickerRect[3] = (int) Math.ceil(pickerY + h);
        touched();
    }

    /** The picker in the chat: picks go to the chat line's caret, full lists to the action bar. */
    private final class PickerHost implements EmojiPicker.Host {

        @Override
        public void insert(String text) {
            ((ChatScreenAccessor) screen).eden_ld$insertText(text, false);
        }

        @Override
        public void close() {
            closePicker();
        }

        @Override
        public void notice(Component message) {
            // Over the input, not a toast: toasts would stack over the line being typed.
            if (Minecraft.getInstance().player != null) {
                Minecraft.getInstance().player.displayClientMessage(message, true);
            }
        }

        @Override
        public void focusInput() {
            // Shift keeps picking: a search that had the keyboard keeps it (after a click, mouseClicked gives it
            // back once CanvasInput has dropped it). Else typing goes back to the chat line.
            TextField search = state.search();
            if (Screen.hasShiftDown() && search != null && search.isFocused()) {
                return;
            }
            embed.input().setFocus(null);
        }

        @Override
        public void resized() {
            placePicker();
        }
    }
}
