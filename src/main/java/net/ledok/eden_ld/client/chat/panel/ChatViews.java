package net.ledok.eden_ld.client.chat.panel;

import net.ledok.eden_ld.client.chat.model.ChatWindow;

import java.util.List;

/**
 * The chat windows' views. The main one belongs to no screen: vanilla's chat screen, the bed's and any other
 * {@code ChatScreen} all show it, and the HUD draws it while chat is closed. For now it is the only one.
 */
public final class ChatViews {

    private static final ChatView MAIN = new ChatView(ChatWindow.MAIN);
    private static final List<ChatView> ALL = List.of(MAIN);
    /** Bumped when the filters' inputs change outside the settings (the ignore list, the System view…). */
    private static int rulesEpoch;

    private ChatViews() {
    }

    public static ChatView main() {
        return MAIN;
    }

    public static List<ChatView> all() {
        return ALL;
    }

    static int rulesEpoch() {
        return rulesEpoch;
    }

    /** The filters changed: every view picks its lines again on the next frame. */
    public static void invalidate() {
        rulesEpoch++;
    }

    /** A new message: views scrolled up hold still. */
    public static void added(ChatEntry entry) {
        for (ChatView view : ALL) {
            view.added(entry);
        }
    }

    /** Every view back to its newest line. */
    public static void resetScroll() {
        for (ChatView view : ALL) {
            view.resetScroll();
        }
    }
}
