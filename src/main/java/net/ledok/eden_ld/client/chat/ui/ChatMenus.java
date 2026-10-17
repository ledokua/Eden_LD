package net.ledok.eden_ld.client.chat.ui;

import net.ledok.eden_ld.client.chat.ChannelMenu;
import net.ledok.eden_ld.client.chat.MessageMenu;
import net.ledok.vectorlib.client.canvas.widget.ContextMenu;

import java.util.List;

/**
 * The new look's menus ({@code docs/PLAN-chat-v2.md}, phase 4b): {@link ChannelMenu} and {@link MessageMenu} as
 * VectorLib {@link ContextMenu}s on the chat's canvas ({@link ChatCanvas}). Builds one from its rows, says how tall it
 * is (a message's menu opens upward from the pointer, and ContextMenu only clamps), and answers "is a menu open?" in
 * either look.
 */
public final class ChatMenus {

    /*
     * ContextMenu's sizes, private there: a row, a separator, a header, and the plate's 2-unit inset above and below
     * the rows. ChatMenusTest reads VectorLib's own by reflection and fails when they drift.
     */
    static final float ROW_H = 14;
    static final float SEPARATOR_H = 5;
    static final float HEADER_H = 12;
    static final float PAD = 4;
    /**
     * How far above the pointer a message's menu ends: it rises 4 into place, so it starts with the pointer in its
     * 2-unit bottom inset (not on the last row) and ends just clear of it.
     */
    static final float ABOVE = 2;

    private ChatMenus() {
    }

    /** A menu of these rows, in the canvas' theme. */
    public static ContextMenu build(List<ContextMenu.Entry> entries) {
        ContextMenu menu = ContextMenu.create();
        for (ContextMenu.Entry entry : entries) {
            menu.entry(entry);
        }
        return menu;
    }

    /** How tall a menu of these rows is (before the canvas caps it). */
    public static float height(List<ContextMenu.Entry> entries) {
        float height = PAD;
        for (ContextMenu.Entry entry : entries) {
            height += switch (entry) {
                case ContextMenu.Separator separator -> SEPARATOR_H;
                case ContextMenu.Header header -> HEADER_H;
                default -> ROW_H;
            };
        }
        return height;
    }

    /** The top of a menu {@code height} tall opened at the pointer's {@code y}: above it when it fits, else at it. */
    public static float top(float y, float height) {
        float above = y - height - ABOVE;
        return above >= 0 ? above : y;
    }

    /**
     * The top of a menu {@code height} tall opened from something {@code anchorTop}..{@code anchorBottom} tall (a tab:
     * its chip; the input glyph: the canvas' bottom), on a canvas {@code limit} tall: 1 below it when it fits, else
     * ending 1 above it (at the top at the most).
     */
    public static float below(float anchorTop, float anchorBottom, float height, float limit) {
        float under = anchorBottom + 1;
        return under + height <= limit ? under : Math.max(0, anchorTop - 1 - height);
    }

    /** Whether a channel or message menu is open on the chat screen showing (either look). */
    public static boolean open() {
        EdenChatUi ui = ChatLook.open();
        return ui != null ? ui.canvas().menuOpen() : ChannelMenu.isOpen() || MessageMenu.isOpen();
    }
}
