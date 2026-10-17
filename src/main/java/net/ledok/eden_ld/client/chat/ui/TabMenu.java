package net.ledok.eden_ld.client.chat.ui;

import net.ledok.eden_ld.chat.ChatChannels.Channel;
import net.ledok.eden_ld.client.chat.ChannelMenu;
import net.ledok.eden_ld.client.chat.ChatActivity;
import net.ledok.eden_ld.client.chat.ChatTabActions;
import net.ledok.eden_ld.client.chat.model.ChatTabSpec;
import net.ledok.eden_ld.client.chat.model.ChatTabs;
import net.ledok.eden_ld.client.hub.HubScreen;
import net.ledok.eden_ld.client.hub.HubState;
import net.ledok.eden_ld.hub.HubPayloads;
import net.ledok.eden_ld.hub.HubSettings.ChannelNotify;
import net.ledok.vectorlib.client.canvas.widget.ContextMenu;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The menu a right-click on a tab of the new look's strip opens ({@code docs/PLAN-chat-v2.md}, phase 5), a VectorLib
 * {@link ContextMenu} on the chat's canvas ({@link ChatCanvas#openTabMenu}) — the input glyph's right-click opens the
 * typing channel's. Built like {@link ChannelMenu}'s: pure rows ({@link #entries}) from a {@link Spec} taken when it
 * opens, and the {@link Actions} the rows run.
 * <ul>
 *   <li>A channel's tab: the channel's own rows ({@link ChannelMenu#core}: Mute ▸, Notifications ▸, Mark as read);
 *   on Global the "Discord messages" check; Hide tab (greyed when it's the last of All and the channel tabs) — or,
 *   on a hidden channel showing while it's the active one, Show tab.</li>
 *   <li>System: its name, Mark as read, "Also in channel tabs" (where the server keeps that switch).</li>
 *   <li>All: its name, Mark all as read, Hide tab (greyed where the server doesn't keep the All tab's switch).</li>
 *   <li>Spy (phase 10): its name in the danger color.</li>
 * </ul>
 * Then Show hidden tabs ▸ while something is hidden, a line and Chat settings. Hiding and showing are Settings → Chat
 * switches: the strip changes when the server's answer arrives.
 */
public final class TabMenu {

    private TabMenu() {
    }

    /**
     * What the menu shows, taken when it opens.
     *
     * @param channel      a channel tab's own rows ({@link ChannelMenu#spec}); null for the others
     * @param discordShown Global's "Discord messages" check; null on other tabs
     * @param hidden       the tab is hidden (a channel showing only while it's the active tab): Show tab, not Hide
     * @param hideable     Hide tab can hide it ({@code ChatTabActions.canHide})
     * @param systemInTabs System's "Also in channel tabs" check; null on other tabs, and where the server doesn't
     *                     keep that switch
     * @param showable     the hidden tabs Show hidden tabs ▸ offers (slots, strip order); empty: no such row
     * @param unread       Mark as read (System) or Mark all as read (All) has something to do
     * @param inkDim       the theme's dim text (All's name)
     * @param danger       the theme's danger color (Spy's name)
     */
    public record Spec(ChatTabSpec tab, @Nullable ChannelMenu.Spec channel, @Nullable Boolean discordShown,
                       boolean hidden, boolean hideable, @Nullable Boolean systemInTabs, int[] showable,
                       boolean unread, int inkDim, int danger) {
    }

    /** What the rows do; a channel tab's own rows run {@link ChannelMenu.Actions}. */
    public interface Actions extends ChannelMenu.Actions {
        void hide();

        /** "Show tab": the hidden tab's switch back on. */
        void unhide();

        /** A row of Show hidden tabs ▸. */
        void show(int slot);

        void discord(boolean shown);

        void systemInTabs(boolean on);

        void markReadSystem();

        void markReadAll();
    }

    private static Component tr(String key) {
        return Component.translatable("chat.eden_ld.menu." + key);
    }

    /** The tab's rows. */
    public static List<ContextMenu.Entry> entries(Spec spec, Actions actions) {
        List<ContextMenu.Entry> entries;
        ChatTabSpec tab = spec.tab();
        switch (tab.kind()) {
            case CHANNEL -> {
                entries = ChannelMenu.core(Objects.requireNonNull(spec.channel()), actions);
                Boolean discord = spec.discordShown();
                if (discord != null) {
                    boolean shown = discord;
                    entries.add(new ContextMenu.Check(tr("discord"), shown, () -> actions.discord(!shown), true));
                }
                entries.add(hideRow(spec, actions));
            }
            case SYSTEM -> {
                entries = new ArrayList<>();
                entries.add(new ContextMenu.Header(Component.translatable("chat.eden_ld.pip.system.name"),
                        tab.color()));
                entries.add(new ContextMenu.Item(tr("read"), actions::markReadSystem, spec.unread()));
                Boolean inTabs = spec.systemInTabs();
                if (inTabs != null) {
                    boolean on = inTabs;
                    entries.add(new ContextMenu.Check(tr("system_in_tabs"), on, () -> actions.systemInTabs(!on), true));
                }
            }
            case ALL -> {
                entries = new ArrayList<>();
                entries.add(new ContextMenu.Header(Component.translatable(tab.labelKey()), spec.inkDim()));
                entries.add(new ContextMenu.Item(tr("read_all"), actions::markReadAll, spec.unread()));
                entries.add(hideRow(spec, actions));
            }
            default -> {
                // Spy (phase 10 adds "Stop spying" with its command); PM tabs come in phase 5b.
                entries = new ArrayList<>();
                entries.add(new ContextMenu.Header(Component.translatable(tab.labelKey()), spec.danger()));
            }
        }
        if (spec.showable().length > 0) {
            int[] slots = spec.showable();
            entries.add(new ContextMenu.Submenu(tr("show_tabs"), () -> ChatMenus.build(showEntries(slots, actions)),
                    true));
        }
        entries.add(new ContextMenu.Separator());
        entries.add(new ContextMenu.Item(tr("settings"), actions::settings, true));
        return entries;
    }

    /** Hide tab, or Show tab on a hidden one. */
    private static ContextMenu.Entry hideRow(Spec spec, Actions actions) {
        return spec.hidden() ? new ContextMenu.Item(tr("show_tab"), actions::unhide, true)
                : new ContextMenu.Item(tr("hide_tab"), actions::hide, spec.hideable());
    }

    /** Show hidden tabs ▸: a row per hidden tab, by its name. */
    static List<ContextMenu.Entry> showEntries(int[] slots, Actions actions) {
        List<ContextMenu.Entry> entries = new ArrayList<>();
        for (int slot : slots) {
            entries.add(new ContextMenu.Item(Component.translatable(ChatTabs.spec(slot).labelKey()),
                    () -> actions.show(slot), true));
        }
        return entries;
    }

    // ---------------------------------------------------------------- the game's side

    /** The menu for {@code tab} as it is now, in the theme's dim text and danger colors. */
    public static Spec spec(ChatTabSpec tab, int inkDim, int danger) {
        ChatTabs tabs = ChatTabActions.tabs();
        int slot = ChatTabs.slot(tab);
        Channel channel = tab.channel();
        ChannelMenu.Spec channelSpec = channel != null ? ChannelMenu.spec(channel, inkDim) : null;
        int count = 0;
        for (int s = 0; s < ChatTabs.SLOTS; s++) {
            if (tabs.showable(s)) {
                count++;
            }
        }
        int[] showable = new int[count];
        count = 0;
        for (int s = 0; s < ChatTabs.SLOTS; s++) {
            if (tabs.showable(s)) {
                showable[count++] = s;
            }
        }
        boolean unread = switch (tab.kind()) {
            case CHANNEL -> channelSpec != null && channelSpec.unread();
            case SYSTEM -> ChatActivity.unseenSystem();
            case ALL -> anyUnread();
            default -> false;
        };
        Boolean systemInTabs = tab.kind() == ChatTabSpec.Kind.SYSTEM
                && HubState.chatFlagKnown(HubPayloads.ChatFlags.SYSTEM_IN_TABS) ? HubState.chatSystemInTabs() : null;
        return new Spec(tab, channelSpec, channel == Channel.GLOBAL ? ChatTabActions.discordShown() : null,
                slot >= 0 && tabs.hidden(slot), ChatTabActions.canHide(tab), systemInTabs, showable, unread, inkDim,
                danger);
    }

    /** Anything for Mark all as read: a channel's dot or mentions, or System's dot. */
    private static boolean anyUnread() {
        for (Channel channel : Channel.values()) {
            if (ChatActivity.unseen(channel) || ChatActivity.mentions(channel) > 0) {
                return true;
            }
        }
        return ChatActivity.unseenSystem();
    }

    /** The rows acting on {@code tab}. */
    public static Actions actions(ChatTabSpec tab) {
        Channel channel = tab.channel();
        ChannelMenu.Actions own = channel != null ? ChannelMenu.actions(channel) : null;
        return new Actions() {
            @Override
            public void mute(int minutes) {
                if (own != null) {
                    own.mute(minutes);
                }
            }

            @Override
            public void notify(ChannelNotify level) {
                if (own != null) {
                    own.notify(level);
                }
            }

            @Override
            public void markRead() {
                if (own != null) {
                    own.markRead();
                }
            }

            @Override
            public void settings() {
                HubState.openSettingsSection = "chat";
                HubScreen.open("settings");
            }

            @Override
            public void hide() {
                ChatTabActions.hide(tab);
            }

            @Override
            public void unhide() {
                ChatTabActions.unhide(tab);
            }

            @Override
            public void show(int slot) {
                ChatTabActions.unhide(ChatTabs.spec(slot));
            }

            @Override
            public void discord(boolean shown) {
                ChatTabActions.discord(shown);
            }

            @Override
            public void systemInTabs(boolean on) {
                ChatTabActions.systemInTabs(on);
            }

            @Override
            public void markReadSystem() {
                ChatActivity.markReadSystem();
            }

            @Override
            public void markReadAll() {
                ChatActivity.markReadAll();
            }
        };
    }
}
