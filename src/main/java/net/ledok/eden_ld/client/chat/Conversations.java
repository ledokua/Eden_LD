package net.ledok.eden_ld.client.chat;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.ledok.eden_ld.chat.ChatMarker;
import net.ledok.eden_ld.client.chat.model.ChatTabSpec;
import net.ledok.eden_ld.client.chat.model.ChatTabs;
import net.ledok.eden_ld.client.chat.panel.ChatEntry;
import net.ledok.eden_ld.client.chat.panel.ChatStore;
import net.ledok.eden_ld.hub.HubPayloads;
import net.ledok.eden_ld.hub.HubSettings.ChannelNotify;
import net.minecraft.client.Minecraft;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The player's private conversations (1.3.94): DMs and group chats, each a tab of the new look's strip
 * ({@link ChatTabs}, {@link ChatTabSpec.Kind#PM}). The server keeps them ({@code chat.dm.DirectMessages}) and sends each
 * one on joining with its stored lines ({@link HubPayloads.ConvData}); a new line arrives as a chat line marked
 * {@link ChatMarker.Kind#DM}, which {@link ChatStore} files here too — one {@link ChatEntry} in both lists, so reading
 * it in All reads it in its tab. Closing a tab hides it (the server remembers); the next line opens it again.
 * <p>
 * Lightweight: what isn't seen yet is counted again only when the chat's lines or their seen state change, and the
 * tabs are put in order again only when a conversation comes, goes or is closed.
 */
public final class Conversations {

    /** Lines kept per conversation on the client (the server keeps 100; a long session may add more). */
    private static final int KEEP = 200;

    private static final class Conv {
        final String id;
        boolean group;
        String name = "";
        UUID owner;
        List<HubPayloads.ConvMember> members = List.of();
        boolean hidden;
        /** When its tab was opened (strip order) and when a line last came (which tabs stay when there are too many). */
        long opened;
        long active;
        final List<ChatEntry> entries = new ArrayList<>();
        ChatTabSpec spec;
        int unseen;
        /** The unseen count the server was last told about: reading everything sends READ once. */
        int reported;
        /** The player's notifications for it (1.3.95): every line pings by default. */
        ChannelNotify notify = ChannelNotify.ALL_MESSAGES;

        Conv(String id) {
            this.id = id;
        }
    }

    private static final Map<String, Conv> BY_ID = new LinkedHashMap<>();
    private static long openCounter;
    /** Bumped when a tab comes, goes, is renamed or closed; and when lines are added (the views pick theirs again). */
    private static int tabsEpoch;
    private static int linesVersion;
    private static final ChatTabSpec[] TABS = new ChatTabSpec[ChatTabs.PM_SLOTS];
    private static int tabCount;
    private static int tabsBuilt = -1;
    /** The store's state the unseen counts were taken at. */
    private static int countedVersion = -1;
    private static int countedSeen = -1;
    private static int countedLines = -1;

    private Conversations() {
    }

    /** Whether this server keeps conversations (an Eden 1.3.94+ server). */
    public static boolean available() {
        return ClientPlayNetworking.canSend(HubPayloads.ConvSend.TYPE);
    }

    /** A new server: nothing is known until it sends its conversations. */
    public static void reset() {
        BY_ID.clear();
        tabsEpoch++;
        linesVersion++;
        tabCount = 0;
        tabsBuilt = -1;
    }

    // ---------------------------------------------------------------- from the server

    /** A conversation as the server sees it now; with {@code full} its stored lines replace those known. */
    public static void onData(HubPayloads.ConvData data) {
        Conv conv = BY_ID.computeIfAbsent(data.conv(), Conv::new);
        boolean wasHidden = conv.hidden || conv.opened == 0;
        conv.group = data.group();
        conv.name = data.name();
        conv.owner = data.owner();
        conv.members = List.copyOf(data.members());
        conv.hidden = data.hidden();
        conv.notify = ChannelNotify.of(data.level());
        if (!conv.hidden && wasHidden) {
            conv.opened = ++openCounter;
        }
        conv.spec = ChatTabSpec.pm(conv.id, title(conv));
        if (data.full()) {
            conv.entries.clear();
            Minecraft minecraft = Minecraft.getInstance();
            UUID self = minecraft.player == null ? null : minecraft.player.getUUID();
            for (HubPayloads.ConvLine line : data.lines()) {
                ChatEntry entry = ChatStore.stored(line.line(), line.time(), self, line.time() <= data.readAt());
                conv.entries.add(entry);
                conv.active = Math.max(conv.active, line.time());
            }
            // Counted on the next tick: reading what's unread then tells the server.
            conv.reported = 0;
            linesVersion++;
        }
        tabsEpoch++;
        ChatTabActions.refresh();
        if (data.focus() && !conv.hidden) {
            ChatTabActions.show(conv.spec, false);
        }
    }

    /** The player is out of the conversation: its tab goes. */
    public static void onGone(String id) {
        if (BY_ID.remove(id) != null) {
            tabsEpoch++;
            linesVersion++;
            ChatTabActions.refresh();
        }
    }

    /** A private line just came into the chat ({@link ChatStore}): it goes in its conversation, whose tab opens. */
    public static void file(ChatEntry entry) {
        ChatMarker.Mark mark = entry.mark();
        if (mark == null || mark.conversation() == null) {
            return;
        }
        Conv conv = BY_ID.get(mark.conversation());
        if (conv == null) {
            return;
        }
        conv.entries.add(entry);
        if (conv.entries.size() > KEEP) {
            conv.entries.remove(0);
        }
        conv.active = System.currentTimeMillis();
        if (conv.hidden) {
            conv.hidden = false;
            conv.opened = ++openCounter;
            tabsEpoch++;
        }
        linesVersion++;
    }

    // ---------------------------------------------------------------- what the chat shows

    /** The lines of a conversation, oldest first (empty when unknown). */
    public static List<ChatEntry> entries(@Nullable String id) {
        Conv conv = id == null ? null : BY_ID.get(id);
        return conv == null ? List.of() : Collections.unmodifiableList(conv.entries);
    }

    /** Bumped when lines come or go: a conversation's view picks its lines again. */
    public static int linesVersion() {
        return linesVersion;
    }

    /** Bumped when the tabs change. */
    public static int tabsEpoch() {
        return tabsEpoch;
    }

    /** The tabs' specs, in strip order ({@link #tabCount()} of them); the array is reused. */
    public static ChatTabSpec[] tabs() {
        buildTabs();
        return TABS;
    }

    public static int tabCount() {
        buildTabs();
        return tabCount;
    }

    /**
     * The open conversations in the order their tabs were opened; with more than {@link ChatTabs#PM_SLOTS}, those
     * with the latest lines.
     */
    private static void buildTabs() {
        if (tabsBuilt == tabsEpoch) {
            return;
        }
        tabsBuilt = tabsEpoch;
        List<Conv> open = new ArrayList<>();
        for (Conv conv : BY_ID.values()) {
            if (!conv.hidden && conv.spec != null) {
                open.add(conv);
            }
        }
        if (open.size() > ChatTabs.PM_SLOTS) {
            open.sort((a, b) -> Long.compare(b.active, a.active));
            open = new ArrayList<>(open.subList(0, ChatTabs.PM_SLOTS));
        }
        open.sort((a, b) -> Long.compare(a.opened, b.opened));
        tabCount = open.size();
        for (int i = 0; i < ChatTabs.PM_SLOTS; i++) {
            TABS[i] = i < tabCount ? open.get(i).spec : null;
        }
    }

    /** Lines from others not seen yet in the conversation (its tab's dot). */
    public static int unseen(@Nullable String id) {
        count();
        Conv conv = id == null ? null : BY_ID.get(id);
        return conv == null ? 0 : conv.unseen;
    }

    /** Whether any conversation has lines not seen yet. */
    public static boolean anyUnseen() {
        count();
        for (Conv conv : BY_ID.values()) {
            if (conv.unseen > 0) {
                return true;
            }
        }
        return false;
    }

    /** Every client tick: the counts follow the chat, and reading a conversation tells the server (once). */
    public static void tick() {
        count();
    }

    private static void count() {
        int version = ChatStore.version();
        int seen = ChatStore.seenEpoch();
        if (version == countedVersion && seen == countedSeen && linesVersion == countedLines) {
            return;
        }
        countedVersion = version;
        countedSeen = seen;
        countedLines = linesVersion;
        boolean send = ClientPlayNetworking.canSend(HubPayloads.ConvAction.TYPE);
        for (Conv conv : BY_ID.values()) {
            int unseen = 0;
            for (ChatEntry entry : conv.entries) {
                if (!entry.seen()) {
                    unseen++;
                }
            }
            conv.unseen = unseen;
            if (unseen == 0 && conv.reported > 0 && send) {
                ClientPlayNetworking.send(new HubPayloads.ConvAction(conv.id, HubPayloads.ConvAction.READ, ""));
            }
            conv.reported = unseen;
        }
    }

    // ---------------------------------------------------------------- one conversation

    /** The tab's name: a group's own, the other player's for a DM. */
    private static String title(Conv conv) {
        if (conv.group) {
            return conv.name;
        }
        UUID self = Minecraft.getInstance().player == null ? null : Minecraft.getInstance().player.getUUID();
        for (HubPayloads.ConvMember member : conv.members) {
            if (!member.uuid().equals(self)) {
                return member.name();
            }
        }
        return conv.name;
    }

    /** The conversation's tab (null when unknown). */
    @Nullable
    public static ChatTabSpec spec(@Nullable String id) {
        Conv conv = id == null ? null : BY_ID.get(id);
        return conv == null ? null : conv.spec;
    }

    public static boolean group(@Nullable String id) {
        Conv conv = id == null ? null : BY_ID.get(id);
        return conv != null && conv.group;
    }

    /** The members, as the server last said. */
    public static List<HubPayloads.ConvMember> members(@Nullable String id) {
        Conv conv = id == null ? null : BY_ID.get(id);
        return conv == null ? List.of() : conv.members;
    }

    /** Whether the player made the group (may rename it). */
    public static boolean owns(@Nullable String id) {
        Conv conv = id == null ? null : BY_ID.get(id);
        Minecraft minecraft = Minecraft.getInstance();
        return conv != null && conv.group && minecraft.player != null && minecraft.player.getUUID().equals(conv.owner);
    }

    /** The player's notifications for it: every line, only @mentions, or nothing (no ping, no dot). */
    public static ChannelNotify notify(@Nullable String id) {
        Conv conv = id == null ? null : BY_ID.get(id);
        return conv == null ? ChannelNotify.ALL_MESSAGES : conv.notify;
    }

    /** Whether its tab shows a dot: lines not seen yet, unless its notifications are Nothing. */
    public static boolean dot(@Nullable String id) {
        return notify(id) != ChannelNotify.NOTHING && unseen(id) > 0;
    }

    /** The other player of a DM (null for a group). */
    @Nullable
    public static HubPayloads.ConvMember partner(@Nullable String id) {
        Conv conv = id == null ? null : BY_ID.get(id);
        if (conv == null || conv.group) {
            return null;
        }
        UUID self = Minecraft.getInstance().player == null ? null : Minecraft.getInstance().player.getUUID();
        for (HubPayloads.ConvMember member : conv.members) {
            if (!member.uuid().equals(self)) {
                return member;
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- the player's actions

    /** «Написати»: the DM with this player (the server answers with its tab, shown). */
    public static void write(UUID player) {
        if (available()) {
            ClientPlayNetworking.send(new HubPayloads.ConvOpen(player));
        }
    }

    /** A line typed in the conversation's tab. */
    public static void send(String id, String text) {
        ClientPlayNetworking.send(new HubPayloads.ConvSend(id, text));
    }

    /** Close the tab: hidden for this player (the server remembers) until the next line. */
    public static void close(String id) {
        Conv conv = BY_ID.get(id);
        if (conv == null) {
            return;
        }
        conv.hidden = true;
        tabsEpoch++;
        ClientPlayNetworking.send(new HubPayloads.ConvAction(id, HubPayloads.ConvAction.HIDE, ""));
        ChatTabActions.refresh();
    }

    /** Mark as read: every line of the conversation. */
    public static void markRead(String id) {
        Conv conv = BY_ID.get(id);
        if (conv != null) {
            for (ChatEntry entry : conv.entries) {
                entry.markSeen();
            }
        }
    }

    /** Mark all as read reaches the conversations' stored lines too. */
    public static void markAllRead() {
        for (Conv conv : BY_ID.values()) {
            for (ChatEntry entry : conv.entries) {
                entry.markSeen();
            }
        }
    }

    /** Notifications ▸ on its tab: kept here at once, and by the server for the next join. */
    public static void setNotify(String id, ChannelNotify level) {
        Conv conv = BY_ID.get(id);
        if (conv == null || conv.notify == level) {
            return;
        }
        conv.notify = level;
        ClientPlayNetworking.send(new HubPayloads.ConvAction(id, HubPayloads.ConvAction.NOTIFY,
                Integer.toString(level.ordinal())));
    }

    public static void action(String id, int action, String text) {
        ClientPlayNetworking.send(new HubPayloads.ConvAction(id, action, text));
    }
}
