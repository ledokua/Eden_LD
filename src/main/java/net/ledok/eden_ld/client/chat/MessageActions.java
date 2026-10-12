package net.ledok.eden_ld.client.chat;

import net.ledok.eden_ld.chat.ChatChannels.Channel;
import net.ledok.eden_ld.client.chat.panel.ChatEntry;
import net.ledok.eden_ld.client.chat.panel.MessageText;
import net.ledok.eden_ld.client.hub.HubScreen;
import net.ledok.eden_ld.client.hub.HubState;
import net.ledok.eden_ld.client.hub.Ignoring;
import net.ledok.eden_ld.client.hub.ModerationScreen;
import net.ledok.eden_ld.hub.HubPayloads;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.network.chat.Component;

/**
 * What can be done with a chat message ({@code docs/PLAN-custom-chat.md}, phase 2): reply (its channel, "@Name " in
 * the input), copy its text, open the writer's profile, ignore them, and for staff open them in the Moderation window.
 * The hover toolbar and the right-click menu both come here.
 */
public final class MessageActions {

    private MessageActions() {
    }

    /** Someone else's line (not the server's, not the player's own). */
    public static boolean fromOther(ChatEntry entry) {
        return entry.sender() != null && !entry.own();
    }

    /** Staff who may punish (the Moderation window's Players section). */
    public static boolean staff() {
        return ClientChatChannel.moderate() || HubState.homeData != null
                && (HubState.homeData.staff() & HubPayloads.HomeData.STAFF_PUNISH) != 0;
    }

    /** Into the message's channel, with "@Name " in front of what's typed. */
    public static void reply(ChatEntry entry) {
        Channel channel = entry.channel();
        if (channel != null && ClientChatChannel.open(channel)) {
            ClientChatChannel.select(channel);
        }
        String name = MessageText.senderName(entry);
        if (name != null && Minecraft.getInstance().screen instanceof ChatScreen chat) {
            ((net.ledok.eden_ld.mixin.ChatScreenAccessor) chat).eden_ld$insertText("@" + name + " ", false);
        }
    }

    public static void copy(ChatEntry entry) {
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.keyboardHandler.setClipboard(MessageText.copyText(entry));
        minecraft.gui.setOverlayMessage(Component.translatable("chat.eden_ld.action.copied"), false);
    }

    public static void profile(ChatEntry entry) {
        if (entry.sender() != null) {
            HubScreen.openProfileOf(entry.sender());
        }
    }

    public static void ignore(ChatEntry entry) {
        if (entry.sender() != null) {
            Ignoring.toggle(entry.sender());
        }
    }

    public static void moderate(ChatEntry entry) {
        String name = MessageText.senderName(entry);
        if (entry.sender() != null && name != null) {
            ModerationScreen.openPlayerFrom(null, entry.sender(), name);
        }
    }
}
