package net.ledok.eden_ld.client.chat.panel;

import net.ledok.eden_ld.chat.ChatChannels.Channel;
import net.ledok.eden_ld.chat.ChatMarker;
import net.ledok.eden_ld.client.chat.model.ChatCounters;
import net.minecraft.client.GuiMessage;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatStoreTest {

    private static final UUID ME = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID TROLL = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final Predicate<UUID> IGNORED = Set.of(TROLL)::contains;

    @BeforeEach
    void empty() {
        ChatStore.clear();
    }

    private static GuiMessage line(ChatMarker.Kind kind, UUID sender, String text) {
        return new GuiMessage(0, ChatMarker.mark(Component.literal(text), kind, false, sender), null, null);
    }

    private static ChatEntry add(GuiMessage message, boolean mention) {
        return ChatStore.add(message, mention, ME, IGNORED).entry();
    }

    @Test
    void ownAndIgnoredLinesNeedNoSeeing() {
        assertTrue(add(line(ChatMarker.Kind.GLOBAL, ME, "mine"), false).seen());
        assertTrue(add(line(ChatMarker.Kind.GLOBAL, TROLL, "spam"), false).seen());
        ChatEntry bob = add(line(ChatMarker.Kind.LOCAL, BOB, "hi"), false);
        assertFalse(bob.seen());
        assertEquals(Channel.LOCAL, bob.channel());
        // One's own line never counts as a mention.
        assertFalse(add(line(ChatMarker.Kind.GLOBAL, ME, "@me"), true).mention());
    }

    @Test
    void theSameLineAgainCountsOnTheLastOneAndIsNewAgain() {
        ChatEntry first = add(line(ChatMarker.Kind.GLOBAL, BOB, "lol"), false);
        first.markSeen();
        ChatStore.Added again = ChatStore.add(line(ChatMarker.Kind.GLOBAL, BOB, "lol"), false, ME, IGNORED);
        assertTrue(again.repeat());
        assertSame(first, again.entry());
        assertEquals(2, first.count());
        assertFalse(first.seen());
        assertEquals(1, ChatStore.entries().size());
        // Someone else saying it is a new line.
        assertFalse(ChatStore.add(line(ChatMarker.Kind.GLOBAL, ME, "lol"), false, ME, IGNORED).repeat());
    }

    @Test
    void theOldestLinesGoPastTheLimit() {
        for (int i = 0; i < ChatStore.LIMIT + 5; i++) {
            add(line(ChatMarker.Kind.GLOBAL, BOB, "line " + i), false);
        }
        assertEquals(ChatStore.LIMIT, ChatStore.entries().size());
        assertEquals("line 5", ChatStore.entries().get(0).message().content().getString());
    }

    @Test
    void countersFollowSeenLinesAndStayCachedOtherwise() {
        add(line(ChatMarker.Kind.GLOBAL, BOB, "a"), false);
        ChatEntry mention = add(line(ChatMarker.Kind.GUILD, BOB, "@me b"), true);
        add(new GuiMessage(0, Component.literal("Server restarts soon"), null, null), false);
        add(line(ChatMarker.Kind.DEATH, BOB, "Bob fell"), false);
        ChatCounters counters = ChatStore.counters();
        assertEquals(1, counters.unseen(Channel.GLOBAL));
        assertEquals(1, counters.unseen(Channel.GUILD));
        assertEquals(1, counters.mentions(Channel.GUILD));
        assertEquals(0, counters.mentions(Channel.GLOBAL));
        assertEquals(2, counters.unseenSystem());
        assertSame(counters, ChatStore.counters());

        mention.markSeen();
        ChatCounters after = ChatStore.counters();
        assertNotSame(counters, after);
        assertEquals(0, after.unseen(Channel.GUILD));
        assertEquals(0, after.mentions(Channel.GUILD));
        // Seen again: nothing changed, nothing counted again.
        mention.markSeen();
        assertSame(after, ChatStore.counters());
    }

    @Test
    void aSnapshotComesBackAsItWas() {
        ChatEntry seen = add(line(ChatMarker.Kind.GLOBAL, BOB, "before"), false);
        seen.markSeen();
        ChatEntry unseen = add(line(ChatMarker.Kind.PARTY, BOB, "party"), false);
        ChatStore.Snapshot snapshot = ChatStore.snapshot();
        ChatStore.clear();
        assertTrue(ChatStore.entries().isEmpty());
        int version = ChatStore.version();
        ChatStore.restore(snapshot);
        assertTrue(ChatStore.version() != version);
        assertEquals(2, ChatStore.entries().size());
        assertSame(seen, ChatStore.entries().get(0));
        assertTrue(ChatStore.entries().get(0).seen());
        assertSame(unseen, ChatStore.entries().get(1));
        assertEquals(1, ChatStore.counters().unseen(Channel.PARTY));
    }

    @Test
    void plainTextIsLowercaseAndFollowsAReplacement() {
        ChatEntry entry = add(line(ChatMarker.Kind.GLOBAL, BOB, "Йди на АРЕНУ"), false);
        assertEquals("йди на арену", entry.plain());
        GuiMessage original = entry.message();
        ChatStore.replace(original, new GuiMessage(0, Component.literal("Deleted"), null, null));
        assertEquals("deleted", entry.plain());
    }
}
