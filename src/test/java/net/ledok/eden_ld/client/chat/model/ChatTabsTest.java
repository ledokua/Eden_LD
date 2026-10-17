package net.ledok.eden_ld.client.chat.model;

import net.ledok.eden_ld.chat.ChatChannels.Channel;
import net.ledok.eden_ld.client.chat.model.ChatTabs.Mode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The new look's tab strip state: which tabs show, in which order, which is active, and what hiding does. */
class ChatTabsTest {

    private static final int GLOBAL = bit(Channel.GLOBAL);
    private static final int LOCAL = bit(Channel.LOCAL);
    private static final int PARTY = bit(Channel.PARTY);
    private static final int GUILD = bit(Channel.GUILD);
    /** Not in a party or guild. */
    private static final int ALONE = GLOBAL | LOCAL;
    private static final int EVERY = GLOBAL | LOCAL | PARTY | GUILD;

    private static int bit(Channel channel) {
        return 1 << channel.ordinal();
    }

    private static int slot(Channel channel) {
        return ChatTabs.slot(ChatTabSpec.of(channel));
    }

    private static int[] order(ChatTabs tabs) {
        int[] slots = new int[tabs.count()];
        for (int i = 0; i < slots.length; i++) {
            slots[i] = tabs.slotAt(i);
        }
        return slots;
    }

    private static ChatTabs tabs(Mode mode, int open, int hidden, Channel typing, boolean allTab) {
        ChatTabs tabs = new ChatTabs();
        tabs.mode(mode);
        tabs.refresh(open, hidden, typing == null ? -1 : typing.ordinal(), allTab, false);
        return tabs;
    }

    @Test
    void slotsRoundTripInStripOrder() {
        for (int slot = 0; slot < ChatTabs.SLOTS; slot++) {
            assertEquals(slot, ChatTabs.slot(ChatTabs.spec(slot)));
        }
        assertSame(ChatTabSpec.ALL, ChatTabs.spec(ChatTabs.ALL_SLOT));
        assertSame(ChatTabSpec.of(Channel.GLOBAL), ChatTabs.spec(1));
        assertSame(ChatTabSpec.of(Channel.GUILD), ChatTabs.spec(4));
        assertSame(ChatTabSpec.SYSTEM, ChatTabs.spec(ChatTabs.SYSTEM_SLOT));
        assertSame(ChatTabSpec.SPY, ChatTabs.spec(ChatTabs.SPY_SLOT));
        assertEquals(-1, ChatTabs.slot(new ChatTabSpec("pm", ChatTabSpec.Kind.PM, null, "x", 0)));
        assertEquals(Mode.ALL, ChatTabs.modeOf(ChatTabs.ALL_SLOT));
        assertEquals(Mode.CHANNEL, ChatTabs.modeOf(slot(Channel.PARTY)));
        assertEquals(Mode.SYSTEM, ChatTabs.modeOf(ChatTabs.SYSTEM_SLOT));
        assertEquals(Mode.SPY, ChatTabs.modeOf(ChatTabs.SPY_SLOT));
        assertEquals(Channel.LOCAL, ChatTabs.channelOf(slot(Channel.LOCAL)));
        assertNull(ChatTabs.channelOf(ChatTabs.SYSTEM_SLOT));
    }

    @Test
    void theStripIsAllThenOpenChannelsThenSystem() {
        ChatTabs alone = tabs(Mode.ALL, ALONE, 0, Channel.GLOBAL, true);
        assertArrayEquals(new int[]{0, 1, 2, 5}, order(alone));
        ChatTabs member = tabs(Mode.ALL, EVERY, 0, Channel.GLOBAL, true);
        assertArrayEquals(new int[]{0, 1, 2, 3, 4, 5}, order(member));
        ChatTabs noAll = tabs(Mode.CHANNEL, ALONE | GUILD, 0, Channel.LOCAL, false);
        assertArrayEquals(new int[]{1, 2, 4, 5}, order(noAll));
    }

