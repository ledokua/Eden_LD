package net.ledok.eden_ld.client.chat.panel;

import net.ledok.eden_ld.chat.ChatChannels.Channel;
import net.ledok.eden_ld.chat.ChatMarker;
import net.ledok.eden_ld.client.chat.model.ChatCounters;
import net.minecraft.client.GuiMessage;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Every chat message since joining, oldest first — Eden's own copy of what vanilla's chat receives (vanilla keeps its
 * 100 for itself, the log and other mods). The chat views draw from here; channel views are filters over the one list,
 * so switching channel never re-lays anything out. {@link #version} changes whenever the list does,
 * {@link #seenEpoch} whenever a line is seen (or new again).
 */
public final class ChatStore {

    /** How many messages are kept (vanilla: 100, shared by every channel). */
    public static final int LIMIT = 1000;

    private static final List<ChatEntry> ENTRIES = new ArrayList<>();
    private static int version;
    private static int seenEpoch;
    @Nullable
    private static ChatCounters counters;

    private ChatStore() {
    }

    public static List<ChatEntry> entries() {
        return Collections.unmodifiableList(ENTRIES);
    }

    public static int version() {
        return version;
    }

    public static int seenEpoch() {
        return seenEpoch;
    }

    static void seenChanged() {
        seenEpoch++;
    }

    /** What isn't seen yet, per channel (counted again only after a change). */
    public static ChatCounters counters() {
        counters = ChatCounters.of(counters, ENTRIES, version, seenEpoch);
        return counters;
    }

    public static void clear() {
        ENTRIES.clear();
        version++;
    }

    /** The messages as they are now, to put back after a reconfiguration ({@link #restore}). */
    public record Snapshot(List<ChatEntry> entries) {
    }

    public static Snapshot snapshot() {
        return new Snapshot(List.copyOf(ENTRIES));
    }

    /** The messages of a snapshot, in place of what's here (as vanilla puts its own back). */
    public static void restore(Snapshot snapshot) {
        ENTRIES.clear();
        ENTRIES.addAll(snapshot.entries());
        version++;
    }

    /** What became of a message: a new entry, or one more on the last one's count ({@code repeat}). */
    public record Added(ChatEntry entry, boolean repeat) {
    }

    /**
     * A message vanilla's chat just took in. {@code self}: the player (their own lines count as seen), {@code ignored}:
     * whose lines there's nothing to see in.
     */
    public static Added add(GuiMessage message, boolean mention, @Nullable UUID self, Predicate<UUID> ignored) {
        ChatMarker.Mark mark = ChatMarker.find(message.content());
        // Spam protection: the very same line again (same sender, same text) counts on the last one.
        if (!ENTRIES.isEmpty()) {
            ChatEntry last = ENTRIES.get(ENTRIES.size() - 1);
            if (sameSender(last.mark, mark) && last.message.content().getString().equals(message.content().getString())) {
                last.repeat(message);
                version++;
                return new Added(last, true);
            }
        }
        boolean own = mark != null && self != null && self.equals(mark.sender());
        ChatEntry entry = new ChatEntry(message, mark, channelOf(mark), own, mention && !own);
        // Nothing to see in one's own lines or an ignored player's.
        entry.seen(own || mark != null && mark.sender() != null && ignored.test(mark.sender()));
        ENTRIES.add(entry);
        if (ENTRIES.size() > LIMIT) {
            ENTRIES.remove(0);
        }
        version++;
        return new Added(entry, false);
    }

    private static boolean sameSender(@Nullable ChatMarker.Mark a, @Nullable ChatMarker.Mark b) {
        if (a == null || b == null) {
            return a == b;
        }
        return a.kind() == b.kind() && java.util.Objects.equals(a.sender(), b.sender());
    }

    /** Vanilla swapped a message for another (the server deleted it): the copy follows. */
    public static void replace(GuiMessage original, GuiMessage replacement) {
        for (ChatEntry entry : ENTRIES) {
            if (entry.message == original) {
                entry.replace(replacement);
                version++;
                return;
            }
        }
    }

    @Nullable
    private static Channel channelOf(@Nullable ChatMarker.Mark mark) {
        if (mark == null) {
            return null;
        }
        return switch (mark.kind()) {
            case GLOBAL, DISCORD -> Channel.GLOBAL;
            case LOCAL -> Channel.LOCAL;
            case PARTY -> Channel.PARTY;
            case GUILD -> Channel.GUILD;
            default -> null;
        };
    }
}
