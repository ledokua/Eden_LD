package net.ledok.eden_ld.client.chat;

import net.ledok.eden_ld.chat.ChatChannels.Channel;
import net.ledok.eden_ld.chat.ChatMarker;
import net.ledok.eden_ld.client.chat.model.ChatRules;
import net.ledok.eden_ld.client.chat.panel.ChatEntry;
import net.ledok.eden_ld.client.chat.panel.ChatStore;
import net.minecraft.client.GuiMessage;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The closed chat (new look) shows other tabs' mentions the player hasn't seen yet — and nothing else extra. */
class HudMentionTest {

    private static final UUID ME = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID TROLL = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final Predicate<UUID> IGNORED = Set.of(TROLL)::contains;

    @BeforeEach
    void empty() {
        ChatStore.clear();
    }

    private static ChatEntry add(ChatMarker.Kind kind, UUID sender, boolean mention) {
        GuiMessage message = new GuiMessage(0, ChatMarker.mark(Component.literal("@Me hi " + kind + sender), kind,
                false, sender), null, null);
        return ChatStore.add(message, mention, ME, IGNORED).entry();
    }

    private static ChatRules rules(int switchedOff, Channel typing) {
        return new ChatRules(ME, IGNORED, switchedOff, 0, 0, typing, true);
    }

    @Test
    void anUnseenMentionShowsUntilSeen() {
        // No settings yet: notifications read Only @mentions.
        ChatEntry party = add(ChatMarker.Kind.PARTY, BOB, true);
        assertTrue(ChatActivity.unseenMention(party, rules(0, Channel.GLOBAL)));
        party.markSeen();
        assertFalse(ChatActivity.unseenMention(party, rules(0, Channel.GLOBAL)));
    }

    @Test
    void onlyMentionsOfOthersInAChannel() {
        assertFalse(ChatActivity.unseenMention(add(ChatMarker.Kind.LOCAL, BOB, false), rules(0, Channel.GLOBAL)));
        assertFalse(ChatActivity.unseenMention(add(ChatMarker.Kind.GUILD, ME, true), rules(0, Channel.GLOBAL)),
                "one's own line is no mention");
        assertFalse(ChatActivity.unseenMention(add(ChatMarker.Kind.LOCAL, TROLL, true), rules(0, Channel.GLOBAL)),
                "an ignored player's is seen at once");
        assertFalse(ChatActivity.unseenMention(add(ChatMarker.Kind.DEATH, BOB, true), rules(0, Channel.GLOBAL)),
                "no channel");
    }

    @Test
    void aHiddenChannelsMentionStaysOutAsAllWouldLeaveIt() {
        ChatEntry local = add(ChatMarker.Kind.LOCAL, BOB, true);
        int localOff = 1 << Channel.LOCAL.ordinal();
        assertFalse(ChatActivity.unseenMention(local, rules(localOff, Channel.GLOBAL)));
        // Typed into, its lines show again.
        assertTrue(ChatActivity.unseenMention(local, rules(localOff, Channel.LOCAL)));
    }
}
