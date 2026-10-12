package net.ledok.eden_ld.client.chat;

import net.ledok.eden_ld.chat.ChatChannels.Channel;
import net.ledok.eden_ld.chat.ChatMarker;
import net.ledok.eden_ld.client.hub.HubNotifications;
import net.ledok.eden_ld.client.hub.HubState;
import net.ledok.eden_ld.hub.HubPayloads;
import net.ledok.eden_ld.hub.HubSettings;
import net.ledok.eden_ld.hub.HubSettings.ChannelNotify;
import net.ledok.eden_ld.mixin.ChatComponentAccessor;
import net.minecraft.client.GuiMessage;
import net.minecraft.client.GuiMessageTag;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * What happened in each chat channel that the player hasn't seen ({@code docs/PLAN-chat-activity.md}): lines from
 * others (the dot on the channel's pip) and lines mentioning them (@N). A line counts as seen once the open chat
 * window has drawn it. Mentions get their own tag: the chat draws its stripe in the notification color, and the line
 * is tinted and stays on the HUD longer (ChatComponentMixin). Mutes and notification levels come from the player's
 * hub settings (per channel, kept on the server).
 */
public final class ChatActivity {

    /** The tag's log name, which also tells mention lines apart. */
    private static final String MENTION_LOG = "Eden mention";
    /** How much longer a mention stays on the HUD (ticks). */
    public static final int MENTION_EXTRA_TICKS = 400;
    /** The "All messages" sound at most this often. */
    private static final long SOFT_SOUND_GAP_MS = 1500;

    private static final Map<Channel, Set<GuiMessage>> UNSEEN = new EnumMap<>(Channel.class);
    /** Unseen mentions, oldest first. */
    private static final Map<Channel, List<GuiMessage>> MENTIONS = new EnumMap<>(Channel.class);
    /** Which message each chat window line belongs to (the window rebuilds lines; old ones fall away). */
    private static final Map<GuiMessage.Line, GuiMessage> LINES = new WeakHashMap<>();
    private static long lastSoftSound;

    static {
        for (Channel channel : Channel.values()) {
            UNSEEN.put(channel, Collections.newSetFromMap(new IdentityHashMap<>()));
            MENTIONS.put(channel, new ArrayList<>());
        }
    }

    private ChatActivity() {
    }

    public static void reset() {
        UNSEEN.values().forEach(Set::clear);
        MENTIONS.values().forEach(List::clear);
        LINES.clear();
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

    // ---------------------------------------------------------------- lines coming in

    /** The tag a line gets when it mentions this player (null: leave the line's own tag). */
    @Nullable
    public static GuiMessageTag mentionTag(Component content) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || ClientChatChannel.current() == null
                || !ChatMarker.mentions(content, minecraft.player.getUUID())) {
            return null;
        }
        ChatMarker.Mark mark = ChatMarker.find(content);
        if (mark != null && minecraft.player.getUUID().equals(mark.sender())) {
            return null;
        }
        return new GuiMessageTag(HubNotifications.color() & 0xFFFFFF, null,
                Component.translatable("chat.eden_ld.mention.tag"), MENTION_LOG);
    }

    public static boolean isMention(@Nullable GuiMessageTag tag) {
        return tag != null && MENTION_LOG.equals(tag.logTag());
    }

    /** A message just joined the chat (ChatComponentMixin): someone else's channel line counts until it's seen. */
    public static void added(GuiMessage message) {
        Channel channel = channelOf(message.content());
        Minecraft minecraft = Minecraft.getInstance();
        if (channel == null || minecraft.player == null) {
            return;
        }
        ChatMarker.Mark mark = ChatMarker.find(message.content());
        if (mark == null || minecraft.player.getUUID().equals(mark.sender()) || HubState.ignores(mark.sender())) {
            return;
        }
        prune();
        boolean mention = isMention(message.tag());
        if (mention && notify(channel) != ChannelNotify.NOTHING) {
            MENTIONS.get(channel).add(message);
        }
        if (muted(channel)) {
            return;
        }
        UNSEEN.get(channel).add(message);
        if (!mention && notify(channel) == ChannelNotify.ALL_MESSAGES && !ChatFilter.channelSwitchedOff(channel)) {
            long now = System.currentTimeMillis();
            if (now - lastSoftSound >= SOFT_SOUND_GAP_MS) {
                lastSoftSound = now;
                MentionSound.playSoft();
            }
        }
    }

    /** A chat window line was made for a message (it is rebuilt on every relayout). */
    public static void line(GuiMessage.Line line, GuiMessage message) {
        LINES.put(line, message);
    }

    /** The open chat window drew this line: its message has been seen. */
    public static void drawn(GuiMessage.Line line) {
        GuiMessage message = LINES.get(line);
        if (message != null) {
            seen(message);
        }
    }

    private static void seen(GuiMessage message) {
        for (Channel channel : Channel.values()) {
            UNSEEN.get(channel).remove(message);
            MENTIONS.get(channel).removeIf(mention -> mention == message);
        }
    }

    /** Messages that fell out of the chat's history (it keeps 100) can't be seen any more. */
    private static void prune() {
        ChatComponent chat = Minecraft.getInstance().gui.getChat();
        List<GuiMessage> kept = ((ChatComponentAccessor) chat).eden_ld$allMessages();
        if (kept.size() < 100) {
            return;
        }
        Set<GuiMessage> present = Collections.newSetFromMap(new IdentityHashMap<>());
        present.addAll(kept);
        for (Channel channel : Channel.values()) {
            UNSEEN.get(channel).retainAll(present);
            MENTIONS.get(channel).removeIf(message -> !present.contains(message));
        }
    }

    @Nullable
    private static Channel channelOf(Component content) {
        ChatMarker.Mark mark = ChatMarker.find(content);
        if (mark == null) {
            return null;
        }
        return switch (mark.kind()) {
            case GLOBAL, DISCORD -> Channel.GLOBAL;
            case LOCAL -> Channel.LOCAL;
            case PARTY -> Channel.PARTY;
            case GUILD -> Channel.GUILD;
            default -> null;
        };
    }

    // ---------------------------------------------------------------- what the pips show

    /** The dot: new lines there not seen yet (never while muted). */
    public static boolean unseen(Channel channel) {
        return !muted(channel) && !UNSEEN.get(channel).isEmpty();
    }

    /** The @N badge: mentions there not seen yet (none when its notifications are Nothing). */
    public static int mentions(Channel channel) {
        return notify(channel) == ChannelNotify.NOTHING ? 0 : MENTIONS.get(channel).size();
    }

    public static int mentionsTotal() {
        int total = 0;
        for (Channel channel : Channel.values()) {
            total += mentions(channel);
        }
        return total;
    }

    /** Mark as read (the channel menu), and what muting does to the dot. */
    public static void markRead(Channel channel) {
        UNSEEN.get(channel).clear();
        MENTIONS.get(channel).clear();
    }

    public static void clearUnseen(Channel channel) {
        UNSEEN.get(channel).clear();
    }

    // ---------------------------------------------------------------- jumping to a mention

    /** The oldest unseen mention there (any channel for null), switching channel first when it isn't shown. */
    public static boolean jumpToMention(@Nullable Channel only) {
        GuiMessage target = null;
        Channel in = null;
        for (Channel channel : Channel.values()) {
            if (only != null && channel != only || mentions(channel) == 0) {
                continue;
            }
            GuiMessage first = MENTIONS.get(channel).get(0);
            if (target == null || first.addedTime() < target.addedTime()) {
                target = first;
                in = channel;
            }
        }
        if (target == null) {
            return false;
        }
        if (!scrollTo(target)) {
            // Hidden in this view: its channel shows it (the chat lays itself out again at once).
            ClientChatChannel.select(in);
            scrollTo(target);
        }
        return true;
    }

    /** Scrolls the chat window so the message's lines sit in the middle; false when it isn't in the window. */
    private static boolean scrollTo(GuiMessage message) {
        ChatComponent chat = Minecraft.getInstance().gui.getChat();
        List<GuiMessage.Line> lines = ((ChatComponentAccessor) chat).eden_ld$trimmedMessages();
        int index = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (LINES.get(lines.get(i)) == message) {
                index = i;
            }
        }
        if (index < 0) {
            return false;
        }
        int page = chat.getLinesPerPage();
        chat.resetChatScroll();
        chat.scrollChat(Math.max(0, Math.min(index - page / 2, lines.size() - page)));
        return true;
    }
}
