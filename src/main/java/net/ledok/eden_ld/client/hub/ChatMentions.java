package net.ledok.eden_ld.client.hub;

import net.ledok.vectorlib.client.canvas.widget.TextField;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * "@name" mentions in the hub's party and guild chat: Tab completes the "@part" at the end of
 * the message from that chat's members (Tab again cycles through the matches), and clicking a
 * name in the chat adds "@name " to the message.
 */
final class ChatMentions {

    /** The chat field on screen and who can be mentioned there. */
    @Nullable
    private static TextField field;
    private static Supplier<List<String>> names = List::of;

    /** The text before the completion, the matches, and which one is in the field now. */
    @Nullable
    private static String base;
    private static List<String> matches = List.of();
    private static int index;
    @Nullable
    private static String completed;

    private ChatMentions() {
    }

    /** The chat field a tab just built (a rebuild makes a new one). */
    static void target(TextField chatField, Supplier<List<String>> members) {
        field = chatField;
        names = members;
    }

    /** Tab in the hub: completes when the chat field has the focus. */
    static boolean tab() {
        TextField target = field;
        if (target == null || !target.isFocused()) {
            return false;
        }
        String text = target.text();
        if (completed == null || !text.equals(completed)) {
            // A fresh completion: the "@part" at the end of the message.
            int at = text.lastIndexOf('@');
            if (at < 0 || text.substring(at).contains(" ") || at > 0 && !Character.isWhitespace(text.charAt(at - 1))) {
                return true;
            }
            String part = text.substring(at + 1).toLowerCase(Locale.ROOT);
            matches = new ArrayList<>();
            for (String name : names.get()) {
                if (name.toLowerCase(Locale.ROOT).startsWith(part)) {
                    matches.add(name);
                }
            }
            if (matches.isEmpty()) {
                return true;
            }
            base = text.substring(0, at);
            index = 0;
        } else {
            index = (index + 1) % matches.size();
        }
        // text() keeps the caret clamped to the end of the shorter text; insert() then appends.
        target.text(base);
        target.insert("@" + matches.get(index) + " ");
        completed = target.text();
        return true;
    }

    /** A clicked name: "@name " added to the message, with a space before it when needed. */
    static void mention(String mention) {
        TextField target = field;
        if (target == null) {
            return;
        }
        String text = target.text();
        target.text(text.isEmpty() || text.endsWith(" ") ? text : text + " ");
        target.insert(mention);
        completed = null;
        if (net.minecraft.client.Minecraft.getInstance().screen instanceof HubScreen screen) {
            screen.canvas().focus(target);
        }
    }
}
