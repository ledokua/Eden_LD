package net.ledok.eden_ld.client.chat;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.ledok.eden_ld.chat.ChatChannels.Channel;
import net.ledok.eden_ld.hub.HubPayloads;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * The client's view of its chat channel (the server decides; see ChatChannels): which one the
 * player types into and which are open. Drives the chat screen's badge. Unknown (null) until
 * an Eden server tells us — the badge then stays hidden.
 */
public final class ClientChatChannel {

    @Nullable
    private static Channel channel;
    private static boolean party;
    private static boolean guild;
    private static boolean emojiPicker;

    private ClientChatChannel() {
    }

    public static void onState(HubPayloads.ChatChannelState state) {
        Channel before = channel;
        channel = Channel.of(state.channel());
        if (before != channel) {
            refreshFocus();
        }
        party = state.party();
        guild = state.guild();
        emojiPicker = state.emojiPicker();
    }

    /** The emoji picker button: permission from the server (standard emojis are always there to pick). */
    public static boolean emojiPicker() {
        return current() != null && emojiPicker;
    }

    public static void reset() {
        channel = null;
    }

    @Nullable
    public static Channel current() {
        return channel != null && ClientPlayNetworking.canSend(HubPayloads.ChatChannelSelect.TYPE) ? channel : null;
    }

    public static boolean open(Channel candidate) {
        return switch (candidate) {
            case GLOBAL, LOCAL -> true;
            case PARTY -> party;
            case GUILD -> guild;
        };
    }

    /** Open, and not hidden in Settings → Chat (a hidden channel can't be picked to write in). */
    public static boolean selectable(Channel candidate) {
        return open(candidate) && !ChatFilter.channelSwitchedOff(candidate);
    }

    /** The next selectable channel (previous with {@code back}); shown at once, confirmed by the server. */
    public static void cycle(boolean back) {
        Channel from = current();
        if (from == null) {
            return;
        }
        Channel[] all = Channel.values();
        int index = from.ordinal();
        for (int step = 0; step < all.length; step++) {
            index = Math.floorMod(index + (back ? -1 : 1), all.length);
            if (selectable(all[index])) {
                break;
            }
        }
        if (all[index] == from) {
            return;
        }
        channel = all[index];
        refreshFocus();
        ClientPlayNetworking.send(new HubPayloads.ChatChannelSelect(index));
    }

    /** Settings changed: a channel the player just hid stops being the one they write in. */
    public static void onSettings() {
        Channel now = current();
        if (now != null && !selectable(now)) {
            cycle(false);
        }
    }

    /** With "only the selected channel" on, switching channel switches what the chat shows. */
    private static void refreshFocus() {
        HubPayloads.SettingsData settings = net.ledok.eden_ld.client.hub.HubState.settingsData;
        if (settings != null && settings.chatFocus()) {
            ChatFilter.refresh();
        }
    }

    /**
     * "!l hi" typed: tells the server the next message goes to LOCAL and returns "hi" — the
     * signed text stays prefix-free. Anything else is returned as is.
     */
    public static String takePrefix(String message) {
        if (message.startsWith("/") || current() == null || !ClientPlayNetworking.canSend(HubPayloads.ChatOneShot.TYPE)) {
            return message;
        }
        Channel typed = Channel.byPrefix(message);
        if (typed == null) {
            return message;
        }
        String rest = message.substring(typed.prefix.length()).trim();
        if (rest.isEmpty()) {
            return message;
        }
        ClientPlayNetworking.send(new HubPayloads.ChatOneShot(typed.ordinal()));
        return rest;
    }

    /** The channel a message typed so far will go to: a "!x " prefix, else the chosen one. */
    @Nullable
    public static Channel effective(String typed) {
        Channel chosen = current();
        if (chosen == null) {
            return null;
        }
        Channel prefixed = Channel.byPrefix(typed);
        return prefixed != null ? prefixed : chosen;
    }

    public static Component badgeLabel(Channel channel) {
        return Component.translatable("chat.eden_ld.badge." + channel.name().toLowerCase(Locale.ROOT));
    }

    /** Badge text color, matching the channel tags in chat. */
    public static int color(Channel channel) {
        return switch (channel) {
            case GLOBAL -> 0xFFFFFFFF;
            case LOCAL -> 0xFFFFFF55;
            case PARTY -> 0xFF55FF55;
            case GUILD -> 0xFF55FFFF;
        };
    }
}
