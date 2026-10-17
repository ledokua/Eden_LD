package net.ledok.eden_ld.client.chat.ui;

import net.ledok.eden_ld.chat.ChatChannels.Channel;
import net.ledok.eden_ld.client.chat.ChannelMenu;
import net.ledok.eden_ld.client.chat.model.ChatTabSpec;
import net.ledok.eden_ld.client.chat.model.ChatTabs;
import net.ledok.eden_ld.hub.HubSettings.ChannelNotify;
import net.ledok.vectorlib.client.canvas.widget.ContextMenu;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A tab's menu in the strip: which rows each kind of tab gets, in what order, and what each runs. */
class TabMenuTest {

    private static final int INK_DIM = 0xFF8C8A85;
    private static final int DANGER = 0xFFE5534B;
    private static final int[] NONE = new int[0];

    /** Records what the rows ran. */
    private static final class Recorder implements TabMenu.Actions {
        final List<String> ran = new ArrayList<>();

        @Override
        public void mute(int minutes) {
            ran.add("mute " + minutes);
        }

        @Override
        public void notify(ChannelNotify level) {
            ran.add("notify " + level);
        }

        @Override
        public void markRead() {
            ran.add("read");
        }

        @Override
        public void settings() {
            ran.add("settings");
        }

        @Override
        public void hide() {
            ran.add("hide");
        }

        @Override
        public void unhide() {
            ran.add("unhide");
        }

        @Override
        public void show(int slot) {
            ran.add("show " + slot);
        }

        @Override
        public void discord(boolean shown) {
            ran.add("discord " + shown);
        }

        @Override
        public void systemInTabs(boolean on) {
            ran.add("system in tabs " + on);
        }

        @Override
        public void markReadSystem() {
            ran.add("read system");
        }

        @Override
        public void markReadAll() {
            ran.add("read all");
        }
    }

    private static List<Class<?>> kinds(List<ContextMenu.Entry> entries) {
        List<Class<?>> kinds = new ArrayList<>();
        for (ContextMenu.Entry entry : entries) {
            kinds.add(entry.getClass());
        }
        return kinds;
    }

    private static String key(Component component) {
        return ((TranslatableContents) component.getContents()).getKey();
    }

    @SuppressWarnings("unchecked")
    private static List<ContextMenu.Entry> entriesOf(ContextMenu menu) throws ReflectiveOperationException {
        Field field = ContextMenu.class.getDeclaredField("entries");
        field.setAccessible(true);
        return (List<ContextMenu.Entry>) field.get(menu);
    }

    private static ChannelMenu.Spec channel(@Nullable Component muted) {
        return new ChannelMenu.Spec(Component.literal("Global"), 0xFFFFFFFF, muted, ChannelNotify.ONLY_MENTIONS, true,
                INK_DIM);
    }

    private static TabMenu.Spec global(@Nullable Component muted, boolean hidden, boolean hideable, int[] showable) {
        return new TabMenu.Spec(ChatTabSpec.of(Channel.GLOBAL), channel(muted), true, hidden, hideable, null, showable,
                true, INK_DIM, DANGER);
    }

    // ---------------------------------------------------------------- channel tabs

    @Test
    void globalsTab() {
        Recorder actions = new Recorder();
        List<ContextMenu.Entry> rows = TabMenu.entries(global(null, false, true, NONE), actions);
        // ChannelMenu's own rows, the Discord check, Hide tab, a line, Chat settings.
        assertEquals(List.of(ContextMenu.Header.class, ContextMenu.Submenu.class, ContextMenu.Submenu.class,
                ContextMenu.Item.class, ContextMenu.Check.class, ContextMenu.Item.class, ContextMenu.Separator.class,
                ContextMenu.Item.class), kinds(rows));
        assertEquals(kinds(ChannelMenu.core(channel(null), actions)), kinds(rows).subList(0, 4));
        ContextMenu.Check discord = (ContextMenu.Check) rows.get(4);
        assertEquals("chat.eden_ld.menu.discord", key(discord.label()));
        assertTrue(discord.checked());
        ContextMenu.Item hide = (ContextMenu.Item) rows.get(5);
        assertEquals("chat.eden_ld.menu.hide_tab", key(hide.label()));
        assertTrue(hide.enabled());
        assertEquals("chat.eden_ld.menu.settings", key(((ContextMenu.Item) rows.get(7)).label()));

        ((ContextMenu.Item) rows.get(3)).action().run();
        discord.action().run();
        hide.action().run();
        ((ContextMenu.Item) rows.get(7)).action().run();
        assertEquals(List.of("read", "discord false", "hide", "settings"), actions.ran);
    }