    @Test
    void noTypingChannelMeansNoTabs() {
        ChatTabs tabs = tabs(Mode.ALL, EVERY, 0, null, true);
        assertEquals(0, tabs.count());
        assertEquals(-1, tabs.step(-1, false));
        assertSame(ChatTabSpec.ALL, tabs.active(null, true, false));
        assertSame(ChatTabSpec.ALL, new ChatTabs().active(null, false, false), "the last resort, with no typing channel");
    }

    @Test
    void aHiddenChannelHasNoTabUnlessItIsTheActiveOne() {
        // Local hidden, typed into, viewed through All: no Local tab.
        ChatTabs onAll = tabs(Mode.ALL, ALONE, LOCAL, Channel.LOCAL, true);
        assertArrayEquals(new int[]{0, 1, 5}, order(onAll));
        // The same on Local's own tab (/ch local, a [tag]…): its tab shows while it's active.
        ChatTabs onLocal = tabs(Mode.CHANNEL, ALONE, LOCAL, Channel.LOCAL, true);
        assertArrayEquals(new int[]{0, 1, 2, 5}, order(onLocal));
        assertEquals(slot(Channel.LOCAL), onLocal.activeSlot());
        // Typing elsewhere: gone again.
        ChatTabs onGlobal = tabs(Mode.CHANNEL, ALONE, LOCAL, Channel.GLOBAL, true);
        assertArrayEquals(new int[]{0, 1, 5}, order(onGlobal));
    }

    @Test
    void aModeChangeAloneLaysTheStripOutAgain() {
        ChatTabs tabs = tabs(Mode.SYSTEM, ALONE, LOCAL, Channel.LOCAL, true);
        assertArrayEquals(new int[]{0, 1, 5}, order(tabs));
        int key = tabs.key();
        // A mention jump from System to the hidden typing channel's tab: same inputs, another mode.
        tabs.mode(Mode.CHANNEL);
        assertTrue(tabs.refresh(ALONE, LOCAL, Channel.LOCAL.ordinal(), true, false));
        assertArrayEquals(new int[]{0, 1, 2, 5}, order(tabs));
        assertEquals(tabs.indexOf(slot(Channel.LOCAL)), 2);
        assertFalse(key == tabs.key());
    }

    @Test
    void anUnchangedStateKeepsTheOrder() {
        ChatTabs tabs = tabs(Mode.ALL, EVERY, PARTY, Channel.GLOBAL, true);
        int[] before = order(tabs);
        int key = tabs.key();
        assertFalse(tabs.refresh(EVERY, PARTY, Channel.GLOBAL.ordinal(), true, false));
        assertArrayEquals(before, order(tabs));
        assertEquals(key, tabs.key());
        assertEquals(Mode.ALL, tabs.mode());
        // Bits above the channels (Discord's hide switch) don't count.
        assertFalse(tabs.refresh(EVERY, PARTY | 1 << 4, Channel.GLOBAL.ordinal(), true, false));
        // Mode set to what it already is: nothing to lay out.
        tabs.mode(Mode.ALL);
        assertFalse(tabs.refresh(EVERY, PARTY, Channel.GLOBAL.ordinal(), true, false));
    }

    @Test
    void theActiveTabFollowsTheModeAndTheTypingChannel() {
        ChatTabs tabs = new ChatTabs();
        assertEquals(Mode.ALL, tabs.mode(), "the game starts on All");
        assertSame(ChatTabSpec.ALL, tabs.active(Channel.LOCAL, true, false));
        tabs.mode(Mode.CHANNEL);
        assertSame(ChatTabSpec.of(Channel.LOCAL), tabs.active(Channel.LOCAL, true, false));
        assertSame(ChatTabSpec.of(Channel.PARTY), tabs.active(Channel.PARTY, true, false), "the tab follows /ch party");
        tabs.mode(Mode.SYSTEM);
        assertSame(ChatTabSpec.SYSTEM, tabs.active(Channel.PARTY, true, false));
    }

