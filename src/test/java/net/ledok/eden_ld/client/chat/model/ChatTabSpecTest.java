package net.ledok.eden_ld.client.chat.model;

import net.ledok.eden_ld.chat.ChatChannels.Channel;
import net.ledok.eden_ld.chat.ChatMarker;
import net.ledok.eden_ld.chat.ChatMarker.Kind;
import net.ledok.eden_ld.hub.HubSettings.MessagesFrom;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatTabSpecTest {

    private static final UUID ME = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID TROLL = UUID.fromString("00000000-0000-0000-0000-000000000003");

    private static ChatRules rules(int switchedOff, Channel typing) {
        return rules(switchedOff, typing, true);
    }

    private static ChatRules rules(int switchedOff, Channel typing, boolean systemInTabs) {
        return new ChatRules(ME, Set.of(TROLL)::contains, switchedOff, MessagesFrom.EVERYONE.ordinal(),
                MessagesFrom.EVERYONE.ordinal(), typing, systemInTabs);
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
        assertFalse(ChatTabSpec.SPY.shows(null, rules));
        assertFalse(ChatTabSpec.SPY.shows(line(Kind.GLOBAL, BOB), rules));
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
        ChatRules ignoringSelf = new ChatRules(ME, id -> true, 0, 0, 0, Channel.GLOBAL, true);
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
                Channel.GLOBAL, true);
        assertTrue(ChatTabSpec.ALL.shows(new ChatMarker.Mark(Kind.DEATH, true, BOB), team));
        assertFalse(ChatTabSpec.ALL.shows(new ChatMarker.Mark(Kind.DEATH, false, BOB), team));
        assertFalse(ChatTabSpec.ALL.shows(new ChatMarker.Mark(Kind.ADVANCEMENT, true, ME), team));
    }

    @Test
    void systemLinesInChannelTabsFollowTheSwitchAllAndSystemKeepThem() {
        ChatTabSpec global = ChatTabSpec.of(Channel.GLOBAL);
        assertTrue(global.shows(null, rules(0, Channel.GLOBAL, true)));
        assertFalse(global.shows(null, rules(0, Channel.GLOBAL, false)));
        assertFalse(ChatTabSpec.of(Channel.PARTY).shows(null, rules(0, Channel.GLOBAL, false)));
        ChatRules off = rules(0, Channel.GLOBAL, false);
        assertTrue(ChatTabSpec.ALL.shows(null, off));
        assertTrue(ChatTabSpec.SYSTEM.shows(null, off));
        // Only unmarked lines: the channel's own lines stay.
        assertTrue(global.shows(line(Kind.GLOBAL, BOB), off));
        assertTrue(global.shows(line(Kind.DISCORD, null), off));
    }

    @Test
    void deathsAndAdvancementsInChannelTabsFollowOnlyTheirOwnSwitches() {
        ChatTabSpec local = ChatTabSpec.of(Channel.LOCAL);
        for (boolean systemInTabs : new boolean[]{true, false}) {
            ChatRules everyone = rules(0, Channel.LOCAL, systemInTabs);
            assertTrue(local.shows(line(Kind.DEATH, BOB), everyone), "switch " + systemInTabs);
            assertTrue(local.shows(line(Kind.ADVANCEMENT, BOB), everyone), "switch " + systemInTabs);
            // An ignored player's death: hidden whatever the switch says.
            assertFalse(local.shows(line(Kind.DEATH, TROLL), everyone), "switch " + systemInTabs);
            ChatRules nobody = new ChatRules(ME, Set.of(TROLL)::contains, 0, MessagesFrom.NOBODY.ordinal(),
                    MessagesFrom.NOBODY.ordinal(), Channel.LOCAL, systemInTabs);
            assertFalse(local.shows(line(Kind.DEATH, BOB), nobody), "switch " + systemInTabs);
            assertFalse(local.shows(line(Kind.ADVANCEMENT, ME), nobody), "switch " + systemInTabs);
        }
    }

    // ---------------------------------------------------------------- system-only lines (1.3.2)

    /** The new look's rules: system-only lines in System alone. */
    private static ChatRules newLook(int switchedOff, Channel typing, boolean systemInTabs) {
        return new ChatRules(ME, Set.of(TROLL)::contains, switchedOff, MessagesFrom.EVERYONE.ordinal(),
                MessagesFrom.EVERYONE.ordinal(), typing, systemInTabs, true);
    }

    private static List<ChatTabSpec> everyTab() {
        List<ChatTabSpec> tabs = new ArrayList<>(List.of(ChatTabSpec.ALL, ChatTabSpec.SYSTEM, ChatTabSpec.SPY,
                new ChatTabSpec("pm", ChatTabSpec.Kind.PM, null, "x", 0)));
        for (Channel channel : Channel.values()) {
            tabs.add(ChatTabSpec.of(channel));
        }
        return tabs;
    }

    @Test
    void systemOnlyLinesShowInSystemAloneWithTheNewLook() {
        for (boolean systemInTabs : new boolean[]{true, false}) {
            ChatRules rules = newLook(0, Channel.GLOBAL, systemInTabs);
            assertTrue(ChatTabSpec.SYSTEM.shows(null, true, rules), "switch " + systemInTabs);
            assertFalse(ChatTabSpec.ALL.shows(null, true, rules), "switch " + systemInTabs);
            for (Channel channel : Channel.values()) {
                assertFalse(ChatTabSpec.of(channel).shows(null, true, rules), channel + ", switch " + systemInTabs);
            }
            assertFalse(ChatTabSpec.SPY.shows(null, true, rules));
            assertFalse(new ChatTabSpec("pm", ChatTabSpec.Kind.PM, null, "x", 0).shows(null, true, rules));
            // Other unmarked lines as before.
            assertTrue(ChatTabSpec.ALL.shows(null, false, rules));
            assertEquals(systemInTabs, ChatTabSpec.of(Channel.GLOBAL).shows(null, false, rules));
        }
        ChatRules none = ChatRules.none(ME, Set.of(TROLL)::contains, Channel.GLOBAL, true);
        assertTrue(ChatTabSpec.SYSTEM.shows(null, true, none));
        assertFalse(ChatTabSpec.ALL.shows(null, true, none));
    }

    @Test
    void theOldLookShowsSystemOnlyLinesAsBefore() {
        for (ChatTabSpec tab : everyTab()) {
            for (boolean systemInTabs : new boolean[]{true, false}) {
                ChatRules rules = rules(0, Channel.GLOBAL, systemInTabs);
                assertEquals(tab.shows(null, rules), tab.shows(null, true, rules),
                        tab.id() + ", switch " + systemInTabs);
            }
            ChatRules none = ChatRules.none(ME, Set.of(TROLL)::contains, Channel.GLOBAL);
            assertEquals(tab.shows(null, none), tab.shows(null, true, none), tab.id());
        }
    }

    @Test
    void theFlagMeansNothingOnMarkedLines() {
        UUID[] senders = {null, ME, BOB, TROLL};
        for (ChatTabSpec tab : everyTab()) {
            for (boolean newLook : new boolean[]{true, false}) {
                for (boolean systemInTabs : new boolean[]{true, false}) {
                    ChatRules rules = newLook ? newLook(0, Channel.LOCAL, systemInTabs)
                            : rules(0, Channel.LOCAL, systemInTabs);
                    for (Kind kind : Kind.values()) {
                        for (boolean team : new boolean[]{true, false}) {
                            for (UUID sender : senders) {
                                ChatMarker.Mark mark = new ChatMarker.Mark(kind, team, sender);
                                assertEquals(tab.shows(mark, false, rules), tab.shows(mark, true, rules),
                                        tab.id() + " " + kind + " " + sender);
                            }
                        }
                    }
                }
            }
        }
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
