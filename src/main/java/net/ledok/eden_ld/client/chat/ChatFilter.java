package net.ledok.eden_ld.client.chat;

import net.ledok.eden_ld.chat.ChatChannels;
import net.ledok.eden_ld.chat.ChatMarker;
import net.ledok.eden_ld.client.hub.HubState;
import net.ledok.eden_ld.hub.HubPayloads;
import net.ledok.eden_ld.hub.HubSettings;
import net.minecraft.client.GuiMessage;
import net.minecraft.client.Minecraft;

/**
 * What the chat window shows (Settings → Chat): lines the server marked ({@link ChatMarker}) of
 * a hidden kind are left out of the window but kept, so turning the kind back on — or switching
 * channel with "only the selected channel" — brings them back. Lines from (or, for deaths and
 * advancements, about) an ignored player hide the same way. Unmarked lines always show.
 */
public final class ChatFilter {

    private ChatFilter() {
    }

    /** Whether the chat leaves this line out now. */
    public static boolean hidden(GuiMessage message) {
        return hidden(ChatMarker.find(message.content()));
    }

    /** Whether the chat leaves a line with this marker out now (the panel keeps each line's marker). */
    public static boolean hidden(@org.jetbrains.annotations.Nullable ChatMarker.Mark mark) {
        if (mark == null) {
            return false;
        }
        // The System view: deaths and advancements (by their own settings below) besides unmarked lines, no chat.
        if (ClientChatChannel.systemView() && mark.kind() != ChatMarker.Kind.DEATH
                && mark.kind() != ChatMarker.Kind.ADVANCEMENT) {
            return true;
        }
        Minecraft minecraft = Minecraft.getInstance();
        // The player's own lines ignore the hide switches — but "only the selected channel"
        // shows only that channel, theirs included.
        boolean own = mark.sender() != null && minecraft.player != null && mark.sender().equals(minecraft.player.getUUID());
        if (!own && HubState.ignores(mark.sender())) {
            return true;
        }
        HubPayloads.SettingsData settings = HubState.settingsData;
        if (settings == null) {
            return false;
        }
        return switch (mark.kind()) {
            // A death or advancement carries its subject only for the ignore list; the switches apply to the player's own too.
            case DEATH -> !shown(settings.chatDeaths(), mark.team());
            case ADVANCEMENT -> !shown(settings.chatAdvancements(), mark.team());
            case GLOBAL -> channelHidden(settings, 0, ChatChannels.Channel.GLOBAL, own);
            case LOCAL -> channelHidden(settings, 1, ChatChannels.Channel.LOCAL, own);
            case PARTY -> channelHidden(settings, 2, ChatChannels.Channel.PARTY, own);
            case GUILD -> channelHidden(settings, 3, ChatChannels.Channel.GUILD, own);
            case DISCORD -> channelHidden(settings, 4, ChatChannels.Channel.GLOBAL, own);
        };
    }

    /** Whether the player hid this channel (Settings → Chat): cycling skips it. */
    public static boolean channelSwitchedOff(ChatChannels.Channel channel) {
        HubPayloads.SettingsData settings = HubState.settingsData;
        return settings != null && (settings.chatHidden() & (1 << channel.ordinal())) != 0;
    }

    private static boolean shown(int messagesFrom, boolean team) {
        return switch (HubSettings.MessagesFrom.of(messagesFrom)) {
            case EVERYONE -> true;
            case TEAM -> team;
            case NOBODY -> false;
        };
    }

    /**
     * Hidden by its own switch (unless it's the channel typed into — picked on its pip on purpose), or — with "only
     * the selected channel" — not the one typed into.
     */
    private static boolean channelHidden(HubPayloads.SettingsData settings, int bit, ChatChannels.Channel channel,
                                         boolean own) {
        ChatChannels.Channel selected = ClientChatChannel.current();
        // The Discord switch (bit 4) isn't Global's own: it keeps Discord lines hidden.
        boolean picked = selected == channel && bit == channel.ordinal();
        if (!own && (settings.chatHidden() & (1 << bit)) != 0 && !picked) {
            return true;
        }
        return settings.chatFocus() && selected != null && selected != channel;
    }

    /** The rules changed: the chat shows the lines they allow now (hidden lines come back, or go). */
    public static void refresh() {
        net.ledok.eden_ld.client.chat.panel.ChatPanel.invalidate();
    }
}
