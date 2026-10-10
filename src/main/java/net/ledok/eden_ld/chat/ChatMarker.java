package net.ledok.eden_ld.chat;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * What kind of line a chat message is — a channel's, Discord's, a death, an advancement —
 * carried inside the message as an empty, invisible piece of text (its insertion holds the
 * kind). Every player gets every line; Eden's client hides the kinds the player turned off
 * (Settings → Chat) and shows them again when turned back on. Other clients see nothing.
 */
public final class ChatMarker {

    private static final String PREFIX = "eden_ld:line:";
    private static final String TEAM = ":team";
    /** How deep {@link #find} looks: a chat line nests the marker in its sender part, a few levels down. */
    private static final int MAX_DEPTH = 6;

    public enum Kind {
        GLOBAL, LOCAL, PARTY, GUILD, DISCORD, DEATH, ADVANCEMENT;

        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * A line's kind; {@code team}: about the reader, their party or their guild (deaths,
     * advancements); {@code sender}: who wrote it (chat lines — a player's own always show).
     */
    public record Mark(Kind kind, boolean team, @Nullable java.util.UUID sender) {
    }

    private ChatMarker() {
    }

    /** The invisible piece marking a line (put it anywhere in the line). */
    public static MutableComponent marker(Kind kind, boolean team) {
        return marker(kind, team, null);
    }

    /** As {@link #marker(Kind, boolean)}, for a player's chat line (their own client always shows it). */
    public static MutableComponent marker(Kind kind, boolean team, @Nullable java.util.UUID sender) {
        return Component.literal("").withStyle(Style.EMPTY.withInsertion(PREFIX + kind.id() + (team ? TEAM : "")
                + (sender != null ? "@" + sender : "")));
    }

    /** The line with its marker in front. */
    public static MutableComponent mark(Component line, Kind kind, boolean team) {
        return mark(line, kind, team, null);
    }

    /** As {@link #mark(Component, Kind, boolean)}, with who the line is from or about (deaths, advancements). */
    public static MutableComponent mark(Component line, Kind kind, boolean team, @Nullable java.util.UUID sender) {
        return Component.empty().append(marker(kind, team, sender)).append(line);
    }

    /** The marker in a line (in its parts and translation arguments), or null for unmarked lines. */
    @Nullable
    public static Mark find(Component line) {
        return find(line, 0);
    }

    @Nullable
    private static Mark find(Component component, int depth) {
        if (depth > MAX_DEPTH) {
            return null;
        }
        String insertion = component.getStyle().getInsertion();
        if (insertion != null && insertion.startsWith(PREFIX) && component.getString().isEmpty()) {
            String value = insertion.substring(PREFIX.length());
            java.util.UUID sender = null;
            int at = value.indexOf('@');
            if (at >= 0) {
                try {
                    sender = java.util.UUID.fromString(value.substring(at + 1));
                } catch (IllegalArgumentException ignored) {
                    // Not a uuid: no sender.
                }
                value = value.substring(0, at);
            }
            boolean team = value.endsWith(TEAM);
            String id = team ? value.substring(0, value.length() - TEAM.length()) : value;
            for (Kind kind : Kind.values()) {
                if (kind.id().equals(id)) {
                    return new Mark(kind, team, sender);
                }
            }
        }
        if (component.getContents() instanceof TranslatableContents translatable) {
            for (Object arg : translatable.getArgs()) {
                if (arg instanceof Component part) {
                    Mark mark = find(part, depth + 1);
                    if (mark != null) {
                        return mark;
                    }
                }
            }
        }
        for (Component sibling : component.getSiblings()) {
            Mark mark = find(sibling, depth + 1);
            if (mark != null) {
                return mark;
            }
        }
        return null;
    }
}
