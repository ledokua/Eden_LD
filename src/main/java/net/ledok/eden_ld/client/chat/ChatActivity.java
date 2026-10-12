package net.ledok.eden_ld.client.chat;

import net.ledok.eden_ld.chat.ChatChannels.Channel;
import net.ledok.eden_ld.chat.ChatMarker;
import net.ledok.eden_ld.client.chat.panel.ChatEntry;
import net.ledok.eden_ld.client.chat.panel.ChatPanel;
import net.ledok.eden_ld.client.chat.panel.ChatStore;
import net.ledok.eden_ld.client.hub.HubState;
import net.ledok.eden_ld.hub.HubPayloads;
import net.ledok.eden_ld.hub.HubSettings;
import net.ledok.eden_ld.hub.HubSettings.ChannelNotify;
import net.minecraft.client.GuiMessage;
import net.minecraft.client.Minecraft;
import org.jetbrains.annotations.Nullable;

/**
 * What happened in each chat channel that the player hasn't seen ({@code docs/PLAN-chat-activity.md}): messages from
 * others (the dot on the channel's pip) and messages mentioning them (@N), read off {@link ChatStore} — a message
 * counts as seen once the open chat has drawn it ({@link ChatPanel}). Mutes and notification levels come from the
 * player's hub settings (per channel, kept on the server).
 */
public final class ChatActivity {

    /** The "All messages" sound at most this often. */
    private static final long SOFT_SOUND_GAP_MS = 1500;
    private static long lastSoftSound;

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
        boolean mention = minecraft.player != null && ChatMarker.mentions(message.content(), minecraft.player.getUUID());
        ChatEntry entry = ChatStore.add(message, mention);
        // Any line (server lines too): a scrolled-up chat holds still.
        ChatPanel.added(entry);
        Channel channel = entry.channel();
        if (entry.seen() || channel == null) {
            return;
        }
        if (!mention && muted(channel)) {
            // Muted: nothing to catch up on later either.
            entry.markSeen();
            return;
        }
        if (!mention && notify(channel) == ChannelNotify.ALL_MESSAGES && !ChatFilter.channelSwitchedOff(channel)) {
            long now = System.currentTimeMillis();
            if (now - lastSoftSound >= SOFT_SOUND_GAP_MS) {
                lastSoftSound = now;
                MentionSound.playSoft();
            }
        }
    }

    // ---------------------------------------------------------------- what the pips show

    /** The dot: messages there not seen yet (never while muted). */
    public static boolean unseen(Channel channel) {
        if (muted(channel)) {
            return false;
        }
        for (ChatEntry entry : ChatStore.entries()) {
            if (entry.channel() == channel && !entry.seen()) {
                return true;
            }
        }
        return false;
    }

    /** The @N badge: mentions there not seen yet (none when its notifications are Nothing). */
    public static int mentions(Channel channel) {
        if (notify(channel) == ChannelNotify.NOTHING) {
            return 0;
        }
        int count = 0;
        for (ChatEntry entry : ChatStore.entries()) {
            if (entry.channel() == channel && entry.mention() && !entry.seen()) {
                count++;
            }
        }
        return count;
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

    /** The oldest unseen mention there (any channel for null), switching channel first when it isn't shown. */
    public static boolean jumpToMention(@Nullable Channel only) {
        ChatEntry target = null;
        for (ChatEntry entry : ChatStore.entries()) {
            Channel channel = entry.channel();
            if (channel != null && entry.mention() && !entry.seen() && (only == null || channel == only)
                    && notify(channel) != ChannelNotify.NOTHING) {
                target = entry;
                break;
            }
        }
        if (target == null) {
            return false;
        }
        if (!ChatPanel.shows(target)) {
            // Hidden in this view: its channel shows it.
            ClientChatChannel.select(target.channel());
        }
        ChatPanel.scrollTo(target);
        return true;
    }
}