    @Test
    void aMutedChannelsTabWithoutDiscord() {
        Recorder actions = new Recorder();
        TabMenu.Spec spec = new TabMenu.Spec(ChatTabSpec.of(Channel.LOCAL), channel(Component.literal("Muted")), null,
                false, false, null, NONE, true, INK_DIM, DANGER);
        List<ContextMenu.Entry> rows = TabMenu.entries(spec, actions);
        assertEquals(List.of(ContextMenu.Header.class, ContextMenu.Header.class, ContextMenu.Item.class,
                ContextMenu.Submenu.class, ContextMenu.Item.class, ContextMenu.Item.class, ContextMenu.Separator.class,
                ContextMenu.Item.class), kinds(rows));
        ContextMenu.Item hide = (ContextMenu.Item) rows.get(5);
        assertEquals("chat.eden_ld.menu.hide_tab", key(hide.label()));
        assertFalse(hide.enabled(), "the last of All and the channels can't go");
        ((ContextMenu.Item) rows.get(2)).action().run();
        assertEquals(List.of("mute 0"), actions.ran, "Unmute");
    }

    @Test
    void aHiddenChannelShowingWhileActiveOffersShowTab() {
        Recorder actions = new Recorder();
        List<ContextMenu.Entry> rows = TabMenu.entries(global(null, true, false, NONE), actions);
        ContextMenu.Item show = (ContextMenu.Item) rows.get(5);
        assertEquals("chat.eden_ld.menu.show_tab", key(show.label()));
        assertTrue(show.enabled());
        show.action().run();
        assertEquals(List.of("unhide"), actions.ran);
    }

    @Test
    void showHiddenTabsListsEachHiddenTab() throws ReflectiveOperationException {
        Recorder actions = new Recorder();
        int local = ChatTabs.slot(ChatTabSpec.of(Channel.LOCAL));
        List<ContextMenu.Entry> rows = TabMenu.entries(global(null, false, true, new int[]{ChatTabs.ALL_SLOT, local}),
                actions);
        ContextMenu.Submenu show = assertInstanceOf(ContextMenu.Submenu.class, rows.get(6));
        assertEquals("chat.eden_ld.menu.show_tabs", key(show.label()));
        assertInstanceOf(ContextMenu.Separator.class, rows.get(7), "then the line and Chat settings");
        List<ContextMenu.Entry> hidden = entriesOf(show.menu().get());
        assertEquals(2, hidden.size());
        assertEquals("chat.eden_ld.tab.all", key(((ContextMenu.Item) hidden.get(0)).label()));
        assertEquals("chat.eden_ld.channel.local", key(((ContextMenu.Item) hidden.get(1)).label()));
        for (ContextMenu.Entry entry : hidden) {
            ((ContextMenu.Item) entry).action().run();
        }
        assertEquals(List.of("show 0", "show " + local), actions.ran);
    }

    @Test
    void nothingHiddenNoShowRow() {
        for (ContextMenu.Entry entry : TabMenu.entries(global(null, false, true, NONE), new Recorder())) {
            if (entry instanceof ContextMenu.Submenu submenu
                    && submenu.label().getContents() instanceof TranslatableContents label) {
                assertFalse(label.getKey().equals("chat.eden_ld.menu.show_tabs"));
            }
        }
    }

    // ---------------------------------------------------------------- System, All, Spy

    private static TabMenu.Spec system(@Nullable Boolean inTabs, boolean unread) {
        return new TabMenu.Spec(ChatTabSpec.SYSTEM, null, null, false, false, inTabs, NONE, unread, INK_DIM, DANGER);
    }

