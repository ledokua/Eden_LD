package net.ledok.eden_ld.client.chat;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.ledok.eden_ld.chat.ChatChannels.Channel;
import net.ledok.eden_ld.client.chat.model.ChatTabSpec;
import net.ledok.eden_ld.client.chat.model.SelectGuard;
import net.ledok.eden_ld.client.chat.panel.ChatViews;
import net.ledok.eden_ld.hub.HubPayloads;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * The client's view of its chat channel (the server decides; see ChatChannels): which one the
 * player types into and which are open. Drives the chat screen's badge. Unknown (null) until
 * an Eden server tells us — the badge then stays hidden. A pick is shown at once; a state the
 * server sent before it handled the pick can't flip it back ({@link SelectGuard}).
 */
public final class ClientChatChannel {

    @Nullable
    private static Channel channel;
    private static boolean party;
    private static boolean guild;
    private static boolean emojiPicker;
    private static boolean moderate;
    /** The System pip: the chat shows only system lines (the server's, deaths, advancements); typing is unchanged. */
    private static boolean systemView;
    private static final SelectGuard GUARD = new SelectGuard();
    /** Bumped whenever the typing channel changes ({@link #typingEpoch}). */
    private static int typingEpoch;

    private ClientChatChannel() {
    }

    public static void onState(HubPayloads.ChatChannelState state) {
        party = state.party();
        guild = state.guild();
        emojiPicker = state.emojiPicker();
        moderate = state.moderate();
        Channel server = Channel.of(state.channel());
        // A state built before the server handled our pick names the old channel: held back (the rest applies).
        int picked = GUARD.pending();
        boolean pickedOpen = picked < 0 || open(Channel.of(picked));
        if (GUARD.accept(server.ordinal(), pickedOpen, System.currentTimeMillis())) {
            apply(server);
        }
        ChatTabActions.refresh();
    }

    /** Every client tick: a channel held back by the guard applies once its hold runs out. */
    public static void tick() {
        int held = GUARD.expire(System.currentTimeMillis());
        if (held >= 0 && Channel.of(held) != channel) {
            apply(Channel.of(held));
            ChatTabActions.refresh();
        }
    }

    /** The server's channel becomes the one typed into. */
    private static void apply(Channel server) {
        Channel before = channel;
        channel = server;
        if (before != server) {
            typingEpoch++;
            refreshFocus(before);
        }
    }

    /**
     * Changes whenever the channel typed into does — a pick, {@code /ch}, a [tag], the server's fallback: the new
     * look's input glyph pops when it differs from the frame before (one int compared, nothing allocated).
     */
    public static int typingEpoch() {
        return typingEpoch;
    }

    /** The System view: only system lines show. */
    public static boolean systemView() {
        return systemView;
    }

    /** The System pip: show only system lines (a channel pip or Ctrl+Tab goes back). */
    public static void showSystem() {
        if (!systemView && current() != null) {
            systemView = true;
            ChatFilter.refresh();
        }
    }

    /** Staff who may punish: the chat's message menu offers "Moderate" (from the server, on join). */
    public static boolean moderate() {
        return current() != null && moderate;
    }

    /** The emoji picker button: permission from the server (standard emojis are always there to pick). */
    public static boolean emojiPicker() {
        return current() != null && emojiPicker;
    }

    public static void reset() {
        channel = null;
        systemView = false;
        switchedOffBefore = -1;
        GUARD.reset();
        typingEpoch++;
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
        select(all[index]);
    }

    /**
     * Switches to a channel the player can use — a click on its pip may pick one switched off in Settings → Chat
     * (its lines show while it's selected); shown at once, confirmed by the server.
     */
    public static void select(Channel target) {
        Channel from = current();
        if (systemView && open(target)) {
            // Back from the System view to the channels (also to the one typed into).
            systemView = false;
            ChatFilter.refresh();
        }
        if (from == null || !open(target) || target == from) {
            return;
        }
        channel = target;
        typingEpoch++;
        GUARD.selected(target.ordinal(), System.currentTimeMillis());
        refreshFocus(from);
        ClientPlayNetworking.send(new HubPayloads.ChatChannelSelect(target.ordinal()));
        ChatTabActions.refresh();
    }

    /** The channels switched off in Settings → Chat at the last settings update (bits; -1 none yet this session). */
    private static int switchedOffBefore = -1;

    /**
     * Settings changed: a channel the player just switched off stops being the one they write in. With the new look,
     * hiding the channel tab being viewed moves the view to All first (as the tab's own "Hide tab" does), when the
     * player keeps the All tab; without it the view follows the typing channel to the next one.
     */
    public static void onSettings() {
        HubPayloads.SettingsData settings = net.ledok.eden_ld.client.hub.HubState.settingsData;
        int switchedOff = settings == null ? 0 : settings.chatHidden();
        int justOff = switchedOffBefore < 0 ? 0 : switchedOff & ~switchedOffBefore;
        switchedOffBefore = switchedOff;
        Channel now = current();
        if (now != null && (justOff & 1 << now.ordinal()) != 0) {
            ChatTabActions.leaving(now);
            cycle(false);
        }
        ChatTabActions.refresh();
    }

    /**
     * The chat shows what the channel choice allows: with "only the selected channel" on, switching switches the
     * view; a switched-off channel's lines show while it is the selected one.
     */
    private static void refreshFocus(@Nullable Channel from) {
        HubPayloads.SettingsData settings = net.ledok.eden_ld.client.hub.HubState.settingsData;
        if (settings != null && (settings.chatFocus() || from != null && ChatFilter.channelSwitchedOff(from)
                || channel != null && ChatFilter.channelSwitchedOff(channel))) {
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
        sentElsewhere(typed);
        return rest;
    }

    /**
     * The new look on another channel's tab: a one-shot to {@code typed} lands in a tab not on screen (the chat, and
     * the closed chat with it, follow the tab), so the action bar says where it went.
     */
    private static void sentElsewhere(Channel typed) {
        ChatTabSpec tab = ChatViews.main().tab();
        if (ChatFilter.newLook() && tab.kind() == ChatTabSpec.Kind.CHANNEL && tab.channel() != typed) {
            Minecraft.getInstance().gui.setOverlayMessage(Component.translatable("chat.eden_ld.sent_to",
                    Component.translatable(ChatTabSpec.of(typed).labelKey()).withColor(color(typed) & 0xFFFFFF)), false);
        }
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

    /** The new look's input glyphs, made once (a translatable follows the language by itself). */
    private static final Component[] GLYPHS = new Component[Channel.values().length];

    /** The new look's input glyph for a channel: a letter or two (G, L, P, Gd), in the channel's color when drawn. */
    public static Component glyphLabel(Channel channel) {
        Component label = GLYPHS[channel.ordinal()];
        if (label == null) {
            label = Component.translatable("chat.eden_ld.glyph." + channel.name().toLowerCase(Locale.ROOT));
            GLYPHS[channel.ordinal()] = label;
        }
        return label;
    }

    /** Badge text color, matching the channel tags in chat. */
    public static int color(Channel channel) {
        return ChatTabSpec.color(channel);
    }
}
