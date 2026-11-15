package net.ledok.eden_ld.chat.dm;

import org.jetbrains.annotations.Nullable;

import java.util.Deque;
import java.util.UUID;
import java.util.function.ToLongFunction;

/**
 * The rules of private conversations ({@link DirectMessages}) as plain code: conversation ids, how much history stays,
 * when a conversation expires, what a group name may be.
 */
public final class DmRules {

    /** Lines kept per conversation. */
    public static final int KEEP = 100;
    /** How long a line is kept. */
    public static final long KEEP_MS = 7L * 24 * 60 * 60 * 1000;
    /** A group with nothing happening this long goes (its lines went long before). */
    public static final long GROUP_IDLE_MS = 30L * 24 * 60 * 60 * 1000;
    /** A group's members at most (the client's form says the same). */
    public static final int MAX_MEMBERS = 10;
    /** Group chats a player may be in at once. */
    public static final int MAX_GROUPS = 20;
    /** A group name's length at most. */
    public static final int NAME_MAX = 24;
    /** The whisper partner complaints never match ({@code ChatRecall}): group lines stay out of complaints. */
    public static final String GROUP_PARTNER = "#group";

    private static final String DM = "dm:";
    private static final String GROUP = "g:";

    private DmRules() {
    }

    /** The DM between two players: the same id whoever writes first. */
    public static String dmKey(UUID a, UUID b) {
        String x = a.toString();
        String y = b.toString();
        return x.compareTo(y) <= 0 ? DM + x + ":" + y : DM + y + ":" + x;
    }

    /** A new group's id from a random number. */
    public static String groupKey(long random) {
        return GROUP + Long.toHexString(random);
    }

    public static boolean isDm(String id) {
        return id.startsWith(DM);
    }

    /**
     * Drops the lines older than {@link #KEEP_MS} and those past {@link #KEEP} from the front (oldest first) of
     * {@code lines}; returns how many went.
     */
    public static <T> int trim(Deque<T> lines, ToLongFunction<T> time, long now) {
        int dropped = 0;
        while (!lines.isEmpty() && (lines.size() > KEEP || now - time.applyAsLong(lines.peekFirst()) > KEEP_MS)) {
            lines.removeFirst();
            dropped++;
        }
        return dropped;
    }

    /**
     * Whether a conversation goes: a DM once its lines are gone and nothing happened in it for {@link #KEEP_MS}, a
     * group after {@link #GROUP_IDLE_MS} of nothing, or with nobody left.
     */
    public static boolean expired(boolean group, int lines, int members, long touched, long now) {
        if (members == 0) {
            return true;
        }
        return group ? now - touched > GROUP_IDLE_MS : lines == 0 && now - touched > KEEP_MS;
    }

    /**
     * A group name as typed, made safe: no formatting codes or control characters, spaces collapsed, at most
     * {@link #NAME_MAX} characters; null when nothing is left.
     */
    @Nullable
    public static String cleanName(@Nullable String raw) {
        if (raw == null) {
            return null;
        }
        StringBuilder out = new StringBuilder();
        boolean space = false;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '§') {
                // A formatting code: the sign and its letter.
                i++;
                continue;
            }
            if (Character.isISOControl(c)) {
                continue;
            }
            if (Character.isWhitespace(c)) {
                space = out.length() > 0;
                continue;
            }
            if (space) {
                out.append(' ');
                space = false;
            }
            out.append(c);
        }
        String name = out.length() > NAME_MAX ? out.substring(0, NAME_MAX).trim() : out.toString();
        return name.isEmpty() ? null : name;
    }
}
