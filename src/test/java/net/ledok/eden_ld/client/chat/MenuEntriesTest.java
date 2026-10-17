package net.ledok.eden_ld.client.chat;

import net.ledok.eden_ld.hub.HubSettings;
import net.ledok.eden_ld.hub.HubSettings.ChannelNotify;
import net.ledok.vectorlib.client.canvas.widget.ContextMenu;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The new look's channel and message menus: which rows show, in what order, and what each runs. */
class MenuEntriesTest {

    private static final int INK_DIM = 0xFF8C8A85;
    private static final Component TITLE = Component.literal("Global");
    private static final Component MUTED = Component.literal("Muted until 14:35");

    /** Records what the rows ran. */
    private static final class Recorder implements ChannelMenu.Actions, MessageMenu.Actions {
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
        public void reply() {
            ran.add("reply");
        }

        @Override
        public void copy() {
            ran.add("copy");
        }

        @Override
        public void profile() {
            ran.add("profile");
        }

        @Override
        public void ignore() {
            ran.add("ignore");
        }

        @Override
        public void moderate() {
            ran.add("moderate");
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

    private static ChannelMenu.Spec channel(@org.jetbrains.annotations.Nullable Component muted, boolean unread) {
        return new ChannelMenu.Spec(TITLE, 0xFF55FFFF, muted, ChannelNotify.ONLY_MENTIONS, unread, INK_DIM);
    }

    // ---------------------------------------------------------------- channel

    @Test
    void aChannelsMenu() {
        Recorder actions = new Recorder();
        List<ContextMenu.Entry> rows = ChannelMenu.entries(channel(null, true), actions);
        assertEquals(List.of(ContextMenu.Header.class, ContextMenu.Submenu.class, ContextMenu.Submenu.class,
                ContextMenu.Item.class, ContextMenu.Separator.class, ContextMenu.Item.class), kinds(rows));
        ContextMenu.Header title = (ContextMenu.Header) rows.get(0);
        assertSame(TITLE, title.text());
        assertEquals(0xFF55FFFF, title.color(), "the channel's color");
        assertEquals("chat.eden_ld.menu.mute", key(((ContextMenu.Submenu) rows.get(1)).label()));
        ContextMenu.Item read = (ContextMenu.Item) rows.get(3);
        assertEquals("chat.eden_ld.menu.read", key(read.label()));
        assertTrue(read.enabled());
        ContextMenu.Item settings = (ContextMenu.Item) rows.get(5);
        assertEquals("chat.eden_ld.menu.settings", key(settings.label()));

        read.action().run();
        settings.action().run();
        assertEquals(List.of("read", "settings"), actions.ran);
    }

    @Test
    void markAsReadIsGreyedWithNothingUnread() {
        ContextMenu.Item read = (ContextMenu.Item) ChannelMenu.entries(channel(null, false), new Recorder()).get(3);
        assertFalse(read.enabled());
    }

    @Test
    void aMutedChannelsMenu() {
        Recorder actions = new Recorder();
        List<ContextMenu.Entry> rows = ChannelMenu.entries(channel(MUTED, true), actions);
        assertEquals(List.of(ContextMenu.Header.class, ContextMenu.Header.class, ContextMenu.Item.class,
                ContextMenu.Submenu.class, ContextMenu.Item.class, ContextMenu.Separator.class, ContextMenu.Item.class),
                kinds(rows));
        ContextMenu.Header muted = (ContextMenu.Header) rows.get(1);
        assertSame(MUTED, muted.text());
        assertEquals(INK_DIM, muted.color(), "the muted line is dim");
        ContextMenu.Item unmute = (ContextMenu.Item) rows.get(2);
        assertEquals("chat.eden_ld.menu.unmute", key(unmute.label()));
        unmute.action().run();
        assertEquals(List.of("mute 0"), actions.ran);
    }

    @Test
    void theMuteSubmenu() throws ReflectiveOperationException {
        Recorder actions = new Recorder();
        List<ContextMenu.Entry> rows = ChannelMenu.muteEntries(actions);
        assertEquals(5, rows.size());
        List<String> keys = new ArrayList<>();
        for (ContextMenu.Entry row : rows) {
            ContextMenu.Item item = assertInstanceOf(ContextMenu.Item.class, row);
            keys.add(key(item.label()));
            item.action().run();
        }
        assertEquals(List.of("chat.eden_ld.menu.mute.5", "chat.eden_ld.menu.mute.15", "chat.eden_ld.menu.mute.60",
                "chat.eden_ld.menu.mute.1440", "chat.eden_ld.menu.mute.forever"), keys);
        assertEquals(List.of("mute 5", "mute 15", "mute 60", "mute 1440", "mute " + HubSettings.MUTED_FOR_GOOD),
                actions.ran);

        // The menu's Mute ▸ row opens exactly these.
        Recorder viaMenu = new Recorder();
        ContextMenu.Submenu mute = (ContextMenu.Submenu) ChannelMenu.entries(channel(null, true), viaMenu).get(1);
        List<ContextMenu.Entry> opened = entriesOf(mute.menu().get());
        assertEquals(kinds(rows), kinds(opened));
        ((ContextMenu.Item) opened.get(2)).action().run();
        assertEquals(List.of("mute 60"), viaMenu.ran);
    }

    @Test
    void theNotificationsSubmenu() throws ReflectiveOperationException {
        Recorder actions = new Recorder();
        List<ContextMenu.Entry> rows = ChannelMenu.notifyEntries(ChannelNotify.ONLY_MENTIONS, actions);
        assertEquals(3, rows.size());
        List<String> keys = new ArrayList<>();
        int checked = 0;
        for (ContextMenu.Entry row : rows) {
            ContextMenu.Check check = assertInstanceOf(ContextMenu.Check.class, row);
            keys.add(key(check.label()));
            checked += check.checked() ? 1 : 0;
            check.action().run();
        }
        // Discord's order, the dot on the level in use.
        assertEquals(List.of("chat.eden_ld.notify.all_messages", "chat.eden_ld.notify.only_mentions",
                "chat.eden_ld.notify.nothing"), keys);
        assertEquals(1, checked);
        assertTrue(((ContextMenu.Check) rows.get(1)).checked());
        assertEquals(List.of("notify ALL_MESSAGES", "notify ONLY_MENTIONS", "notify NOTHING"), actions.ran);

        // The menu's Notifications ▸ row: the level in its label, and these rows behind it.
        Recorder viaMenu = new Recorder();
        ContextMenu.Submenu notify = (ContextMenu.Submenu) ChannelMenu.entries(channel(null, true), viaMenu).get(2);
        String label = notify.label().getString();
        assertTrue(label.endsWith("chat.eden_ld.notify.only_mentions"), label);
        List<ContextMenu.Entry> opened = entriesOf(notify.menu().get());
        assertEquals(kinds(rows), kinds(opened));
        assertTrue(((ContextMenu.Check) opened.get(1)).checked());
        ((ContextMenu.Check) opened.get(2)).action().run();
        assertEquals(List.of("notify NOTHING"), viaMenu.ran);
    }

    // ---------------------------------------------------------------- message

    private static List<String> runAll(List<ContextMenu.Entry> rows, Recorder actions) {
        for (ContextMenu.Entry row : rows) {
            if (row instanceof ContextMenu.Item item) {
                item.action().run();
            }
        }
        return actions.ran;
    }

    private static MessageMenu.Spec message(boolean fromOther, boolean hasSender, boolean staff) {
        return new MessageMenu.Spec(Component.literal("Steve's message"), INK_DIM, fromOther, hasSender, staff,
                Component.literal("Ignore"));
    }

    @Test
    void someoneElsesMessage() {
        Recorder actions = new Recorder();
        List<ContextMenu.Entry> rows = MessageMenu.entries(message(true, true, false), actions);
        assertInstanceOf(ContextMenu.Header.class, rows.get(0));
        assertEquals(INK_DIM, ((ContextMenu.Header) rows.get(0)).color());
        assertEquals(5, rows.size());
        assertEquals(List.of("reply", "copy", "profile", "ignore"), runAll(rows, actions));
        assertEquals("Ignore", ((ContextMenu.Item) rows.get(4)).label().getString());
    }

    @Test
    void someoneElsesMessageForStaff() {
        Recorder actions = new Recorder();
        List<ContextMenu.Entry> rows = MessageMenu.entries(message(true, true, true), actions);
        assertEquals(6, rows.size());
        assertEquals(List.of("reply", "copy", "profile", "ignore", "moderate"), runAll(rows, actions));
    }

    @Test
    void theirOwnMessage() {
        Recorder actions = new Recorder();
        // Staff or not: nothing to reply to, ignore or moderate.
        List<ContextMenu.Entry> rows = MessageMenu.entries(message(false, true, true), actions);
        assertEquals(3, rows.size());
        assertEquals(List.of("copy", "profile"), runAll(rows, actions));
    }

    @Test
    void theServersLine() {
        Recorder actions = new Recorder();
        List<ContextMenu.Entry> rows = MessageMenu.entries(message(false, false, true), actions);
        assertEquals(2, rows.size());
        assertEquals(List.of("copy"), runAll(rows, actions));
    }
}
