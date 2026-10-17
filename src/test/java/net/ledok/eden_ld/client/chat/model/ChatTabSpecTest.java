package net.ledok.eden_ld.client.chat.model;

import net.ledok.eden_ld.chat.ChatChannels.Channel;
import net.ledok.eden_ld.chat.ChatMarker;
import net.ledok.eden_ld.chat.ChatMarker.Kind;
import net.ledok.eden_ld.hub.HubSettings.MessagesFrom;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatTabSpecTest {

    private static final UUID ME = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID TROLL = UUID.fromString("00000000-0000-0000-0000-000000000003");

    private static ChatRules rules(int switchedOff, Channel typing) {
        return new ChatRules(ME, Set.of(TROLL)::contains, switchedOff, MessagesFrom.EVERYONE.ordinal(),
                MessagesFrom.EVERYONE.ordinal(), typing);
    }

    private static ChatMarker.Mark line(Kind kind, UUID sender) {
        return new ChatMarker.Mark(kind, false, sender);
    }

    @Test
    void unmarkedLinesShowEverywhereButSpyAndPm() {
        ChatRules rules = rules(0, Channel.GLOBAL);
        assertTrue(ChatTabSpec.ALL.shows(null, rules));
        assertTrue(ChatTabSpec.SYSTEM.shows(null, rules));
        assertTrue(ChatTabSpec.of(Channel.PARTY).shows(null, rules));
        assertFalse(new ChatTabSpec("spy", ChatTabSpec.Kind.SPY, null, "x", 0).shows(null, rules));
        assertFalse(new ChatTabSpec("pm", ChatTabSpec.Kind.PM, null, "x", 0).shows(null, rules));
    }

    @Test
    void systemShowsDeathsAndAdvancementsButNoChat() {
        ChatRules rules = rules(0, Channel.GLOBAL);
        assertFalse(ChatTabSpec.SYSTEM.shows(line(Kind.GLOBAL, BOB), rules));
        assertFalse(ChatTabSpec.SYSTEM.shows(line(Kind.DISCORD, null), rules));
        assertFalse(ChatTabSpec.SYSTEM.shows(line(Kind.LOCAL, ME), rules));
        assertTrue(ChatTabSpec.SYSTEM.shows(line(Kind.DEATH, BOB), rules));
        assertTrue(ChatTabSpec.SYSTEM.shows(line(Kind.ADVANCEMENT, null), rules));
    }

    @Test
    void ignoredPlayersHideButNeverTheReader() {
        ChatRules rules = rules(0, Channel.GLOBAL);
        assertFalse(ChatTabSpec.ALL.shows(line(Kind.GLOBAL, TROLL), rules));
        assertFalse(ChatTabSpec.ALL.shows(line(Kind.DEATH, TROLL), rules));
        ChatRules ignoringSelf = new ChatRules(ME, id -> true, 0, 0, 0, Channel.GLOBAL);
        assertTrue(ChatTabSpec.ALL.shows(line(Kind.GLOBAL, ME), ignoringSelf));
    }

    @Test
    void switchedOffChannelsHideExceptOwnLinesAndTheChannelTypedInto() {
        int localOff = 1 << Channel.LOCAL.ordinal();
        assertFalse(ChatTabSpec.ALL.shows(line(Kind.LOCAL, BOB), rules(localOff, Channel.GLOBAL)));
        assertTrue(ChatTabSpec.ALL.shows(line(Kind.LOCAL, ME), rules(localOff, Channel.GLOBAL)));
        // Picked on its pip on purpose: its lines show although it's switched off.
        assertTrue(ChatTabSpec.ALL.shows(line(Kind.LOCAL, BOB), rules(localOff, Channel.LOCAL)));
        assertTrue(ChatTabSpec.ALL.shows(line(Kind.GLOBAL, BOB), rules(localOff, Channel.GLOBAL)));
    }

    @Test
    void discordHasItsOwnSwitchEvenInGlobal() {
        int discordOff = 1 << 4;
        assertFalse(ChatTabSpec.ALL.shows(line(Kind.DISCORD, null), rules(discordOff, Channel.GLOBAL)));
        assertFalse(ChatTabSpec.of(Channel.GLOBAL).shows(line(Kind.DISCORD, null), rules(discordOff, Channel.GLOBAL)));
        assertTrue(ChatTabSpec.ALL.shows(line(Kind.GLOBAL, BOB), rules(discordOff, Channel.GLOBAL)));
        assertTrue(ChatTabSpec.of(Channel.GLOBAL).shows(line(Kind.DISCORD, null), rules(0, Channel.GLOBAL)));
    }

    @Test
    void channelTabShowsOnlyItsChannelOwnLinesIncluded() {
        ChatTabSpec party = ChatTabSpec.of(Channel.PARTY);
        ChatRules rules = rules(0, Channel.PARTY);
        assertTrue(party.shows(line(Kind.PARTY, BOB), rules));
        assertTrue(party.shows(line(Kind.PARTY, ME), rules));
        assertFalse(party.shows(line(Kind.GLOBAL, ME), rules));
        assertFalse(party.shows(line(Kind.GUILD, BOB), rules));
        assertTrue(party.shows(line(Kind.DEATH, BOB), rules));
    }

    @Test
    void deathsAndAdvancementsFollowTheirSettingsForEveryone() {
        ChatRules team = new ChatRules(ME, id -> false, 0, MessagesFrom.TEAM.ordinal(), MessagesFrom.NOBODY.ordinal(),
                Channel.GLOBAL);
        assertTrue(ChatTabSpec.ALL.shows(new ChatMarker.Mark(Kind.DEATH, true, BOB), team));
        assertFalse(ChatTabSpec.ALL.shows(new ChatMarker.Mark(Kind.DEATH, false, BOB), team));
        assertFalse(ChatTabSpec.ALL.shows(new ChatMarker.Mark(Kind.ADVANCEMENT, true, ME), team));
    }

    @Test
    void noSettingsShowsEverythingButIgnoredPlayers() {
        ChatRules none = ChatRules.none(ME, Set.of(TROLL)::contains, Channel.GLOBAL);
        for (Kind kind : Kind.values()) {
            assertTrue(ChatTabSpec.ALL.shows(line(kind, BOB), none), kind.name());
        }
        assertFalse(ChatTabSpec.ALL.shows(line(Kind.LOCAL, TROLL), none));
    }
}
