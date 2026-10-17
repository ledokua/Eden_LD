package net.ledok.eden_ld.client.chat;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.ledok.eden_ld.chat.ChatChannels.Channel;
import net.ledok.eden_ld.client.chat.model.ChatTabSpec;
import net.ledok.eden_ld.client.chat.model.ChatTabs;
import net.ledok.eden_ld.client.chat.panel.ChatEntry;
import net.ledok.eden_ld.client.chat.panel.ChatViews;
import net.ledok.eden_ld.client.chat.ui.ChatLook;
import net.ledok.eden_ld.client.chat.ui.EdenChatUi;
import net.ledok.eden_ld.client.hub.HubState;
import net.ledok.eden_ld.hub.HubPayloads;
import net.ledok.eden_ld.hub.HubSettings;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * What the new look's chat tabs do ({@code docs/PLAN-chat-v2.md}, phase 5) — for the tab strip, its menus, the keys
 * and the chat's clicks. The main view's {@link ChatTabs} are fed the client's state ({@link #refresh}); a channel's
 * tab is the channel typed into, so showing it picks that channel; hiding and showing tabs are Settings → Chat
 * switches, kept on the server like every hub setting (the strip changes when its answer arrives); a [tag] click is
 * answered here; a mention is brought into a tab that shows it. Nothing here draws or allocates per frame.
 */
public final class ChatTabActions {

    private static final Channel[] CHANNELS = Channel.values();
    /** Each channel's hide switch, in {@link Channel} order. */
    private static final String[] HIDE_KEYS = {HubSettings.CHAT_HIDE_GLOBAL, HubSettings.CHAT_HIDE_LOCAL,
            HubSettings.CHAT_HIDE_PARTY, HubSettings.CHAT_HIDE_GUILD};

    private ChatTabActions() {
    }

    /** The main chat's tabs. */
    public static ChatTabs tabs() {
        return ChatViews.main().tabs();
    }

    /**
     * The tabs follow the client's state: the open channels, those hidden in Settings → Chat, the channel typed into,
     * the All tab's switch (no spying yet: phase 10). Cheap when nothing changed — the open chat calls it every frame,
     * and a new channel state, settings or flags call it too, so the stored mode stays one the strip shows.
     */
    public static void refresh() {
        int open = 0;
        for (Channel channel : CHANNELS) {
            if (ClientChatChannel.open(channel)) {
                open |= 1 << channel.ordinal();
            }
        }
        HubPayloads.SettingsData settings = HubState.settingsData;
        Channel typing = ClientChatChannel.current();
        tabs().refresh(open, settings == null ? 0 : settings.chatHidden(), typing == null ? -1 : typing.ordinal(),
                HubState.chatAllTab(), false);
    }

    /**
     * The view shows {@code tab} (a click on it, Ctrl+Tab). A channel's tab picks that channel to type into, and with
     * {@code jump} — a click, as on 1.2's pips — scrolls to its oldest unseen mention. Open menus close first.
     */
    public static void show(ChatTabSpec tab, boolean jump) {
        int slot = ChatTabs.slot(tab);
        if (slot < 0) {
            return;
        }
        EdenChatUi ui = ChatLook.open();
        if (ui != null) {
            ui.canvas().closeMenus();
        }
        tabs().mode(ChatTabs.modeOf(slot));
        Channel channel = tab.channel();
        if (channel != null) {
            ClientChatChannel.select(channel);
        }
        refresh();
        if (jump && channel != null) {
            ChatActivity.jumpToMention(channel);
        }
    }

    /**
     * Ctrl+Tab (Ctrl+Shift+Tab: back): the strip's next tab, round it — All and System included, hidden channels not.
     * A channel's tab picks its channel, without a mention jump. False with no tabs (not an Eden server).
     */
    public static boolean cycle(boolean back) {
        refresh();
        ChatTabs tabs = tabs();
        int to = tabs.step(tabs.indexOf(tabs.activeSlot()), back);
        if (to < 0) {
            return false;
        }
        show(ChatTabs.spec(tabs.slotAt(to)), false);
        return true;
    }

    // ---------------------------------------------------------------- hiding and showing

    /**
     * Whether the tab menu's "Hide tab" may hide it ({@link ChatTabs#canHide}); All only where the server keeps the
     * All tab's switch (an older one would show it again at once).
     */
    public static boolean canHide(ChatTabSpec tab) {
        int slot = ChatTabs.slot(tab);
        if (slot == ChatTabs.ALL_SLOT && !HubState.chatFlagKnown(HubPayloads.ChatFlags.ALL_TAB)) {
            return false;
        }
        return slot >= 0 && tabs().canHide(slot);
    }

