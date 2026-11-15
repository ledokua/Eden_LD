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
    private static final String MENTION = "eden_ld:mention:";
    private static final String TEAM = ":team";
    /** A second invisible piece on deaths, advancements and joins: "p" about the reader's party, "g" their guild. */
    private static final String CIRCLE = "eden_ld:circle:";
    /** A second invisible piece on private lines ({@link Kind#DM}): the conversation's id. */
    private static final String CONVERSATION = "eden_ld:conv:";
    /** How deep {@link #find} looks: a chat line nests the marker in its sender part, a few levels down. */
    private static final int MAX_DEPTH = 6;

    public enum Kind {
        /**
         * JOIN: joining or leaving the server; clients before 1.3.42 don't know it and read the line as unmarked. DM
         * (1.3.94): a line of a private conversation (a DM or a group chat), its id beside the marker; older clients
         * read it as unmarked.
         */
        GLOBAL, LOCAL, PARTY, GUILD, DISCORD, DEATH, ADVANCEMENT, JOIN, DM;

        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * A line's kind; {@code team}: about the reader, their party or their guild (deaths,
     * advancements); {@code sender}: who wrote it (chat lines — a player's own always show); {@code party} /
     * {@code guild}: a death, advancement or join about the reader or a member of their party / guild (the Party and
     * Guild tabs show only those). A line without the circle piece (an older server) counts as {@code team} for both.
     * {@code conversation}: a {@link Kind#DM} line's conversation (null on every other kind).
     */
    public record Mark(Kind kind, boolean team, @Nullable java.util.UUID sender, boolean party, boolean guild,
                       @Nullable String conversation) {

        public Mark(Kind kind, boolean team, @Nullable java.util.UUID sender, boolean party, boolean guild) {
            this(kind, team, sender, party, guild, null);
        }

        public Mark(Kind kind, boolean team, @Nullable java.util.UUID sender) {
            this(kind, team, sender, team, team);
        }
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

    /**
     * The invisible piece after an "@name" mention, naming who was mentioned: that player's client highlights the
     * line and counts it on the channel's pip. Empty, so shift-click inserts nothing.
     */
    public static MutableComponent mention(java.util.UUID mentioned) {
        return Component.literal("").withStyle(Style.EMPTY.withInsertion(MENTION + mentioned));
    }

    /** Whether the line mentions this player (a {@link #mention} piece anywhere in it). */
    public static boolean mentions(Component line, java.util.UUID player) {
        return mentions(line, MENTION + player, 0);
    }

    private static boolean mentions(Component component, String insertion, int depth) {
        if (depth > MAX_DEPTH + 2) {
            return false;
        }
        if (insertion.equals(component.getStyle().getInsertion()) && component.getString().isEmpty()) {
            return true;
        }
        if (component.getContents() instanceof TranslatableContents translatable) {
            for (Object arg : translatable.getArgs()) {
                if (arg instanceof Component part && mentions(part, insertion, depth + 1)) {
                    return true;
                }
            }
        }
        for (Component sibling : component.getSiblings()) {
            if (mentions(sibling, insertion, depth + 1)) {
                return true;
            }
        }
        return false;
    }

    /** The line with its marker in front. */
    public static MutableComponent mark(Component line, Kind kind, boolean team) {
        return mark(line, kind, team, null);
    }

    /** As {@link #mark(Component, Kind, boolean)}, with who the line is from or about (deaths, advancements). */
    public static MutableComponent mark(Component line, Kind kind, boolean team, @Nullable java.util.UUID sender) {
        return Component.empty().append(marker(kind, team, sender)).append(line);
    }

    /**
     * As {@link #mark(Component, Kind, boolean, java.util.UUID)} for a death, advancement or join, saying whether it is
     * about the reader's party and guild ({@code team} = either).
     */
    public static MutableComponent mark(Component line, Kind kind, @Nullable java.util.UUID subject, boolean party,
                                        boolean guild) {
        return Component.empty().append(marker(kind, party || guild, subject))
                .append(Component.literal("").withStyle(Style.EMPTY.withInsertion(
                        CIRCLE + (party ? "p" : "") + (guild ? "g" : ""))))
                .append(line);
    }

    /**
     * A line of the private conversation {@code conversation} written by {@code sender}: the DM marker and the
     * conversation's id in front (Eden's client files it under that conversation's tab). A null sender: the server's
     * note in the conversation (someone joined, a message refused).
     */
    public static MutableComponent markDm(Component line, String conversation, @Nullable java.util.UUID sender) {
        return Component.empty().append(marker(Kind.DM, false, sender))
                .append(Component.literal("").withStyle(Style.EMPTY.withInsertion(CONVERSATION + conversation)))
                .append(line);
    }

    /** The marker in a line (in its parts and translation arguments), or null for unmarked lines. */
    @Nullable
    public static Mark find(Component line) {
        Mark mark = find(line, 0);
        if (mark != null && mark.kind() == Kind.DM) {
            // The conversation piece sits beside the marker, at the top of the line.
            for (Component sibling : line.getSiblings()) {
                String insertion = sibling.getStyle().getInsertion();
                if (insertion != null && insertion.startsWith(CONVERSATION) && sibling.getString().isEmpty()) {
                    return new Mark(Kind.DM, false, mark.sender(), false, false,
                            insertion.substring(CONVERSATION.length()));
                }
            }
            return mark;
        }
        if (mark == null || !(mark.kind() == Kind.DEATH || mark.kind() == Kind.ADVANCEMENT || mark.kind() == Kind.JOIN)) {
            return mark;
        }
        // The circle piece sits beside the marker, at the top of the line.
        for (Component sibling : line.getSiblings()) {
            String insertion = sibling.getStyle().getInsertion();
            if (insertion != null && insertion.startsWith(CIRCLE) && sibling.getString().isEmpty()) {
                String circle = insertion.substring(CIRCLE.length());
                return new Mark(mark.kind(), mark.team(), mark.sender(), circle.contains("p"), circle.contains("g"));
            }
        }
        return mark;
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
