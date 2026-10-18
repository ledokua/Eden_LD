package net.ledok.eden_ld.client.chat.panel;

import net.ledok.eden_ld.chat.ChatChannels.Channel;
import net.ledok.eden_ld.chat.ChatMarker;
import net.ledok.eden_ld.chat.SystemOnlyRules;
import net.ledok.eden_ld.client.chat.model.ChatCounters;
import net.minecraft.client.GuiMessage;
import net.minecraft.network.chat.Component;
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
    /**
     * How many of them may be system-only ({@link ChatEntry#systemOnly()}), the oldest going first: out of All, a
     * flood of other admins' command echoes would otherwise push the chat's history out unseen.
     */
    public static final int SYSTEM_ONLY_LIMIT = 200;

    private static final List<ChatEntry> ENTRIES = new ArrayList<>();
    private static int version;
    private static int seenEpoch;
    @Nullable
    private static ChatCounters counters;
    /** Which unmarked lines are system-only: Eden's own rules until the server sends its own (ChatSystemOnly). */
    private static SystemOnlyRules rules = SystemOnlyRules.BUILT_IN;
    /** How many entries are system-only. */
    private static int systemOnlyCount;
    /**
     * Whether {@link #SYSTEM_ONLY_LIMIT} applies: only where the lines live in the System tab alone (Eden's new look;
     * the client sets it). The 1.2 look shows them in its normal view and keeps its whole history, as before.
     */
    private static java.util.function.BooleanSupplier capSystemOnly = () -> false;

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
        systemOnlyCount = 0;
        version++;
    }

    /** Which unmarked lines show in the System tab alone. */
    public static SystemOnlyRules rules() {
        return rules;
    }

    /**
     * New system-only rules (joining a server, the server's own list, its /reload): every unmarked line is read again
     * under them — nothing happens when they're the same. A line that becomes system-only counts as seen; one that
     * stops being so stays as seen as it was, so no dots come back.
     */
    public static void rules(SystemOnlyRules next) {
        if (next.equals(rules)) {
            return;
        }
        rules = next;
        for (ChatEntry entry : ENTRIES) {
            if (entry.mark != null) {
                continue;
            }
            Component content = entry.message.content();
            boolean systemOnly = next.matches(content, content.getString());
            if (systemOnly != entry.systemOnly) {
                entry.systemOnly = systemOnly;
                if (systemOnly) {
                    entry.seen(true);
                }
            }
        }
        countSystemOnly();
        trimSystemOnly();
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
        countSystemOnly();
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
        Component content = message.content();
        ChatMarker.Mark mark = ChatMarker.find(content);
        String text = content.getString();
        // Other admins' command echoes and routine notices: the System tab's alone (new look), read once, here.
        boolean systemOnly = mark == null && rules.matches(content, text);
        // Spam protection: the very same line again (same sender, same text, filed alike) counts on the last one.
        if (!ENTRIES.isEmpty()) {
            ChatEntry last = ENTRIES.get(ENTRIES.size() - 1);
            if (last.systemOnly == systemOnly && sameSender(last.mark, mark)
                    && last.message.content().getString().equals(text)) {
                last.repeat(message);
                version++;
                return new Added(last, true);
            }
        }
        boolean own = mark != null && self != null && self.equals(mark.sender());
        ChatEntry entry = new ChatEntry(message, mark, channelOf(mark), own, mention && !own, systemOnly);
        // Nothing to see in one's own lines, an ignored player's or system-only ones (no dot, no count).
        entry.seen(own || systemOnly || mark != null && mark.sender() != null && ignored.test(mark.sender()));
        ENTRIES.add(entry);
        if (systemOnly) {
            systemOnlyCount++;
        }
        if (ENTRIES.size() > LIMIT && ENTRIES.remove(0).systemOnly) {
            systemOnlyCount--;
        }
        trimSystemOnly();
        version++;
        return new Added(entry, false);
    }

    private static void countSystemOnly() {
        int count = 0;
        for (ChatEntry entry : ENTRIES) {
            if (entry.systemOnly) {
                count++;
            }
        }
        systemOnlyCount = count;
    }

    /** Whether the system-only cap applies now (the client passes "the new look is on"). */
    public static void capSystemOnly(java.util.function.BooleanSupplier when) {
        capSystemOnly = when;
    }

    /** The oldest system-only lines go past {@link #SYSTEM_ONLY_LIMIT} (the scroll counts from the newest line). */
    private static void trimSystemOnly() {
        if (!capSystemOnly.getAsBoolean()) {
            return;
        }
        for (int i = 0; systemOnlyCount > SYSTEM_ONLY_LIMIT && i < ENTRIES.size(); ) {
            if (ENTRIES.get(i).systemOnly) {
                ENTRIES.remove(i);
                systemOnlyCount--;
            } else {
                i++;
            }
        }
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