    /**
     * "Hide tab": the channel's hide switch, or the All tab's, goes to the server. Hiding the tab being viewed moves
     * the view first — to All, or to the typing channel's tab when All is the one going or the player has no All tab
     * (Settings → Chat then moves the typing channel off a hidden one, {@link ClientChatChannel#onSettings}).
     */
    public static void hide(ChatTabSpec tab) {
        if (!canHide(tab)) {
            return;
        }
        ChatTabs tabs = tabs();
        int slot = ChatTabs.slot(tab);
        tabs.mode(tabs.afterHiding(slot));
        Channel channel = tab.channel();
        if (channel != null) {
            send(HIDE_KEYS[channel.ordinal()], 1);
        } else {
            send(HubSettings.CHAT_ALL_TAB, 0);
        }
        refresh();
    }

    /** "Show tab" / "Show hidden tabs ▸": the tab's switch back on (the server's answer brings the tab back). */
    public static void unhide(ChatTabSpec tab) {
        Channel channel = tab.channel();
        if (channel != null) {
            send(HIDE_KEYS[channel.ordinal()], 0);
        } else if (tab.kind() == ChatTabSpec.Kind.ALL && HubState.chatFlagKnown(HubPayloads.ChatFlags.ALL_TAB)) {
            send(HubSettings.CHAT_ALL_TAB, 1);
        }
    }

    /**
     * Settings → Chat just hid {@code channel}, the one typed into: with the new look on its tab, the view goes to All
     * (while the player keeps the All tab) before the typing channel moves on — as the tab's own "Hide tab" does.
     */
    static void leaving(Channel channel) {
        ChatTabs tabs = tabs();
        if (ChatFilter.newLook() && tabs.mode() == ChatTabs.Mode.CHANNEL && HubState.chatAllTab()
                && ClientChatChannel.current() == channel) {
            tabs.mode(ChatTabs.Mode.ALL);
        }
    }

    /** The Global tab's "Discord messages" check: Discord's lines in Global (and All), or not. */
    public static void discord(boolean shown) {
        send(HubSettings.CHAT_HIDE_DISCORD, shown ? 0 : 1);
    }

    /** Whether Discord's lines show (the Global tab's check). */
    public static boolean discordShown() {
        HubPayloads.SettingsData settings = HubState.settingsData;
        return settings == null || (settings.chatHidden() & 1 << 4) == 0;
    }

    /**
     * The System tab's "Also in channel tabs" check ({@code chat_system_in_tabs}); only where the server keeps it
     * ({@code HubState.chatFlagKnown(ChatFlags.SYSTEM_IN_TABS)}: the menu leaves the row out otherwise).
     */
    public static void systemInTabs(boolean on) {
        if (HubState.chatFlagKnown(HubPayloads.ChatFlags.SYSTEM_IN_TABS)) {
            send(HubSettings.CHAT_SYSTEM_IN_TABS, on ? 1 : 0);
        }
    }

    private static void send(String key, int value) {
        ClientPlayNetworking.send(new HubPayloads.SettingChange(key, value));
    }

    // ---------------------------------------------------------------- clicks in the chat

    /**
     * A click on a chat line's {@code [Local]} tag in the new look's open chat ({@code ScreenMixin}): the channel
     * switches here, at once — no round trip, and no "Chat channel: Local" line, which would land in every tab. On a
     * channel's tab the tab moves with it; on All or System the action bar says where messages go now. False — the
     * server's {@code /ch} answers, as before — for the 1.2 look, any other command, or a channel not open to the
     * player.
     */
    public static boolean answerIn(String command) {
        if (ChatLook.open() == null || ClientChatChannel.current() == null) {
            return false;
        }
        Channel channel = ChatTabs.tagChannel(command);
        if (channel == null || !ClientChatChannel.open(channel)) {
            return false;
        }
        ClientChatChannel.select(channel);
        if (ChatViews.main().tab().kind() != ChatTabSpec.Kind.CHANNEL) {
            Minecraft.getInstance().gui.setOverlayMessage(Component.translatable("chat.eden_ld.channel_switched",
                    Component.translatable(ChatTabSpec.of(channel).labelKey())
                            .withColor(ClientChatChannel.color(channel) & 0xFFFFFF)), false);
        }
        return true;
    }

    /**
     * A mention to jump to that the view doesn't show, after its channel was picked ({@link ChatActivity#jumpToMention}):
     * the tab that shows it — All while the player keeps it and it shows the line (as 1.2's default view did), else
     * the channel's own tab when it is the one typed into. When no tab can (the channel closed under it, its lines
     * hidden with no All tab), the mention is marked seen, so it can't hold the "↓ Mentions" pill for good.
     *
     * @return whether the view shows it now
     */
    public static boolean reveal(ChatEntry entry) {
        Channel channel = entry.channel();
        ChatTabs.Mode mode = ChatTabs.revealMode(
                HubState.chatAllTab() && ChatTabSpec.ALL.shows(entry.mark(), ChatFilter.rules()),
                channel != null && channel == ClientChatChannel.current());
        if (mode == null) {
            entry.markSeen();
            return false;
        }
        tabs().mode(mode);
        refresh();
        if (ChatViews.main().shows(entry)) {
            return true;
        }
        entry.markSeen();
        return false;
    }
}
