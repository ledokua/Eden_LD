package net.ledok.eden_ld.hub.guild;

import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.ledok.eden_ld.compat.opac.OpacCompat;
import net.ledok.eden_ld.EdenLD;
import net.ledok.eden_ld.chat.ChatChannels;
import net.ledok.eden_ld.compat.EconomyCompat;
import net.ledok.eden_ld.hub.HubPayloads;
import net.ledok.eden_ld.hub.party.PartyManager;
import net.ledok.eden_ld.minestar.MinestarHook;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Server side of the Guild tab: payload receivers, tab data, and syncing. Guild changes are
 * rare, so every change re-sends guild data to everyone online (the directory, invite
 * candidates, and client combat matchers all follow).
 */
public final class GuildServer {

    private static final Set<UUID> CREATING = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private GuildServer() {
    }

    public static void initialize() {
        ServerPlayNetworking.registerGlobalReceiver(HubPayloads.GuildDataRequest.TYPE,
                (payload, context) -> send(context.player()));
        ServerPlayNetworking.registerGlobalReceiver(HubPayloads.GuildAction.TYPE,
                (payload, context) -> handle(context.player(), payload));
        // Deferred a tick: JOIN fires before the player is in the list (see HubServer).
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> server.execute(() -> {
            GuildManager.get(server).onJoin(handler.getPlayer());
            syncAll(server);
        }));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                server.execute(() -> syncAll(server)));
    }

    /** Guild data to everyone online. */
    public static void syncAll(MinecraftServer server) {
        // Every guild change passes through here: keep OPAC's guild claims in step first.
        OpacCompat.reconcile(server);
        for (ServerPlayer online : server.getPlayerList().getPlayers()) {
            send(online);
        }
        // Tags above heads follow guild changes (tag, color, membership).
        net.ledok.eden_ld.hub.HubServer.syncNameStyles(server);
        // Membership opens or closes the GUILD chat channel.
        net.ledok.eden_ld.chat.ChatChannels.pushStateAll(server);
        // Guild level perks add favorite slots: joining or leaving changes them.
        net.ledok.eden_ld.chat.emoji.EmojiRegistry.refreshLimits(server);
    }

    public static void send(ServerPlayer player) {
        if (!player.hasDisconnected()) {
            ServerPlayNetworking.send(player, build(player));
        }
    }

    public static void broadcastChatLine(MinecraftServer server, Guild guild, Guild.ChatLine line) {
        HubPayloads.GuildChatLine payload = new HubPayloads.GuildChatLine(
                new HubPayloads.ChatEntry(line.sender(), line.name(), line.text()));
        for (UUID member : guild.members.keySet()) {
            ServerPlayer online = server.getPlayerList().getPlayer(member);
            if (online != null) {
                ServerPlayNetworking.send(online, payload);
            }
        }
    }

    private static HubPayloads.GuildRef ref(GuildManager manager, Guild guild) {
        return new HubPayloads.GuildRef(guild.id, guild.name, guild.tag, guild.color, guild.members.size(),
                manager.memberLimit(guild), guild.joinMode.ordinal(), GuildLevels.level(guild));
    }

    private static final HubPayloads.GuildRef NONE =
            new HubPayloads.GuildRef(new UUID(0L, 0L), "", "", 0, 0, 0, 0, 0);

    private static HubPayloads.GuildData build(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        long cost = Math.max(0, EdenLD.CONFIG.hub.guild_create_cost);
        if (server == null) {
            return new HubPayloads.GuildData(false, NONE, "", 0, List.of(), List.of(), List.of(), List.of(),
                    List.of(), List.of(), List.of(), List.of(), cost, EconomyCompat.available());
        }
        GuildManager manager = GuildManager.get(server);
        PartyManager parties = PartyManager.get(server);
        Guild guild = manager.guildOf(player.getUUID());

        List<HubPayloads.GuildRef> directory = new ArrayList<>();
        for (Guild other : manager.all()) {
            directory.add(ref(manager, other));
        }
        directory.sort(Comparator.comparing(ref -> ref.name().toLowerCase(Locale.ROOT)));

        if (guild == null) {
            List<HubPayloads.GuildInvite> invites = new ArrayList<>();
            manager.invitesFor(player.getUUID()).forEach((guildId, invite) -> {
                Guild inviting = manager.byId(guildId);
                if (inviting != null) {
                    invites.add(new HubPayloads.GuildInvite(ref(manager, inviting), invite.inviterName()));
                }
            });
            return new HubPayloads.GuildData(false, NONE, "", 0, List.of(), List.of(), List.of(), List.of(),
                    List.of(), invites, manager.applicationsOf(player.getUUID()), directory, cost,
                    EconomyCompat.available());
        }

        List<HubPayloads.GuildMember> members = new ArrayList<>();
        Set<UUID> viewerFriends = MinestarHook.friendUuids(player.getUUID());
        guild.members.forEach((uuid, rank) -> members.add(new HubPayloads.GuildMember(uuid,
                guild.memberNames.getOrDefault(uuid, "?"), rank.level,
                server.getPlayerList().getPlayer(uuid) != null,
                parties.friendlyFireProtectionSuspended(uuid),
                net.ledok.eden_ld.hub.profile.ProfileServer.mayView(server, player.getUUID(), uuid,
                        viewerFriends.contains(uuid)))));
        // Leader first, then by rank, online before offline, then name.
        members.sort(Comparator.comparingInt((HubPayloads.GuildMember m) -> -m.rank())
                .thenComparing(m -> !m.online())
                .thenComparing(m -> m.name().toLowerCase(Locale.ROOT)));

        GuildRank myRank = guild.rank(player.getUUID());
        boolean officer = myRank.atLeast(GuildRank.OFFICER);
        List<HubPayloads.NameRef> outgoing = new ArrayList<>();
        List<HubPayloads.NameRef> applications = new ArrayList<>();
        List<HubPayloads.PartyMember> candidates = new ArrayList<>();
        if (officer) {
            manager.outgoingInvites(guild.id).forEach((uuid, invite) -> {
                ServerPlayer online = server.getPlayerList().getPlayer(uuid);
                outgoing.add(new HubPayloads.NameRef(uuid, online != null ? online.getGameProfile().getName() : "?"));
            });
            manager.pruneApplicationsOf(guild);
            guild.applications.keySet().forEach(uuid ->
                    applications.add(new HubPayloads.NameRef(uuid, guild.applicantNames.getOrDefault(uuid, "?"))));
            Set<UUID> friends = MinestarHook.friendUuids(player.getUUID());
            Set<UUID> invited = manager.outgoingInvites(guild.id).keySet();
            for (ServerPlayer online : server.getPlayerList().getPlayers()) {
                // Not those who ignore this officer: they wouldn't get the invite.
                if (manager.guildOf(online.getUUID()) == null && !invited.contains(online.getUUID())
                        && !net.ledok.eden_ld.hub.IgnoreList.ignores(server, online.getUUID(), player.getUUID())) {
                    candidates.add(new HubPayloads.PartyMember(online.getUUID(), online.getGameProfile().getName(),
                            0, true, "", friends.contains(online.getUUID())));
                }
            }
        }
        List<HubPayloads.ChatEntry> chat = new ArrayList<>();
        for (Guild.ChatLine line : guild.chat) {
            chat.add(new HubPayloads.ChatEntry(line.sender(), line.name(), line.text()));
        }
        return new HubPayloads.GuildData(true, ref(manager, guild), guild.motd, myRank.level, members,
                outgoing, applications, chat, candidates, List.of(), List.of(), directory, cost,
                EconomyCompat.available());
    }

    // ---------------------------------------------------------------- actions

    private static void handle(ServerPlayer player, HubPayloads.GuildAction action) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }
        GuildManager manager = GuildManager.get(server);
        UUID me = player.getUUID();
        UUID target = action.target();
        if (action.kind() == HubPayloads.GuildAction.Action.CREATE) {
            create(server, player, action.text(), action.text2(), action.value());
            return;
        }
        Component result = switch (action.kind()) {
            case INVITE -> {
                ServerPlayer invitee = server.getPlayerList().getPlayer(target);
                yield invitee == null ? Component.translatable("message.eden_ld.guild.not_online")
                        : manager.invite(player, invitee);
            }
            case CANCEL_INVITE -> {
                manager.cancelInvite(me, target);
                yield null;
            }
            case ACCEPT_INVITE -> manager.acceptInvite(server, player, target);
            case DECLINE_INVITE -> {
                manager.declineInvite(me, target);
                yield null;
            }
            case APPLY -> manager.apply(server, player, target);
            case CANCEL_APPLICATION -> {
                manager.cancelApplication(me, target);
                yield null;
            }
            case ACCEPT_APPLICATION -> manager.acceptApplication(server, me, target);
            case DECLINE_APPLICATION -> {
                manager.declineApplication(me, target);
                yield null;
            }
            case JOIN -> manager.join(server, player, target);
            case LEAVE -> manager.leave(server, player);
            case KICK -> manager.kick(server, me, target);
            case PROMOTE -> manager.promote(me, target);
            case DEMOTE -> manager.demote(me, target);
            case TRANSFER -> manager.transfer(server, me, target);
            case DISBAND -> manager.disband(server, me);
            case RENAME -> manager.rename(me, action.text());
            case SET_TAG -> manager.setTag(me, action.text());
            case SET_COLOR -> manager.setColor(me, action.value());
            case SET_MOTD -> manager.setMotd(me, action.text());
            case SET_JOIN_MODE -> manager.setJoinMode(me,
                    Guild.JoinMode.values()[Math.floorMod(action.value(), Guild.JoinMode.values().length)]);
            case CHAT -> ChatChannels.send(player, ChatChannels.Channel.GUILD, action.text());
            case CREATE -> null;
        };
        if (result != null) {
            fail(player, result);
        }
        if (action.kind() != HubPayloads.GuildAction.Action.CHAT) {
            syncAll(server);
            // Ranks decide bank rights: open banks follow promotions, kicks, and disbanding.
            GuildBankServer.pushAll(server);
            // Membership decides who sees whose guild waystones.
            net.ledok.eden_ld.hub.HubServer.refreshWaystones(server);
        }
    }

    /** Validates first, then charges the creation cost (if any), then founds the guild. */
    private static void create(MinecraftServer server, ServerPlayer player, String name, String tag, int color) {
        GuildManager manager = GuildManager.get(server);
        Component invalid = manager.guildOf(player.getUUID()) != null
                ? Component.translatable("message.eden_ld.guild.already_in_guild")
                : manager.validateName(name, null);
        if (invalid == null) {
            invalid = manager.validateTag(tag, null);
        }
        if (invalid != null) {
            fail(player, invalid);
            return;
        }
        long cost = Math.max(0, EdenLD.CONFIG.hub.guild_create_cost);
        if (cost > 0 && !EconomyCompat.available()) {
            fail(player, Component.translatable("message.eden_ld.guild.no_economy"));
            return;
        }
        if (!CREATING.add(player.getUUID())) {
            return;
        }
        EconomyCompat.take(player, cost, paid -> {
            CREATING.remove(player.getUUID());
            if (!paid) {
                fail(player, Component.translatable("message.eden_ld.guild.not_enough_lc", cost));
                return;
            }
            Component error = manager.create(player, name, tag, color);
            if (error != null) {
                EconomyCompat.give(player, cost);
                fail(player, error);
                return;
            }
            player.sendSystemMessage(Component.translatable("message.eden_ld.guild.created", name.trim())
                    .withStyle(ChatFormatting.GREEN));
            syncAll(server);
        });
    }

    private static void fail(ServerPlayer player, @Nullable Component message) {
        if (message == null) {
            return;
        }
        player.sendSystemMessage(message.copy().withStyle(ChatFormatting.RED));
        ServerPlayNetworking.send(player, new HubPayloads.HubNotice(message));
    }
}
