package net.ledok.eden_ld.chat;

import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.ledok.eden_ld.EdenLD;
import net.ledok.eden_ld.compat.LuckPermsCompat;
import net.ledok.eden_ld.hub.HubPayloads;
import net.ledok.eden_ld.hub.HubServer;
import net.ledok.eden_ld.hub.HubSettings;
import net.ledok.eden_ld.hub.guild.Guild;
import net.ledok.eden_ld.hub.guild.GuildManager;
import net.ledok.eden_ld.hub.guild.GuildServer;
import net.ledok.eden_ld.hub.party.Party;
import net.ledok.eden_ld.hub.party.PartyManager;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.OutgoingChatMessage;
import net.minecraft.network.chat.PlayerChatMessage;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Eden's chat: every chat message goes to one channel — GLOBAL (everyone; the only one the
 * Minestar Discord bridge sees), LOCAL (players nearby), PARTY or GUILD. A player types into
 * their chosen channel (switched in the chat screen, or {@code /ch}); "!g" "!l" "!p" "!gd" at
 * the start send one message elsewhere. {@code /p}, {@code /g}, {@code /l} and the hub chat
 * panels come through here too.
 * <p>
 * Eden delivers every message itself (it replaces Styled Chat): typed chat stays signed player
 * chat, shown with Eden's chat type ("%s%s": the sender line from {@link ChatStyle}, then the
 * message). Minestar's bridge listens to Fabric's CHAT_MESSAGE, which only GLOBAL fires.
 */
public final class ChatChannels {

    public enum Channel {
        GLOBAL("!g"), LOCAL("!l"), PARTY("!p"), GUILD("!gd");

        public final String prefix;

        Channel(String prefix) {
            this.prefix = prefix;
        }

        /** Longest prefix first: "!gd" before "!g". */
        private static final Channel[] BY_PREFIX = {GUILD, GLOBAL, LOCAL, PARTY};
        private static final Channel[] VALUES = values();

        public static Channel of(int ordinal) {
            return ordinal >= 0 && ordinal < VALUES.length ? VALUES[ordinal] : GLOBAL;
        }

        /**
         * The channel a typed "!x message" asks for (the prefix in any case, then a space); null without a prefix.
         * Allocates nothing: the chat's input glyph asks every frame.
         */
        @Nullable
        public static Channel byPrefix(String text) {
            for (Channel channel : BY_PREFIX) {
                int length = channel.prefix.length();
                if (text.length() > length && text.charAt(length) == ' '
                        && text.regionMatches(true, 0, channel.prefix, 0, length)) {
                    return channel;
                }
            }
            return null;
        }
    }

    /** "%s%s": the sender line, then the message (data/eden_ld/chat_type/chat.json). */
    public static final ResourceKey<ChatType> CHAT_TYPE = ResourceKey.create(Registries.CHAT_TYPE,
            ResourceLocation.fromNamespaceAndPath(EdenLD.MOD_ID, "chat"));

    /** Grants the chat screen's emoji picker button (typing :name: works for everyone). */
    public static final String PERMISSION_EMOJI_PICKER = "eden_ld.emoji_picker";

    private static final Map<UUID, Channel> ONE_SHOT = new ConcurrentHashMap<>();
    /**
     * Each player's last message still waiting for a pasted emoji to be fetched: later messages
     * wait behind it — signed chat must reach everyone in the order it was sent.
     */
    private static final Map<UUID, java.util.concurrent.CompletableFuture<Void>> WAITING = new ConcurrentHashMap<>();
    /** Set while Eden re-broadcasts a join/leave message it restyled. */
    private static boolean announcing;

    private ChatChannels() {
    }