    @Test
    void allWithoutItsTabReadsAsTheTypingChannelAndIsStoredSo() {
        ChatTabs tabs = new ChatTabs();
        // Read live (the closed chat lays nothing out): the typing channel's tab.
        assertSame(ChatTabSpec.of(Channel.GUILD), tabs.active(Channel.GUILD, false, false));
        tabs.refresh(ALONE | GUILD, 0, Channel.GUILD.ordinal(), false, false);
        assertEquals(Mode.CHANNEL, tabs.mode());
        assertEquals(slot(Channel.GUILD), tabs.activeSlot());
        assertTrue(tabs.indexOf(tabs.activeSlot()) >= 0);
        // Turning the All tab back on doesn't jump the view to All.
        tabs.refresh(ALONE | GUILD, 0, Channel.GUILD.ordinal(), true, false);
        assertEquals(Mode.CHANNEL, tabs.mode());
        assertSame(ChatTabSpec.of(Channel.GUILD), tabs.active(Channel.GUILD, true, false));
    }

    @Test
    void allOffWithAHiddenTypingChannelStillLightsItsTab() {
        // All on and viewed; then the All tab goes and Local, typed into, is hidden.
        ChatTabs tabs = tabs(Mode.ALL, ALONE, LOCAL, Channel.LOCAL, false);
        assertEquals(Mode.CHANNEL, tabs.mode());
        assertArrayEquals(new int[]{1, 2, 5}, order(tabs));
        assertEquals(1, tabs.indexOf(tabs.activeSlot()));
        // Ctrl+Shift+Tab from there lands on Global, not a tab further.
        assertEquals(0, tabs.step(tabs.indexOf(tabs.activeSlot()), true));
    }

    @Test
    void spyWithoutSpyingFallsBack() {
        ChatTabs tabs = new ChatTabs();
        tabs.mode(Mode.SPY);
        assertSame(ChatTabSpec.ALL, tabs.active(Channel.LOCAL, true, false));
        assertSame(ChatTabSpec.of(Channel.LOCAL), tabs.active(Channel.LOCAL, false, false));
        assertSame(ChatTabSpec.SPY, tabs.active(Channel.LOCAL, true, true));
        tabs.refresh(ALONE, 0, Channel.LOCAL.ordinal(), true, true);
        assertEquals(Mode.SPY, tabs.mode());
        assertArrayEquals(new int[]{0, 1, 2, 5, 6}, order(tabs));
        tabs.refresh(ALONE, 0, Channel.LOCAL.ordinal(), true, false);
        assertEquals(Mode.ALL, tabs.mode());
        tabs.mode(Mode.SPY);
        tabs.refresh(ALONE, 0, Channel.LOCAL.ordinal(), false, false);
        assertEquals(Mode.CHANNEL, tabs.mode());
    }

    @Test
    void stepWrapsBothWays() {
        ChatTabs tabs = tabs(Mode.ALL, ALONE, 0, Channel.GLOBAL, true);
        assertEquals(4, tabs.count());
        assertEquals(1, tabs.step(0, false));
        assertEquals(0, tabs.step(3, false));
        assertEquals(3, tabs.step(0, true));
        assertEquals(2, tabs.step(3, true));
        assertEquals(0, tabs.step(-1, false), "from no tab: the first");
        assertEquals(3, tabs.step(-1, true), "from no tab, back: the last");
    }

    @Test
    void canHideRefusesTheLastOfAllAndTheChannels() {
        ChatTabs tabs = tabs(Mode.ALL, ALONE, 0, Channel.GLOBAL, true);
        assertTrue(tabs.canHide(ChatTabs.ALL_SLOT));
        assertTrue(tabs.canHide(slot(Channel.LOCAL)));
        assertFalse(tabs.canHide(ChatTabs.SYSTEM_SLOT), "System always shows");
        assertFalse(tabs.canHide(slot(Channel.PARTY)), "no Party tab to hide");
        // Global and Local hidden, typing Global through All: All is the last one.
        ChatTabs last = tabs(Mode.ALL, ALONE, GLOBAL | LOCAL, Channel.GLOBAL, true);
        assertArrayEquals(new int[]{0, 5}, order(last));
        assertFalse(last.canHide(ChatTabs.ALL_SLOT));
        // No All tab, Local hidden but active: Global is the last unhidden one, and Local offers "Show tab".
        ChatTabs noAll = tabs(Mode.CHANNEL, ALONE, LOCAL, Channel.LOCAL, false);
        assertArrayEquals(new int[]{1, 2, 5}, order(noAll));
        assertFalse(noAll.canHide(slot(Channel.GLOBAL)));
        assertFalse(noAll.canHide(slot(Channel.LOCAL)));
        assertTrue(noAll.hidden(slot(Channel.LOCAL)));
        assertFalse(noAll.hidden(slot(Channel.GLOBAL)));
        assertTrue(noAll.hidden(ChatTabs.ALL_SLOT));
    }

