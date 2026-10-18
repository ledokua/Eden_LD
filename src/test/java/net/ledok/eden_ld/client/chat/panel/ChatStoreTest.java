package net.ledok.eden_ld.client.chat.panel;

import net.ledok.eden_ld.chat.ChatChannels.Channel;
import net.ledok.eden_ld.chat.ChatMarker;
import net.ledok.eden_ld.chat.SystemOnlyRules;
import net.ledok.eden_ld.client.chat.model.ChatCounters;
import net.minecraft.client.GuiMessage;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
        ChatStore.rules(SystemOnlyRules.BUILT_IN);
        ChatStore.clear();
    }

    /** The store is shared: other tests find Eden's own rules. */
    @AfterEach
    void builtIn() {
        ChatStore.rules(SystemOnlyRules.BUILT_IN);
        ChatStore.capSystemOnly(() -> false);
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

    // ---------------------------------------------------------------- system-only lines (1.3.2)

    private static GuiMessage system(Component content) {
        return new GuiMessage(0, content, null, null);
    }

    /** Another admin's command, as the game echoes it: "[@: Running function x:0]". */
    private static Component echo(String function) {
        return Component.translatable("chat.type.admin", Component.literal("@"),
                Component.translatable("commands.function.scheduled.single", Component.literal(function)));
    }

    private static long systemOnlyCount() {
        return ChatStore.entries().stream().filter(ChatEntry::systemOnly).count();
    }

    @Test
    void systemOnlyLinesArriveSeenAndNeverCount() {
        ChatEntry echo = add(system(echo("x:a")), false);
        ChatEntry spawn = add(system(Component.translatable("block.minecraft.set_spawn")), false);
        ChatEntry plain = add(system(Component.literal("Server restarts soon")), false);
        assertTrue(echo.systemOnly());
        assertTrue(echo.seen());
        assertTrue(spawn.systemOnly());
        assertTrue(spawn.seen());
        assertFalse(plain.systemOnly());
        assertFalse(plain.seen());
        assertEquals(1, ChatStore.counters().unseenSystem());
    }

    @Test
    void aSystemOnlyLineAgainStaysOneQuietLine() {
        Component refusal = Component.translatable("gui.xaero_claims_protection_interact_block",
                Component.translatable("gui.xaero_claims_protection_main_hand"),
                Component.literal("minecraft:soul_sand"));
        ChatEntry first = add(system(refusal), false);
        ChatStore.Added again = ChatStore.add(system(refusal), false, ME, IGNORED);
        assertTrue(again.repeat());
        assertSame(first, again.entry());
        assertEquals(2, first.count());
        assertTrue(first.seen());
        assertEquals(1, ChatStore.entries().size());
        assertEquals(0, ChatStore.counters().unseenSystem());
    }

    @Test
    void aSystemOnlyLineNeverFoldsIntoTheSameTextFiledOtherwise() {
        ChatEntry literal = add(system(Component.literal("Respawn point set")), false);
        ChatStore.Added spawn = ChatStore.add(system(Component.translatable("block.minecraft.set_spawn")), false, ME,
                IGNORED);
        assertEquals(literal.message().content().getString(), spawn.entry().message().content().getString());
        assertFalse(spawn.repeat());
        assertEquals(2, ChatStore.entries().size());
        assertFalse(literal.systemOnly());
        assertTrue(spawn.entry().systemOnly());
    }

    @Test
    void markedLinesAreNeverSystemOnly() {
        ChatEntry death = add(new GuiMessage(0, ChatMarker.mark(Component.translatable("block.minecraft.set_spawn"),
                ChatMarker.Kind.DEATH, false, BOB), null, null), false);
        assertFalse(death.systemOnly());
        assertFalse(death.seen());
        ChatStore.rules(SystemOnlyRules.of(List.of(), List.of("Respawn"), List.of()));
        assertFalse(death.systemOnly());
    }

    @Test
    void newRulesFileTheLinesAgain() {
        ChatEntry rewriter = add(system(Component.literal("[DataRewriter] recipes: 60 removed")), false);
        assertFalse(rewriter.systemOnly());
        assertFalse(rewriter.seen());
        int version = ChatStore.version();
        ChatStore.rules(SystemOnlyRules.of(List.of(), List.of("[DataRewriter]"), List.of()));
        assertTrue(rewriter.systemOnly());
        assertTrue(rewriter.seen());
        assertNotEquals(version, ChatStore.version());
        // Back to Eden's own: no longer system-only, but no dot comes back.
        ChatStore.rules(SystemOnlyRules.BUILT_IN);
        assertFalse(rewriter.systemOnly());
        assertTrue(rewriter.seen());
        // The same rules again: nothing is read again.
        int same = ChatStore.version();
        ChatStore.rules(SystemOnlyRules.of(List.of(), List.of(), List.of()));
        assertEquals(same, ChatStore.version());
    }

    @Test
    void theOldLookKeepsEverySystemOnlyLine() {
        ChatStore.capSystemOnly(() -> false);
        for (int i = 0; i < ChatStore.SYSTEM_ONLY_LIMIT + 10; i++) {
            add(system(echo("x:" + i)), false);
        }
        assertEquals(ChatStore.SYSTEM_ONLY_LIMIT + 10, systemOnlyCount());
    }

    @Test
    void systemOnlyLinesAreCappedOldestFirstAndTheChatsHistoryStays() {
        ChatStore.capSystemOnly(() -> true);
        int normal = 0;
        for (int i = 0; i < ChatStore.SYSTEM_ONLY_LIMIT + 10; i++) {
            add(system(echo("x:" + i)), false);
            if (i % 4 == 0 && normal < 50) {
                add(line(ChatMarker.Kind.GLOBAL, BOB, "normal " + normal++), false);
            }
        }
        assertEquals(ChatStore.SYSTEM_ONLY_LIMIT, systemOnlyCount());
        assertEquals(ChatStore.SYSTEM_ONLY_LIMIT + 50, ChatStore.entries().size());
        // The oldest echoes went; the oldest chat line is still there.
        assertEquals("normal 0", ChatStore.entries().get(0).message().content().getString());
        assertEquals("[@: Running function x:10]", ChatStore.entries().stream().filter(ChatEntry::systemOnly)
                .findFirst().orElseThrow().message().content().getString());

        // Cleared and put back: counted again, so the next one still takes the oldest's place.
        ChatStore.Snapshot snapshot = ChatStore.snapshot();
        ChatStore.clear();
        add(system(echo("x:between")), false);
        ChatStore.restore(snapshot);
        add(system(echo("x:new")), false);
        assertEquals(ChatStore.SYSTEM_ONLY_LIMIT, systemOnlyCount());
        assertEquals("[@: Running function x:11]", ChatStore.entries().stream().filter(ChatEntry::systemOnly)
                .findFirst().orElseThrow().message().content().getString());
    }

    @Test
    void linesPastTheLimitLeaveTheSystemOnlyCountRight() {
        for (int i = 0; i < 10; i++) {
            add(system(echo("x:" + i)), false);
        }
        for (int i = 0; i < ChatStore.LIMIT; i++) {
            add(line(ChatMarker.Kind.GLOBAL, BOB, "line " + i), false);
        }
        assertEquals(0, systemOnlyCount());
        for (int i = 0; i < ChatStore.SYSTEM_ONLY_LIMIT; i++) {
            add(system(echo("y:" + i)), false);
        }
        assertEquals(ChatStore.SYSTEM_ONLY_LIMIT, systemOnlyCount());
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