    public static void initialize() {
        if (net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("styledchat")) {
            EdenLD.LOGGER.warn("[Chat] Styled Chat is installed: it re-formats every chat message and breaks Eden's "
                    + "channels and chat format. Remove it (Eden replaces it).");
        }
        net.ledok.eden_ld.chat.emoji.EmojiRegistry.initialize();
        ChatGuard.initialize();
        ServerMessageEvents.ALLOW_CHAT_MESSAGE.register(ChatChannels::onChat);
        ServerMessageEvents.ALLOW_GAME_MESSAGE.register((server, message, overlay) -> onGameMessage(server, message));
        ServerPlayNetworking.registerGlobalReceiver(HubPayloads.ChatChannelSelect.TYPE,
                (payload, context) -> select(context.player(), Channel.of(payload.channel())));
        ServerPlayNetworking.registerGlobalReceiver(HubPayloads.ChatOneShot.TYPE,
                (payload, context) -> ONE_SHOT.put(context.player().getUUID(), Channel.of(payload.channel())));
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
                server.execute(() -> {
                    pushState(handler.getPlayer());
                    sendKnownGuests(server, handler.getPlayer());
                    ChatHistory.sendTo(handler.getPlayer());
                }));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            ONE_SHOT.remove(handler.getPlayer().getUUID());
            WAITING.remove(handler.getPlayer().getUUID());
        });
        // Granting or revoking the emoji picker applies live.
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STARTED.register(started ->
                LuckPermsCompat.onUserDataChange(() -> started.execute(() -> pushStateAll(started))));
    }

    // ---------------------------------------------------------------- channel choice

    public static boolean available(ServerPlayer player, Channel channel) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return false;
        }
        return switch (channel) {
            case GLOBAL, LOCAL -> true;
            case PARTY -> PartyManager.get(server).partyOf(player.getUUID()) != null;
            case GUILD -> GuildManager.get(server).guildOf(player.getUUID()) != null;
        };
    }

    /** The channel the player types into; their choice falls back to GLOBAL while unavailable. */
    public static Channel selected(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        Channel chosen = server == null ? Channel.GLOBAL
                : Channel.of(HubSettings.chatChannel(server, player.getUUID()));
        return available(player, chosen) ? chosen : Channel.GLOBAL;
    }

    /** Switches the player's channel; false (and nothing stored) when it isn't open to them. */
    public static boolean select(ServerPlayer player, Channel channel) {
        MinecraftServer server = player.getServer();
        boolean ok = server != null && available(player, channel);
        if (ok) {
            HubSettings.setChatChannel(server, player.getUUID(), channel.ordinal());
        }
        pushState(player);
        return ok;
    }

    /** Tells the client its channel and which ones are open (the chat screen's badge). */
    public static void pushState(ServerPlayer player) {
        if (!player.hasDisconnected() && ServerPlayNetworking.canSend(player, HubPayloads.ChatChannelState.TYPE)) {
            ServerPlayNetworking.send(player, new HubPayloads.ChatChannelState(selected(player).ordinal(),
                    available(player, Channel.PARTY), available(player, Channel.GUILD),
                    LuckPermsCompat.hasPermission(player, PERMISSION_EMOJI_PICKER),
                    (net.ledok.eden_ld.command.EdenPermissions.staff(player) & HubPayloads.HomeData.STAFF_PUNISH) != 0));
        }
    }

    /** Party or guild membership changed somewhere: everyone's open channels may have. */
    public static void pushStateAll(MinecraftServer server) {
        server.getPlayerList().getPlayers().forEach(ChatChannels::pushState);
    }

    // ---------------------------------------------------------------- sending

    /** Typed chat: routed to its channel and delivered by Eden, never by vanilla. */
    private static boolean onChat(PlayerChatMessage message, ServerPlayer sender, ChatType.Bound params) {
        String text = message.signedContent();
        Channel channel = ONE_SHOT.remove(sender.getUUID());
        if (channel == null) {
            // A client without Eden sends "!l hi" as is: route it, and hide the prefix.
            Channel typed = Channel.byPrefix(text);
            if (typed != null) {
                channel = typed;
                text = text.substring(typed.prefix.length()).trim();
            } else {
                channel = selected(sender);
            }
        }
        if (!text.isEmpty()) {
            Channel target = channel;
            String body = text;
            inOrder(sender, body, () -> {
                Component error = deliver(sender, target, message, body);
                if (error != null) {
                    sender.sendSystemMessage(error.copy().withStyle(ChatFormatting.RED));
                }
            });
        }
        return false;
    }

    /**
     * Runs a player's delivery now — or, when the text pastes an emoji not known yet (a FakeNitro
     * image link) or an earlier message of theirs is still waiting, once that's done, in order.
     */
    private static void inOrder(ServerPlayer sender, String text, Runnable delivery) {
        MinecraftServer server = sender.getServer();
        if (server == null) {
            return;
        }
        java.util.concurrent.CompletableFuture<Void> guests =
                net.ledok.eden_ld.chat.emoji.EmojiRegistry.fetchGuests(server, text);
        java.util.concurrent.CompletableFuture<Void> previous = WAITING.get(sender.getUUID());
        boolean behind = previous != null && !previous.isDone();
        if (!behind && guests.isDone()) {
            delivery.run();
            return;
        }
        java.util.concurrent.CompletableFuture<Void> ready = behind
                ? java.util.concurrent.CompletableFuture.allOf(previous, guests) : guests;
        java.util.concurrent.CompletableFuture<Void> next = ready.handle((ok, error) -> null)
                .thenRunAsync(() -> {
                    if (!sender.hasDisconnected()) {
                        delivery.run();
                    }
                }, server);
        WAITING.put(sender.getUUID(), next);
    }

    /**
     * Sends plain text on a channel ({@code /p}, {@code /g}, {@code /l}, hub chat panels) —
     * shown like typed chat. Returns an error component, or null when delivered.
     */
    @Nullable
    public static Component send(ServerPlayer sender, Channel channel, String message) {
        String trimmed = message == null ? "" : message.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        // Checked now so the command can answer; delivered in order with the player's chat.
        Component unavailable = available(sender, channel) ? null : switch (channel) {
            case PARTY -> Component.translatable("message.eden_ld.party.not_in_party");
            case GUILD -> Component.translatable("message.eden_ld.guild.not_in_guild");
            default -> null;
        };
        if (unavailable != null) {
            return unavailable;
        }
        // What typed chat gets from vanilla and BanHammer: allowed characters, length, mutes, a rate limit.
        Component refused = ChatGuard.check(sender, trimmed);
        if (refused != null) {
            return refused;
        }
        inOrder(sender, trimmed, () -> deliver(sender, channel, null, trimmed));
        return null;
    }

    /**
     * Delivers to the channel's audience. {@code signed} is the typed chat message (kept signed,
     * the styled text shown over it); null for command/hub text, sent as system messages.
     */
    @Nullable
    private static Component deliver(ServerPlayer sender, Channel channel, @Nullable PlayerChatMessage signed,
                                     String text) {
        MinecraftServer server = sender.getServer();
        if (server == null) {
            return null;
        }
        Party party = null;
        Guild guild = null;
        List<ServerPlayer> recipients = new ArrayList<>();
        switch (channel) {
            case GLOBAL -> recipients.addAll(server.getPlayerList().getPlayers());
            case LOCAL -> {
                double radius = EdenLD.CONFIG.chat.local_radius;
                for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                    if (player.level() == sender.level() && player.distanceToSqr(sender) <= radius * radius) {
                        recipients.add(player);
                    }
                }
            }
            case PARTY -> {
                party = PartyManager.get(server).partyOf(sender.getUUID());
                if (party == null) {
                    return Component.translatable("message.eden_ld.party.not_in_party");
                }
                addOnline(server, party.members.keySet(), recipients);
            }
            case GUILD -> {
                guild = GuildManager.get(server).guildOf(sender.getUUID());
                if (guild == null) {
                    return Component.translatable("message.eden_ld.guild.not_in_guild");
                }
                addOnline(server, guild.members.keySet(), recipients);
            }
        }

        ChatStyle.Content content = ChatStyle.content(sender, text);
        Component senderLine = ChatStyle.senderLine(sender, channel);
        ChatType.Bound bound = ChatType.bind(CHAT_TYPE, server.registryAccess(), senderLine);
        if (signed != null) {
            // The signed text stays verifiable; clients show the styled (and prefix-free) text.
            PlayerChatMessage shown = content.decorated() || !text.equals(signed.signedContent())
                    ? signed.withUnsignedContent(content.component()) : signed;
            OutgoingChatMessage outgoing = OutgoingChatMessage.create(shown);
            for (ServerPlayer recipient : recipients) {
                recipient.sendChatMessage(outgoing, sender.shouldFilterMessageTo(recipient), bound);
            }
            if (channel == Channel.GLOBAL) {
                // Other mods' chat listeners — Minestar's Discord bridge among them — see GLOBAL only,
                // as Discord should read it: no "!g", and :name: emojis as <:name:id>.
                String forDiscord = net.ledok.eden_ld.chat.emoji.EmojiRegistry.toDiscord(
                        ChatFormat.toDiscord(ChatStyle.expandShortcuts(sender, text), ChatStyle.formatOptions()));
                PlayerChatMessage relayed = forDiscord.equals(signed.signedContent()) ? shown
                        : PlayerChatMessage.unsigned(sender.getUUID(), forDiscord);
                ServerMessageEvents.CHAT_MESSAGE.invoker().onChatMessage(relayed, sender, bound);
            }
        } else {
            Component line = Component.empty().append(senderLine).append(content.component());
            for (ServerPlayer recipient : recipients) {
                recipient.sendSystemMessage(line);
            }
        }
        if (channel == Channel.GLOBAL) {
            // For players joining later: the recent Global chat (Settings may hide it, like any Global line).
            ChatHistory.add(Component.empty().append(senderLine).append(content.component()));
        }
        server.logChatMessage(content.component(), bound,
                channel == Channel.GLOBAL ? null : channel.name().toLowerCase(Locale.ROOT));

        if (EdenLD.CONFIG.chat.mention_sound) {
            for (ServerPlayer mentioned : content.mentioned()) {
                int volume = HubSettings.mentionVolume(server, mentioned.getUUID());
                if (!recipients.contains(mentioned) || mentioned == sender || volume <= 0
                        || net.ledok.eden_ld.hub.IgnoreList.ignores(server, mentioned.getUUID(), sender.getUUID())
                        || HubSettings.channelNotify(server, mentioned.getUUID(), channel.ordinal())
                                == HubSettings.ChannelNotify.NOTHING) {
                    continue;
                }
                if (ServerPlayNetworking.canSend(mentioned, HubPayloads.MentionPing.TYPE)) {
                    // Eden's client plays it at the player's own volume, apart from the game's sliders.
                    ServerPlayNetworking.send(mentioned, new HubPayloads.MentionPing(channel.ordinal()));
                } else {
                    mentioned.playNotifySound(SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.PLAYERS, volume / 100f, 1.4f);
                }
            }
        }
        if (channel == Channel.LOCAL && recipients.size() <= 1) {
            sender.sendSystemMessage(Component.translatable("chat.eden_ld.local.nobody").withStyle(ChatFormatting.GRAY));
        }
        String senderName = sender.getGameProfile().getName();
        // The hub's panels show the line as chat does: hovers (items, spoilers, emojis) and clicks.
        Component hubText = content.component();
        if (party != null) {
            Party.ChatLine line = new Party.ChatLine(sender.getUUID(), senderName, hubText);
            PartyManager.get(server).appendChat(party, line);
            HubServer.broadcastPartyChatLine(server, party, line);
        }
        if (guild != null) {
            Guild.ChatLine line = new Guild.ChatLine(sender.getUUID(), senderName, hubText, System.currentTimeMillis());
            GuildManager.get(server).appendChat(guild, line);
            GuildServer.broadcastChatLine(server, guild, line);
        }
        return null;
    }

    private static void addOnline(MinecraftServer server, Iterable<UUID> members, List<ServerPlayer> into) {
        for (UUID member : members) {
            ServerPlayer online = server.getPlayerList().getPlayer(member);
            if (online != null) {
                into.add(online);
            }
        }
    }

    // ---------------------------------------------------------------- join / leave

    /**
     * Join and leave messages: none for players whose groups set {@code eden_ld.silent_join},
     * and leaving in red (config) as Styled Chat had it. The name inside is already Eden's
     * display name (prefix and color).
     */
    private static boolean onGameMessage(MinecraftServer server, Component message) {
        if (announcing) {
            return true;
        }
        // Discord lines from Minestar's bridge (plain text, legacy colors): emojis as images
        // (unknown ones fetched as guests first — the line then goes out a moment later), links
        // clickable, GIFs as "[GIF]".
        // Discord lines go out marked as Discord's (players may hide them; they join the recent
        // Global chat new players get).
        if (message.getContents() instanceof net.minecraft.network.chat.contents.PlainTextContents.LiteralContents
                && message.getSiblings().isEmpty()) {
            if (isDiscordLine(message.getString())) {
                if (!net.ledok.eden_ld.chat.emoji.EmojiRegistry.showDiscordLine(server, message.getString(),
                        shown -> announceDiscord(server, shown))) {
                    announceDiscord(server, message);
                }
                return false;
            }
            return !net.ledok.eden_ld.chat.emoji.EmojiRegistry.showDiscordLine(server, message.getString(),
                    shown -> broadcastTo(server, shown, player -> shown));
        }
        if (!(message.getContents() instanceof TranslatableContents translatable)) {
            return true;
        }
        String key = translatable.getKey();
        // Deaths and advancements: each player chooses whose they see (everyone / party & guild /
        // nobody), so each copy is marked with whether it's about the reader's party or guild.
        ChatMarker.Kind kind = key.startsWith("death.") ? ChatMarker.Kind.DEATH
                : key.startsWith("chat.type.advancement.") ? ChatMarker.Kind.ADVANCEMENT : null;
        if (kind != null) {
            Object[] kindArgs = translatable.getArgs();
            UUID subject = kindArgs.length > 0 && kindArgs[0] instanceof Component name ? entityId(name) : null;
            // The subject rides in the mark too: players who ignore them don't see it.
            broadcastTo(server, message, viewer -> ChatMarker.mark(message, kind,
                    subject != null && sameTeam(server, viewer.getUUID(), subject), subject));
            return false;
        }
        boolean join = key.startsWith("multiplayer.player.joined");
        boolean leave = key.equals("multiplayer.player.left");
        if (!join && !leave) {
            return true;
        }
        Object[] args = translatable.getArgs();
        UUID player = args.length > 0 && args[0] instanceof Component name ? entityId(name) : null;
        if (player != null && LuckPermsCompat.metaBool(player, ChatStyle.META_SILENT_JOIN, false)) {
            return false;
        }
        if (leave && EdenLD.CONFIG.chat.red_leave_messages) {
            announcing = true;
            try {
                server.getPlayerList().broadcastSystemMessage(
                        Component.translatable(key, args).withStyle(ChatFormatting.RED), false);
            } finally {
                announcing = false;
            }
            return false;
        }
        return true;
    }

    /** Guest emojis in the recent chat and the player's guild and party chat, before those lines show. */
    private static void sendKnownGuests(MinecraftServer server, ServerPlayer player) {
        List<Component> texts = new ArrayList<>(ChatHistory.recent());
        Guild guild = GuildManager.get(server).guildOf(player.getUUID());
        if (guild != null) {
            guild.chat.forEach(line -> texts.add(line.text()));
        }
        Party party = PartyManager.get(server).partyOf(player.getUUID());
        if (party != null) {
            party.chatHistory.forEach(line -> texts.add(line.text()));
        }
        net.ledok.eden_ld.chat.emoji.EmojiRegistry.sendGuestsIn(player, texts);
    }

    /** A line from Minestar's Discord bridge: starts with the configured marker (colors ignored). */
    private static boolean isDiscordLine(String line) {
        String marker = EdenLD.CONFIG.chat.discord_line_marker;
        String plain = ChatFormatting.stripFormatting(line);
        return marker != null && !marker.isBlank() && plain != null && plain.stripLeading().startsWith(marker);
    }

    /** Whether the viewer is the subject, or shares their party or guild. */
    private static boolean sameTeam(MinecraftServer server, UUID viewer, UUID subject) {
        if (viewer.equals(subject)) {
            return true;
        }
        Party party = PartyManager.get(server).partyOf(viewer);
        if (party != null && party == PartyManager.get(server).partyOf(subject)) {
            return true;
        }
        Guild guild = GuildManager.get(server).guildOf(viewer);
        return guild != null && guild == GuildManager.get(server).guildOf(subject);
    }

    /** A Discord line to everyone, marked as Discord's, and kept for players joining later. */
    private static void announceDiscord(MinecraftServer server, Component line) {
        Component marked = ChatMarker.mark(line, ChatMarker.Kind.DISCORD, false);
        broadcastTo(server, marked, player -> marked);
        ChatHistory.add(marked);
    }

    /**
     * A system broadcast of {@code message} (the console logs it, other mods hear it as usual),
     * each player's copy made by {@code copy}.
     */
    private static void broadcastTo(MinecraftServer server, Component message,
                                    java.util.function.Function<ServerPlayer, Component> copy) {
        announcing = true;
        try {
            server.getPlayerList().broadcastSystemMessage(message, copy, false);
        } finally {
            announcing = false;
        }
    }

    /** The player a display name belongs to: vanilla puts an entity hover card on the name. */
    @Nullable
    private static UUID entityId(Component component) {
        HoverEvent hover = component.getStyle().getHoverEvent();
        if (hover != null) {
            HoverEvent.EntityTooltipInfo info = hover.getValue(HoverEvent.Action.SHOW_ENTITY);
            if (info != null) {
                return info.id;
            }
        }
        for (Component sibling : component.getSiblings()) {
            UUID id = entityId(sibling);
            if (id != null) {
                return id;
            }
        }
        return null;
    }
}
