package net.ledok.eden_ld.client.chat;

import net.ledok.eden_ld.chat.ChatChannels.Channel;
import net.ledok.eden_ld.chat.ChatMarker;
import net.ledok.eden_ld.client.chat.model.ChatCounters;
import net.ledok.eden_ld.client.chat.model.ChatRules;
import net.ledok.eden_ld.client.chat.model.ChatTabSpec;
import net.ledok.eden_ld.client.chat.model.ToastQueue;
import net.ledok.eden_ld.client.chat.panel.ChatEntry;
import net.ledok.eden_ld.client.chat.panel.ChatPanel;
import net.ledok.eden_ld.client.chat.panel.ChatStore;
import net.ledok.eden_ld.client.chat.panel.ChatViews;
import net.ledok.eden_ld.client.hub.HubState;
import net.ledok.eden_ld.hub.HubPayloads;
import net.ledok.eden_ld.hub.HubSettings;
import net.ledok.eden_ld.hub.HubSettings.ChannelNotify;
import net.minecraft.client.GuiMessage;
import net.minecraft.client.Minecraft;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * What happened in each chat channel that the player hasn't seen ({@code docs/PLAN-chat-activity.md}): messages from
 * others (the dot on the channel's pip) and messages mentioning them (@N), read off {@link ChatStore} — a message
 * counts as seen once the open chat has drawn it ({@link ChatPanel}). Mutes and notification levels come from the
 * player's hub settings (per channel, kept on the server). Lines the chat window isn't showing also go to the closed
 * chat's toasts ({@link #toasts()}, drawn from phase 6 of {@code docs/PLAN-chat-v2.md}).
 */
public final class ChatActivity {

    /** The "All messages" sound at most this often. */
    private static final long SOFT_SOUND_GAP_MS = 1500;
    private static long lastSoftSound;
    private static final ToastQueue TOASTS = new ToastQueue();
    /** Eden's lines at each reconfiguration (vanilla's chat state, until it's put back). */
    private static final Map<Object, ChatStore.Snapshot> SNAPSHOTS = new WeakHashMap<>();

    private ChatActivity() {
    }

    // ---------------------------------------------------------------- the player's settings

    public static ChannelNotify notify(Channel channel) {
        HubPayloads.SettingsData settings = HubState.settingsData;
        return settings == null ? ChannelNotify.ONLY_MENTIONS : ChannelNotify.of(settings.chatNotify(), channel.ordinal());
    }

    /** When the channel's mute ends (epoch minutes), {@link HubSettings#MUTED_FOR_GOOD}, or 0 when not muted. */
    public static int muteEnd(Channel channel) {
        HubPayloads.SettingsData settings = HubState.settingsData;
        if (settings == null || channel.ordinal() >= settings.chatMutes().size()) {
            return 0;
        }
        int end = settings.chatMutes().get(channel.ordinal());
        return end > 0 && end <= System.currentTimeMillis() / 60_000L ? 0 : end;
    }

    public static boolean muted(Channel channel) {
        return muteEnd(channel) != 0;
    }

    // ---------------------------------------------------------------- messages coming in

    /** A message just joined vanilla's chat (ChatComponentMixin): Eden's store takes it too. */
    public static void added(GuiMessage message) {
        Minecraft minecraft = Minecraft.getInstance();
        UUID self = minecraft.player == null ? null : minecraft.player.getUUID();
        boolean mention = self != null && ChatMarker.mentions(message.content(), self);
        ChatStore.Added added = ChatStore.add(message, mention, self, HubState::ignores);
        ChatEntry entry = added.entry();
        // Any new line (server lines too): a scrolled-up chat holds still. A repeat stays where it was.
        if (!added.repeat()) {
            ChatViews.added(entry);
        }
        Channel channel = entry.channel();
        if (entry.seen() || channel == null) {
            return;
        }
        boolean muted = muted(channel);
        if (!mention && muted) {
            // Muted: nothing to catch up on later either.
            entry.markSeen();
            return;
        }
        ChannelNotify notify = notify(channel);
        if (!mention && notify == ChannelNotify.ALL_MESSAGES && !ChatFilter.channelSwitchedOff(channel)) {
            long now = System.currentTimeMillis();
            if (now - lastSoftSound >= SOFT_SOUND_GAP_MS) {
                lastSoftSound = now;
                MentionSound.playSoft();
            }
        }
        if (!ChatViews.main().shows(entry)) {
            TOASTS.offer(entry, channel, notify, muted, entry.mention(), System.currentTimeMillis());
        }
    }

    /** Vanilla's chat was cleared (leaving, or a reconfiguration): so is Eden's. */
    public static void cleared() {
        ChatStore.clear();
        TOASTS.clear();
        ChatViews.resetScroll();
    }

    /** Vanilla saved its chat for a reconfiguration (it clears it next): Eden's lines are kept with it. */
    public static void stored(Object state) {
        SNAPSHOTS.put(state, ChatStore.snapshot());
    }

    /**
     * Vanilla put its chat back after a reconfiguration: Eden's lines come back too, as they were (seen, counted) —
     * never replayed as new, so no sounds, toasts or dots come again.
     */
    public static void restored(Object state) {
        ChatStore.Snapshot snapshot = SNAPSHOTS.remove(state);
        if (snapshot != null) {
            ChatStore.restore(snapshot);
            ChatViews.resetScroll();
        }
    }

    /** The closed chat's toasts, without those of channels read since or set to Nothing. */
    public static ToastQueue toasts() {
        if (!TOASTS.isEmpty()) {
            ChatCounters counters = ChatStore.counters();
            TOASTS.retain(channel -> notify(channel) != ChannelNotify.NOTHING && counters.unseen(channel) > 0);
        }
        return TOASTS;
    }

    // ---------------------------------------------------------------- what the pips show

    /** The dot: messages there not seen yet (never while muted). */
    public static boolean unseen(Channel channel) {
        return !muted(channel) && ChatStore.counters().unseen(channel) > 0;
    }

    /** The System pip's dot: system lines (the server's, deaths, advancements) not seen yet. */
    public static boolean unseenSystem() {
        return ChatStore.counters().unseenSystem() > 0;
    }

    /** The @N badge: mentions there not seen yet (none when its notifications are Nothing). */
    public static int mentions(Channel channel) {
        return notify(channel) == ChannelNotify.NOTHING ? 0 : ChatStore.counters().mentions(channel);
    }

    public static int mentionsTotal() {
        int total = 0;
        for (Channel channel : Channel.values()) {
            total += mentions(channel);
        }
        return total;
    }

    /** Mark as read (the channel menu). */
    public static void markRead(Channel channel) {
        for (ChatEntry entry : ChatStore.entries()) {
            if (entry.channel() == channel) {
                entry.markSeen();
            }
        }
        TOASTS.read(channel);
    }

    /** Mark as read on the System tab: the lines of no channel (the server's, other mods', deaths, advancements). */
    public static void markReadSystem() {
        for (ChatEntry entry : ChatStore.entries()) {
            if (entry.channel() == null) {
                entry.markSeen();
            }
        }
    }

    /** Mark all as read (the All tab): every line, and the closed chat's toasts. */
    public static void markReadAll() {
        for (ChatEntry entry : ChatStore.entries()) {
            entry.markSeen();
        }
        TOASTS.clear();
    }

    /**
     * Whether the closed chat (new look) shows this line although its tab doesn't: a mention the player hasn't seen
     * yet, from a channel whose notifications aren't Nothing, that All would show (not an ignored player's, not a
     * hidden channel's) — until the toasts (phase 6), the only sign on screen of where a ping came from.
     */
    public static boolean unseenMention(ChatEntry entry, ChatRules rules) {
        Channel channel = entry.channel();
        return entry.mention() && !entry.seen() && channel != null && notify(channel) != ChannelNotify.NOTHING
                && ChatTabSpec.ALL.shows(entry.mark(), rules);
    }

    /** Muting clears the dot (mentions stay). */
    public static void clearUnseen(Channel channel) {
        for (ChatEntry entry : ChatStore.entries()) {
            if (entry.channel() == channel && !entry.mention()) {
                entry.markSeen();
            }
        }
    }

    // ---------------------------------------------------------------- jumping to a mention

    /**
     * The oldest unseen mention there (any channel for null), switching channel first when it isn't shown. The new
     * look then shows a tab that has it ({@link ChatTabActions#reveal}) — System can't, nor a closed channel's tab; a
     * mention no tab can show is marked seen and the next one is tried.
     */
    public static boolean jumpToMention(@Nullable Channel only) {
        boolean tabs = ChatFilter.newLook();
        while (true) {
            ChatEntry target = oldestUnseenMention(only);
            if (target == null) {
                return false;
            }
            if (!ChatPanel.shows(target)) {
                // Hidden in this view: its channel shows it.
                ClientChatChannel.select(target.channel());
                if (tabs && !ChatPanel.shows(target) && !ChatTabActions.reveal(target)) {
                    continue;
                }
            }
            ChatPanel.scrollTo(target);
            return true;
        }
    }

    @Nullable
    private static ChatEntry oldestUnseenMention(@Nullable Channel only) {
        for (ChatEntry entry : ChatStore.entries()) {
            Channel channel = entry.channel();
            if (channel != null && entry.mention() && !entry.seen() && (only == null || channel == only)
                    && notify(channel) != ChannelNotify.NOTHING) {
                return entry;
            }
        }
        return null;
    }
}