    @Test
    void hidingTheActiveTabMovesTheView() {
        ChatTabs onGlobal = tabs(Mode.CHANNEL, ALONE, 0, Channel.GLOBAL, true);
        assertEquals(Mode.ALL, onGlobal.afterHiding(slot(Channel.GLOBAL)));
        assertEquals(Mode.CHANNEL, onGlobal.afterHiding(slot(Channel.LOCAL)), "not the active tab: unchanged");
        ChatTabs onAll = tabs(Mode.ALL, ALONE, 0, Channel.GLOBAL, true);
        assertEquals(Mode.CHANNEL, onAll.afterHiding(ChatTabs.ALL_SLOT));
        assertEquals(Mode.ALL, onAll.afterHiding(slot(Channel.GLOBAL)));
        ChatTabs noAll = tabs(Mode.CHANNEL, ALONE, 0, Channel.LOCAL, false);
        assertEquals(Mode.CHANNEL, noAll.afterHiding(slot(Channel.LOCAL)), "no All tab to go to");
        ChatTabs onSystem = tabs(Mode.SYSTEM, ALONE, 0, Channel.LOCAL, true);
        assertEquals(Mode.SYSTEM, onSystem.afterHiding(slot(Channel.LOCAL)));
    }

    @Test
    void showHiddenTabsOffersOpenHiddenChannelsAndAllWhenOff() {
        // Party hidden but no party (left it): nothing to bring back there.
        ChatTabs tabs = tabs(Mode.ALL, ALONE | GUILD, LOCAL | PARTY | GUILD, Channel.GLOBAL, true);
        assertFalse(tabs.showable(ChatTabs.ALL_SLOT));
        assertFalse(tabs.showable(slot(Channel.GLOBAL)));
        assertTrue(tabs.showable(slot(Channel.LOCAL)));
        assertFalse(tabs.showable(slot(Channel.PARTY)));
        assertTrue(tabs.showable(slot(Channel.GUILD)));
        assertFalse(tabs.showable(ChatTabs.SYSTEM_SLOT));
        ChatTabs noAll = tabs(Mode.CHANNEL, ALONE, 0, Channel.GLOBAL, false);
        assertTrue(noAll.showable(ChatTabs.ALL_SLOT));
    }

    @Test
    void aMentionJumpGoesWhereTheLineShows() {
        // From System: All has it (the 1.2 default view after a pick).
        assertEquals(Mode.ALL, ChatTabs.revealMode(true, true));
        // All off, or it hides the line's channel: the channel's own tab, now typed into.
        assertEquals(Mode.CHANNEL, ChatTabs.revealMode(false, true));
        // The party ended before the jump: the pick was refused, but All still shows the line.
        assertEquals(Mode.ALL, ChatTabs.revealMode(true, false));
        // …and with no All tab (or Party hidden) no tab can: the mention is done with, not pinned for good.
        assertNull(ChatTabs.revealMode(false, false));
    }

    @Test
    void aTagClickNamesItsChannel() {
        assertEquals(Channel.LOCAL, ChatTabs.tagChannel("/ch local"));
        assertEquals(Channel.GUILD, ChatTabs.tagChannel("/ch guild"));
        assertEquals(Channel.GLOBAL, ChatTabs.tagChannel("/ch global"));
        assertNull(ChatTabs.tagChannel("/ch"));
        assertNull(ChatTabs.tagChannel("/ch spy"));
        assertNull(ChatTabs.tagChannel("/ch local now"));
        assertNull(ChatTabs.tagChannel("/msg Bob hi"));
        assertNull(ChatTabs.tagChannel("ch local"));
    }
}