    @Test
    void systemsTabWhereTheServerKeepsTheSwitch() {
        Recorder actions = new Recorder();
        List<ContextMenu.Entry> rows = TabMenu.entries(system(true, true), actions);
        assertEquals(List.of(ContextMenu.Header.class, ContextMenu.Item.class, ContextMenu.Check.class,
                ContextMenu.Separator.class, ContextMenu.Item.class), kinds(rows));
        ContextMenu.Header title = (ContextMenu.Header) rows.get(0);
        assertEquals("chat.eden_ld.pip.system.name", key(title.text()));
        assertEquals(0xFFAAAAAA, title.color());
        ContextMenu.Item read = (ContextMenu.Item) rows.get(1);
        assertEquals("chat.eden_ld.menu.read", key(read.label()));
        assertTrue(read.enabled());
        ContextMenu.Check inTabs = (ContextMenu.Check) rows.get(2);
        assertEquals("chat.eden_ld.menu.system_in_tabs", key(inTabs.label()));
        assertTrue(inTabs.checked());
        read.action().run();
        inTabs.action().run();
        assertEquals(List.of("read system", "system in tabs false"), actions.ran);
    }

    @Test
    void systemsTabOnAnOlderServer() {
        List<ContextMenu.Entry> rows = TabMenu.entries(system(null, false), new Recorder());
        assertEquals(List.of(ContextMenu.Header.class, ContextMenu.Item.class, ContextMenu.Separator.class,
                ContextMenu.Item.class), kinds(rows), "no check for a switch the server doesn't keep");
        assertFalse(((ContextMenu.Item) rows.get(1)).enabled(), "nothing unread");
    }

    @Test
    void allsTab() {
        Recorder actions = new Recorder();
        TabMenu.Spec spec = new TabMenu.Spec(ChatTabSpec.ALL, null, null, false, true, null, NONE, true, INK_DIM,
                DANGER);
        List<ContextMenu.Entry> rows = TabMenu.entries(spec, actions);
        assertEquals(List.of(ContextMenu.Header.class, ContextMenu.Item.class, ContextMenu.Item.class,
                ContextMenu.Separator.class, ContextMenu.Item.class), kinds(rows));
        ContextMenu.Header title = (ContextMenu.Header) rows.get(0);
        assertEquals("chat.eden_ld.tab.all", key(title.text()));
        assertEquals(INK_DIM, title.color());
        assertEquals("chat.eden_ld.menu.read_all", key(((ContextMenu.Item) rows.get(1)).label()));
        assertEquals("chat.eden_ld.menu.hide_tab", key(((ContextMenu.Item) rows.get(2)).label()));
        ((ContextMenu.Item) rows.get(1)).action().run();
        ((ContextMenu.Item) rows.get(2)).action().run();
        assertEquals(List.of("read all", "hide"), actions.ran);
    }

    @Test
    void allsHideIsGreyedWhereTheServerDoesntKeepItsSwitch() {
        // ChatTabActions.canHide is false for All while the All tab's flag is unknown.
        TabMenu.Spec spec = new TabMenu.Spec(ChatTabSpec.ALL, null, null, false, false, null, NONE, false, INK_DIM,
                DANGER);
        List<ContextMenu.Entry> rows = TabMenu.entries(spec, new Recorder());
        assertFalse(((ContextMenu.Item) rows.get(1)).enabled(), "nothing unread");
        assertFalse(((ContextMenu.Item) rows.get(2)).enabled());
    }

    @Test
    void spysTabIsItsNameInTheDangerColor() {
        TabMenu.Spec spec = new TabMenu.Spec(ChatTabSpec.SPY, null, null, false, false, null, NONE, false, INK_DIM,
                DANGER);
        List<ContextMenu.Entry> rows = TabMenu.entries(spec, new Recorder());
        assertEquals(List.of(ContextMenu.Header.class, ContextMenu.Separator.class, ContextMenu.Item.class),
                kinds(rows));
        assertEquals(DANGER, ((ContextMenu.Header) rows.get(0)).color());
        assertEquals("chat.eden_ld.tab.spy", key(((ContextMenu.Header) rows.get(0)).text()));
    }

    // ---------------------------------------------------------------- placement

    @Test
    void aTabsMenuOpensBelowItsChipOrAboveWhenThereIsNoRoom() {
        // A chip at 7..25 on a 224-tall canvas: 1 below it.
        assertEquals(26, ChatMenus.below(7, 25, 100, 224));
        assertEquals(26, ChatMenus.below(7, 25, 198, 224), "exactly fits");
        // Too tall to fit below: ends 1 above the chip, no higher than the top.
        assertEquals(99, ChatMenus.below(200, 218, 100, 224));
        assertEquals(0, ChatMenus.below(50, 68, 199, 224));
        // The glyph's: anchored at the canvas' bottom, it opens upward from there.
        assertEquals(123, ChatMenus.below(224, 224, 100, 224));
    }
}
