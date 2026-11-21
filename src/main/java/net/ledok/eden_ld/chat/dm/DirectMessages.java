package net.ledok.eden_ld.chat.dm;

import com.mojang.authlib.GameProfile;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.ledok.eden_ld.EdenLD;
import net.ledok.eden_ld.chat.ChatGuard;
import net.ledok.eden_ld.chat.ChatMarker;
import net.ledok.eden_ld.chat.ChatStyle;
import net.ledok.eden_ld.chat.LegacyText;
import net.ledok.eden_ld.hub.ActivityInvites;
import net.ledok.eden_ld.hub.HubPayloads;
import net.ledok.eden_ld.hub.HubSettings;
import net.ledok.eden_ld.hub.IgnoreList;
import net.ledok.eden_ld.hub.moderation.ChatRecall;
import net.ledok.eden_ld.util.OnServerThread;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.PlayerChatMessage;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Private messages and small group chats in Eden's chat (1.3.94): each conversation is a tab of the player's chat
 * ({@code client.chat.Conversations}). The server keeps them ({@link ConversationStore}: the last 100 lines for 7
 * days), sends each player theirs on joining ({@link HubPayloads.ConvData}, unread markers included), and delivers a
 * new line as a chat line marked {@link ChatMarker.Kind#DM} — a client without Eden reads it as a plain line.
 * {@code /msg}, {@code /tell} and {@code /w} from a player with Eden land here too ({@code MsgCommandDmMixin}).
 * <p>
 * Privacy: players who ignore each other can't write to each other or add each other to groups, and each player's
 * Settings switches ({@link HubSettings#DM_OFF}) say who may (friends, party, guild, everyone else) — someone who
 * already wrote in a DM can always be answered. Staff don't read conversations: nothing is logged, and a complaint
 * carries only the DM lines between the reporter and the player reported ({@link ChatRecall#sendPrivate}).
 */
public final class DirectMessages {

    /** The conversations each online player's client knows (sent since they joined): others come with a line. */
    private static final Map<UUID, Set<String>> KNOWN = new HashMap<>();
    /**
     * Repeat «Написати» within this long is dropped (review finding 39): each open built the whole history on the
     * server thread, and a modified client could send them at packet rate.
     */
    private static final long OPEN_COOLDOWN_MS = 500;
    /**
     * A group is renamed at most this often per owner (review finding 26): every rename is a line to every member, on
     * top of the chat's own rate limit.
     */
    private static final long RENAME_COOLDOWN_MS = 2_000;
    /** When each online player last opened a DM and renamed a group ({@link Util#getMillis}; server thread). */
    private static final Map<UUID, Long> LAST_OPEN = new HashMap<>();
    private static final Map<UUID, Long> LAST_RENAME = new HashMap<>();

    private DirectMessages() {
    }

    public static void initialize() {
        ServerPlayNetworking.registerGlobalReceiver(HubPayloads.ConvOpen.TYPE,
                (payload, context) -> open(context.player(), payload.target()));
        ServerPlayNetworking.registerGlobalReceiver(HubPayloads.ConvSend.TYPE,
                (payload, context) -> send(context.player(), payload.conv(), payload.text()));
        ServerPlayNetworking.registerGlobalReceiver(HubPayloads.ConvAction.TYPE,
                (payload, context) -> action(context.player(), payload));
        ServerPlayNetworking.registerGlobalReceiver(HubPayloads.GroupCreate.TYPE,
                (payload, context) -> createGroup(context.player(), payload.name(), payload.invite()));
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> OnServerThread.run(server, () -> {
            ServerPlayer player = handler.getPlayer();
            if (!player.hasDisconnected()) {
                sync(server, player);
            }
        }));
        // DISCONNECT fires on the server thread while the player is still in the list (server.execute would run at
        // once, and the check below kept everything): forget them in a queued task, unless they're back already.
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            UUID id = handler.getPlayer().getUUID();
            server.tell(new net.minecraft.server.TickTask(server.getTickCount(), () -> {
                if (server.getPlayerList().getPlayer(id) == null) {
                    KNOWN.remove(id);
                    LAST_OPEN.remove(id);
                    LAST_RENAME.remove(id);
                }
            }));
        });
    }

    private static boolean eden(ServerPlayer player) {
        return ServerPlayNetworking.canSend(player, HubPayloads.ConvData.TYPE);
    }

    // ---------------------------------------------------------------- joining

    /** A player joined: old lines go, and their client gets each of their conversations with its lines. */
    private static void sync(MinecraftServer server, ServerPlayer player) {
        ConversationStore store = ConversationStore.get(server);
        store.prune(System.currentTimeMillis());
        UUID id = player.getUUID();
        String name = player.getGameProfile().getName();
        Set<String> known = new HashSet<>();
        KNOWN.put(id, known);
        for (Conversation conv : store.of(id)) {
            Conversation.Member member = conv.member(id);
            if (member != null && !name.equals(member.name)) {
                member.name = name;
                store.changed();
            }
        }
        if (!eden(player)) {
            return;
        }
        for (Conversation conv : store.of(id)) {
            ServerPlayNetworking.send(player, data(server, conv, id, false, true));
            known.add(conv.id);
        }
    }

    // ---------------------------------------------------------------- what clients get

    /** The conversation as {@code viewer} sees it; with {@code full} its lines (a group's since they joined). */
    private static HubPayloads.ConvData data(MinecraftServer server, Conversation conv, UUID viewer, boolean focus,
                                             boolean full) {
        Conversation.Member self = conv.member(viewer);
        List<HubPayloads.ConvMember> members = new ArrayList<>();
        conv.members.forEach((uuid, member) -> members.add(new HubPayloads.ConvMember(uuid, member.name)));
        List<HubPayloads.ConvLine> lines = new ArrayList<>();
        if (full) {
            long since = conv.group && self != null ? self.joined : 0;
            for (Conversation.Line line : conv.lines) {
                if (line.time() >= since) {
                    lines.add(new HubPayloads.ConvLine(line.time(), lineFor(server, conv, line, viewer)));
                }
            }
        }
        return new HubPayloads.ConvData(conv.id, conv.group, conv.name, conv.owner, members,
                self != null && self.hidden, focus, full, self == null ? 0 : self.readAt,
                self == null ? Conversation.DEFAULT_NOTIFY : self.notify, lines);
    }

    /** Sends the conversation to every online member with Eden (its lines only to those whose client hasn't it). */
    private static void pushMeta(MinecraftServer server, Conversation conv) {
        for (UUID member : conv.members.keySet()) {
            ServerPlayer online = server.getPlayerList().getPlayer(member);
            if (online != null && eden(online)) {
                Set<String> known = KNOWN.computeIfAbsent(member, k -> new HashSet<>());
                ServerPlayNetworking.send(online, data(server, conv, member, false, !known.contains(conv.id)));
                known.add(conv.id);
            }
        }
    }

    /**
     * A line as {@code viewer}'s chat shows it: the DM markers, a pink tag — {@code [PM]}, {@code [PM → Name]} on the
     * writer's own copy, the group's name — then the writer's name as chat showed it and the text. A DM's tag
     * suggests {@code /msg Name } on a click (clients without Eden answer that way). The stored JSON is parsed once per
     * line and kept on it (review finding 39); each use gets a copy, so nothing downstream can change the kept one.
     */
    private static Component lineFor(MinecraftServer server, Conversation conv, Conversation.Line line, UUID viewer) {
        if (line.name == null || line.body == null) {
            line.name = parse(server, line.nameJson());
            line.body = parse(server, line.bodyJson());
        }
        Component name = line.name.copy();
        Component body = line.body.copy();
        return ChatMarker.markDm(Component.empty().append(tag(conv, line.sender(), viewer)).append(" ").append(name)
                .append(LegacyText.parse(EdenLD.CONFIG.chat.separator)).append(body), conv.id, line.sender());
    }

    private static MutableComponent tag(Conversation conv, UUID sender, UUID viewer) {
        if (conv.group) {
            return Component.literal("[" + conv.name + "]").withStyle(ChatFormatting.LIGHT_PURPLE);
        }
        String other = otherName(conv, viewer);
        MutableComponent tag = sender.equals(viewer)
                ? Component.translatableWithFallback("chat.eden_ld.dm.tag_to", "[PM → %s]", other)
                : Component.translatableWithFallback("chat.eden_ld.dm.tag", "[PM]");
        return tag.withStyle(style -> style.withColor(ChatFormatting.LIGHT_PURPLE)
                .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, "/msg " + other + " "))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                        Component.translatableWithFallback("chat.eden_ld.dm.tag.hint", "Click to answer"))));
    }

    /** The DM's other member's name, as {@code viewer} sees it. */
    private static String otherName(Conversation conv, UUID viewer) {
        for (Map.Entry<UUID, Conversation.Member> entry : conv.members.entrySet()) {
            if (!entry.getKey().equals(viewer)) {
                return entry.getValue().name;
            }
        }
        return "";
    }

    private static Component parse(MinecraftServer server, String json) {
        try {
            Component component = Component.Serializer.fromJson(json, server.registryAccess());
            return component == null ? Component.empty() : component;
        } catch (RuntimeException e) {
            return Component.literal(json);
        }
    }

    /**
     * A note of the server's in the conversation (who joined, why a line was refused) for one member; not kept, and
     * like group lines never part of a complaint.
     */
    private static void note(ServerPlayer player, Conversation conv, Component text) {
        ChatRecall.sendPrivate(player, ChatMarker.markDm(text.copy().withStyle(ChatFormatting.GRAY), conv.id, null),
                DmRules.GROUP_PARTNER);
    }

    /**
     * A note to every online member, except those who ignore {@code actor} (review finding 26: ignoring a group's
     * owner stops their renames and invites reaching you as lines, as it stops their messages).
     */
    private static void noteAll(MinecraftServer server, Conversation conv, Component text, UUID actor) {
        for (UUID member : conv.members.keySet()) {
            ServerPlayer online = server.getPlayerList().getPlayer(member);
            if (online != null && (member.equals(actor) || !IgnoreList.ignores(server, member, actor))) {
                note(online, conv, text);
            }
        }
    }

    /** Shows a {@link ChatGuard} refusal (an empty one means a closing connection: nothing to show). */
    private static void guardNote(ServerPlayer player, @Nullable Conversation conv, Component guard) {
        if (guard.getString().isEmpty()) {
            return;
        }
        if (conv == null) {
            error(player, guard);
        } else {
            note(player, conv, guard.copy().withStyle(ChatFormatting.RED));
        }
    }

    /**
     * The player's client drops the conversation; it's no longer known to it either, so a later open sends it whole
     * (opens of a known one send no lines: review finding 39).
     */
    private static void gone(ServerPlayer player, String id) {
        Set<String> known = KNOWN.get(player.getUUID());
        if (known != null) {
            known.remove(id);
        }
        ServerPlayNetworking.send(player, new HubPayloads.ConvGone(id));
    }

    private static void error(ServerPlayer player, Component text) {
        player.sendSystemMessage(text.copy().withStyle(ChatFormatting.RED));
    }

    // ---------------------------------------------------------------- privacy

    /**
     * Why {@code from} can't write to {@code to} (or add them to a group), or null when they may: either ignores the
     * other, or {@code to}'s switches leave {@code from} out — unless {@code to} wrote in this DM themselves.
     */
    @Nullable
    private static Component refusal(MinecraftServer server, UUID from, UUID to, String toName,
                                     @Nullable Conversation dm) {
        if (IgnoreList.ignores(server, from, to)) {
            return Component.translatable("message.eden_ld.dm.you_ignore", toName);
        }
        boolean allowed = !IgnoreList.ignores(server, to, from) && (dm != null && dm.wrote(to)
                || ActivityInvites.allowedBy(server, from, to, HubSettings.dmOff(server, to)));
        return allowed ? null : Component.translatable("message.eden_ld.dm.blocked", toName);
    }

    // ---------------------------------------------------------------- DMs

    /** «Написати»: the DM with {@code target} opens on the player's chat (made when new). */
    private static void open(ServerPlayer player, UUID target) {
        MinecraftServer server = player.getServer();
        if (server == null || target.equals(player.getUUID())) {
            return;
        }
        long now = Util.getMillis();
        Long last = LAST_OPEN.get(player.getUUID());
        if (last != null && now - last < OPEN_COOLDOWN_MS) {
            return;
        }
        LAST_OPEN.put(player.getUUID(), now);
        String targetName = nameOf(server, target);
        if (targetName == null) {
            return;
        }
        ConversationStore store = ConversationStore.get(server);
        Conversation conv = store.get(DmRules.dmKey(player.getUUID(), target));
        Component refused = refusal(server, player.getUUID(), target, targetName, conv);
        if (refused != null) {
            error(player, refused);
            return;
        }
        boolean created = conv == null;
        if (created) {
            conv = newDm(store, player, target, targetName);
        }
        Conversation.Member self = conv.member(player.getUUID());
        if (self != null && self.hidden) {
            self.hidden = false;
            store.changed();
        }
        // A client that has the conversation already keeps its lines (closing a tab keeps them): only the tab's state
        // and the focus go (review finding 39). A new DM may reuse an expired one's id, so it always goes whole.
        Set<String> known = KNOWN.computeIfAbsent(player.getUUID(), k -> new HashSet<>());
        boolean full = created || !known.contains(conv.id);
        ServerPlayNetworking.send(player, data(server, conv, player.getUUID(), true, full));
        known.add(conv.id);
    }

    private static Conversation newDm(ConversationStore store, ServerPlayer player, UUID target, String targetName) {
        long now = System.currentTimeMillis();
        Conversation conv = new Conversation(DmRules.dmKey(player.getUUID(), target), false, "", player.getUUID(), now);
        conv.members.put(player.getUUID(), new Conversation.Member(player.getGameProfile().getName(), now));
        conv.members.put(target, new Conversation.Member(targetName, now));
        store.add(conv);
        return conv;
    }

    @Nullable
    private static String nameOf(MinecraftServer server, UUID player) {
        ServerPlayer online = server.getPlayerList().getPlayer(player);
        if (online != null) {
            return online.getGameProfile().getName();
        }
        return server.getProfileCache() == null ? null
                : server.getProfileCache().get(player).map(GameProfile::getName).orElse(null);
    }

    /**
     * {@code /msg}, {@code /tell}, {@code /w} to one other player from a player with Eden: the line goes to their DM
     * instead of a whisper. False (vanilla whispers) for the console, clients without Eden and several targets.
     */
    public static boolean routeTell(CommandSourceStack source, Collection<ServerPlayer> targets,
                                    PlayerChatMessage message) {
        ServerPlayer player = source.getPlayer();
        if (player == null || targets.size() != 1 || !eden(player)) {
            return false;
        }
        ServerPlayer target = targets.iterator().next();
        MinecraftServer server = player.getServer();
        if (server == null || target == player) {
            return false;
        }
        String text = message.signedContent().trim();
        if (text.isEmpty()) {
            return true;
        }
        ConversationStore store = ConversationStore.get(server);
        Conversation conv = store.get(DmRules.dmKey(player.getUUID(), target.getUUID()));
        Component refused = refusal(server, player.getUUID(), target.getUUID(), target.getGameProfile().getName(),
                conv);
        if (refused != null) {
            error(player, refused);
            return true;
        }
        Component guard = ChatGuard.check(player, text);
        if (guard != null) {
            if (!guard.getString().isEmpty()) {
                error(player, guard);
            }
            return true;
        }
        if (conv == null) {
            conv = newDm(store, player, target.getUUID(), target.getGameProfile().getName());
        }
        deliver(server, store, conv, player, text);
        return true;
    }

    // ---------------------------------------------------------------- lines

    /** A line typed in a conversation's tab. */
    private static void send(ServerPlayer player, String id, String text) {
        MinecraftServer server = player.getServer();
        String trimmed = text == null ? "" : text.trim();
        if (server == null || trimmed.isEmpty()) {
            return;
        }
        ConversationStore store = ConversationStore.get(server);
        Conversation conv = store.get(id);
        if (conv == null || conv.member(player.getUUID()) == null) {
            gone(player, id);
            return;
        }
        if (!conv.group) {
            for (Map.Entry<UUID, Conversation.Member> entry : conv.members.entrySet()) {
                if (!entry.getKey().equals(player.getUUID())) {
                    Component refused = refusal(server, player.getUUID(), entry.getKey(), entry.getValue().name, conv);
                    if (refused != null) {
                        note(player, conv, refused.copy().withStyle(ChatFormatting.RED));
                        return;
                    }
                }
            }
        }
        Component guard = ChatGuard.check(player, trimmed);
        if (guard != null) {
            if (!guard.getString().isEmpty()) {
                note(player, conv, guard.copy().withStyle(ChatFormatting.RED));
            }
            return;
        }
        deliver(server, store, conv, player, trimmed);
    }

    /**
     * Keeps the line and delivers it to every online member (in a group, not to those who ignore the writer): its tab
     * opens again for whoever closed it, and a client that doesn't know the conversation yet gets it first.
     */
    private static void deliver(MinecraftServer server, ConversationStore store, Conversation conv, ServerPlayer sender,
                                String text) {
        long now = System.currentTimeMillis();
        ChatStyle.Content content = ChatStyle.content(sender, text);
        Conversation.Line line = new Conversation.Line(now, sender.getUUID(),
                Component.Serializer.toJson(sender.getDisplayName(), server.registryAccess()),
                Component.Serializer.toJson(content.component(), server.registryAccess()));
        conv.lines.addLast(line);
        DmRules.trim(conv.lines, Conversation.Line::time, now);
        conv.touched = now;
        Conversation.Member self = conv.member(sender.getUUID());
        if (self != null) {
            self.readAt = now;
            self.name = sender.getGameProfile().getName();
        }
        boolean reopened = false;
        for (Conversation.Member member : conv.members.values()) {
            reopened |= member.hidden;
            member.hidden = false;
        }
        store.changed();
        for (UUID member : conv.members.keySet()) {
            ServerPlayer online = server.getPlayerList().getPlayer(member);
            if (online == null || conv.group && IgnoreList.ignores(server, member, sender.getUUID())) {
                continue;
            }
            if (eden(online)) {
                Set<String> known = KNOWN.computeIfAbsent(member, k -> new HashSet<>());
                if (!known.contains(conv.id)) {
                    // Its lines up to now, this one left out: it comes next as a chat line.
                    conv.lines.removeLast();
                    ServerPlayNetworking.send(online, data(server, conv, member, false, true));
                    conv.lines.addLast(line);
                    known.add(conv.id);
                } else if (reopened) {
                    ServerPlayNetworking.send(online, data(server, conv, member, false, false));
                }
            }
            String partner = conv.group ? DmRules.GROUP_PARTNER : otherName(conv, member);
            ChatRecall.sendPrivate(online, lineFor(server, conv, line, member), partner);
        }
    }

    // ---------------------------------------------------------------- the tab's actions

    private static void action(ServerPlayer player, HubPayloads.ConvAction action) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }
        ConversationStore store = ConversationStore.get(server);
        Conversation conv = store.get(action.conv());
        Conversation.Member self = conv == null ? null : conv.member(player.getUUID());
        if (conv == null || self == null) {
            gone(player, action.conv());
            return;
        }
        switch (action.action()) {
            case HubPayloads.ConvAction.HIDE -> {
                self.hidden = true;
                store.changed();
            }
            case HubPayloads.ConvAction.READ -> {
                self.readAt = System.currentTimeMillis();
                store.changed();
            }
            case HubPayloads.ConvAction.NOTIFY -> {
                try {
                    self.notify = HubSettings.ChannelNotify.of(Integer.parseInt(action.text())).ordinal();
                    store.changed();
                } catch (NumberFormatException ignored) {
                    // Not a level: nothing changes.
                }
            }
            case HubPayloads.ConvAction.LEAVE -> leave(server, store, conv, player);
            case HubPayloads.ConvAction.RENAME -> rename(server, store, conv, player, action.text());
            case HubPayloads.ConvAction.INVITE -> invite(server, store, conv, player, action.text());
            default -> {
            }
        }
    }

    // ---------------------------------------------------------------- groups

    private static void createGroup(ServerPlayer player, String rawName, List<UUID> invite) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }
        String name = DmRules.cleanName(rawName);
        if (name == null) {
            return;
        }
        ConversationStore store = ConversationStore.get(server);
        if (store.groupsOf(player.getUUID()) >= DmRules.MAX_GROUPS) {
            error(player, Component.translatable("message.eden_ld.dm.too_many_groups", DmRules.MAX_GROUPS));
            return;
        }
        // The name is text every invitee reads (the tab, each line's tag): it passes what a chat line passes — not
        // while muted, and a rate-limit token (review finding 26).
        Component guard = ChatGuard.check(player, name);
        if (guard != null) {
            guardNote(player, null, guard);
            return;
        }
        String id;
        do {
            id = DmRules.groupKey(ThreadLocalRandom.current().nextLong());
        } while (store.get(id) != null);
        long now = System.currentTimeMillis();
        Conversation conv = new Conversation(id, true, name, player.getUUID(), now);
        conv.members.put(player.getUUID(), new Conversation.Member(player.getGameProfile().getName(), now));
        store.add(conv);
        ServerPlayNetworking.send(player, data(server, conv, player.getUUID(), true, true));
        KNOWN.computeIfAbsent(player.getUUID(), k -> new HashSet<>()).add(id);
        note(player, conv, Component.translatable("message.eden_ld.dm.group.created", name));
        // No more than fit (the packet's list has no cap of its own): the rest would only be "full" notes.
        int room = DmRules.MAX_MEMBERS - 1;
        for (UUID target : new java.util.LinkedHashSet<>(invite)) {
            if (room <= 0) {
                break;
            }
            ServerPlayer online = server.getPlayerList().getPlayer(target);
            if (online != null) {
                add(server, store, conv, player, online);
                room--;
            }
        }
    }

    private static void invite(MinecraftServer server, ConversationStore store, Conversation conv, ServerPlayer player,
                               String name) {
        if (!conv.group) {
            return;
        }
        // Each invite is a line to every member (and re-adds someone who left): it takes a chat token (review finding 26).
        if (!ChatGuard.take(player.getUUID())) {
            note(player, conv, Component.translatable("message.eden_ld.chat.rate_limited").withStyle(ChatFormatting.RED));
            return;
        }
        ServerPlayer target = server.getPlayerList().getPlayerByName(name);
        if (target == null) {
            note(player, conv, Component.translatable("message.eden_ld.dm.not_online", name));
            return;
        }
        add(server, store, conv, player, target);
    }

    /** {@code by} adds {@code target} to the group, if the cap, the target's groups and their privacy allow it. */
    private static void add(MinecraftServer server, ConversationStore store, Conversation conv, ServerPlayer by,
                            ServerPlayer target) {
        String targetName = target.getGameProfile().getName();
        if (conv.members.containsKey(target.getUUID())) {
            return;
        }
        if (conv.members.size() >= DmRules.MAX_MEMBERS) {
            note(by, conv, Component.translatable("message.eden_ld.dm.group.full", DmRules.MAX_MEMBERS));
            return;
        }
        Component refused = refusal(server, by.getUUID(), target.getUUID(), targetName, null);
        if (refused == null && store.groupsOf(target.getUUID()) >= DmRules.MAX_GROUPS) {
            refused = Component.translatable("message.eden_ld.dm.group.their_limit", targetName);
        }
        if (refused != null) {
            note(by, conv, refused.copy().withStyle(ChatFormatting.RED));
            return;
        }
        store.addMember(conv, target.getUUID(), targetName, System.currentTimeMillis());
        pushMeta(server, conv);
        noteAll(server, conv, Component.translatable("message.eden_ld.dm.group.added",
                by.getGameProfile().getName(), targetName), by.getUUID());
    }

    private static void leave(MinecraftServer server, ConversationStore store, Conversation conv, ServerPlayer player) {
        if (!conv.group) {
            // A DM can't be left: its tab closes.
            Conversation.Member self = conv.member(player.getUUID());
            if (self != null) {
                self.hidden = true;
                store.changed();
            }
            return;
        }
        store.removeMember(conv, player.getUUID());
        Set<String> known = KNOWN.get(player.getUUID());
        if (known != null) {
            known.remove(conv.id);
        }
        ServerPlayNetworking.send(player, new HubPayloads.ConvGone(conv.id));
        if (conv.members.isEmpty()) {
            return;
        }
        if (conv.owner.equals(player.getUUID())) {
            // The longest member takes the group over (they may rename it).
            conv.owner = conv.members.keySet().iterator().next();
        }
        conv.touched = System.currentTimeMillis();
        pushMeta(server, conv);
        noteAll(server, conv, Component.translatable("message.eden_ld.dm.group.left", player.getGameProfile().getName()),
                player.getUUID());
    }

    private static void rename(MinecraftServer server, ConversationStore store, Conversation conv, ServerPlayer player,
                               String raw) {
        String name = DmRules.cleanName(raw);
        if (!conv.group || name == null || !conv.owner.equals(player.getUUID()) || name.equals(conv.name)) {
            return;
        }
        // A rename is text every member reads, as a line and as the tab's title (review finding 26): not while muted,
        // within the chat's rate limit, and at most once per RENAME_COOLDOWN_MS.
        long now = Util.getMillis();
        Long last = LAST_RENAME.get(player.getUUID());
        if (last != null && now - last < RENAME_COOLDOWN_MS) {
            note(player, conv, Component.translatable("message.eden_ld.chat.rate_limited").withStyle(ChatFormatting.RED));
            return;
        }
        Component guard = ChatGuard.check(player, name);
        if (guard != null) {
            guardNote(player, conv, guard);
            return;
        }
        LAST_RENAME.put(player.getUUID(), now);
        conv.name = name;
        conv.touched = System.currentTimeMillis();
        store.changed();
        pushMeta(server, conv);
        noteAll(server, conv, Component.translatable("message.eden_ld.dm.group.renamed",
                player.getGameProfile().getName(), name), player.getUUID());
    }
}
