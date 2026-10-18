package net.ledok.eden_ld.client.chat;

import net.ledok.eden_ld.chat.ChatChannels;
import net.ledok.eden_ld.chat.ChatMarker;
import net.ledok.eden_ld.client.chat.model.ChatRules;
import net.ledok.eden_ld.client.chat.model.ChatTabSpec;
import net.ledok.eden_ld.client.chat.ui.ChatLook;
import net.ledok.eden_ld.client.hub.HubState;
import net.ledok.eden_ld.hub.HubPayloads;
import net.minecraft.client.Minecraft;

import java.util.UUID;

/**
 * What the chat window shows (Settings → Chat): lines the server marked ({@link ChatMarker}) of a hidden kind are left
 * out of the window but kept, so turning the kind back on — or switching channel with "only the selected channel" —
 * brings them back. Lines from (or, for deaths and advancements, about) an ignored player hide the same way. Unmarked
 * lines always show, but in the new look's channel tabs only with {@code chat_system_in_tabs} on, and system-only ones
 * (other admins' command echoes, routine notices: {@link net.ledok.eden_ld.chat.SystemOnlyRules}) in its System tab
 * alone. The rules themselves are {@link ChatTabSpec#shows}; this class hands them the player's state.
 */
public final class ChatFilter {

    private ChatFilter() {
    }

    /**
     * Whether the chat reads its view from the new look's tabs ({@code ChatView.tab()}, {@link #rules()}): the open
     * chat as its screen was built — a chat screen keeps its look until it's opened again, even when the setting
     * changes under it — and the closed chat (no screen) by the setting.
     */
    public static boolean newLook() {
        return Minecraft.getInstance().gui.getChat().isChatFocused() ? ChatLook.open() != null : ChatLook.enabled();
    }

    /**
     * The view the 1.2 look's pips pick: System while its pip is lit, the channel typed into with "only the selected
     * channel" on, else everything.
     */
    public static ChatTabSpec currentTab() {
        if (ClientChatChannel.systemView()) {
            return ChatTabSpec.SYSTEM;
        }
        HubPayloads.SettingsData settings = HubState.settingsData;
        ChatChannels.Channel typing = ClientChatChannel.current();
        return settings != null && settings.chatFocus() && typing != null ? ChatTabSpec.of(typing) : ChatTabSpec.ALL;
    }

    /** The player's state, as the filters read it. */
    public static ChatRules rules() {
        Minecraft minecraft = Minecraft.getInstance();
        UUID self = minecraft.player == null ? null : minecraft.player.getUUID();
        ChatChannels.Channel typing = ClientChatChannel.current();
        HubPayloads.SettingsData settings = HubState.settingsData;
        // System-only lines go to the System tab alone with the new look; the 1.2 look shows them as before.
        boolean newLook = newLook();
        if (settings == null) {
            return ChatRules.none(self, HubState::ignores, typing, newLook);
        }
        // The 1.2 look's channel view ("only the selected channel") always had the system lines.
        return new ChatRules(self, HubState::ignores, settings.chatHidden(), settings.chatDeaths(),
                settings.chatAdvancements(), typing, !newLook || HubState.chatSystemInTabs(), newLook);
    }

    /** Whether the player hid this channel (Settings → Chat): cycling skips it. */
    public static boolean channelSwitchedOff(ChatChannels.Channel channel) {
        HubPayloads.SettingsData settings = HubState.settingsData;
        return settings != null && (settings.chatHidden() & (1 << channel.ordinal())) != 0;
    }

    /** The rules changed: the chat shows the lines they allow now (hidden lines come back, or go). */
    public static void refresh() {
        net.ledok.eden_ld.client.chat.panel.ChatPanel.invalidate();
    }
}
