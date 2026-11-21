package net.ledok.eden_ld.hub.guild;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
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
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Server side of the Guild tab: payload receivers, tab data, and syncing. Guild changes are
 * rare, so every change re-sends guild data to everyone online (the directory, invite
 * candidates, and client combat matchers all follow).
 */
public final class GuildServer {

    private static final Set<UUID> CREATING = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * Coalesced syncs (review finding 38): actions only raise these, and {@link #drain} runs the server-wide
     * fan-out at most once per tick, however many packets arrived. {@link #SELF_PENDING}: actors whose action
     * changed nothing get only their own data back (once per tick), so the client's view still refreshes.
     */
    private static final AtomicBoolean SYNC_PENDING = new AtomicBoolean();
    private static final AtomicBoolean MEMBERSHIP_PENDING = new AtomicBoolean();
    private static final Set<UUID> SELF_PENDING = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /**
     * Guilds whose open banks must be re-sent after this tick's sync (review finding 97): the bank shows the claim
     * limit, which only the sync's OPAC reconcile updates, so a push before it showed the old limit.
     */
    private static final Set<UUID> BANK_PENDING = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private GuildServer() {
    }

    public static void initialize() {
        GuildMemberClaims.initialize();
        ServerPlayNetworking.registerGlobalReceiver(HubPayloads.GuildDataRequest.TYPE,
                (payload, context) -> send(context.player()));
        ServerPlayNetworking.registerGlobalReceiver(HubPayloads.GuildAction.TYPE,
                (payload, context) -> {
                    if (payload.kind() == HubPayloads.GuildAction.Action.CHAT
                            || net.ledok.eden_ld.hub.HubRateLimit.GUILD_ACTION.allow(context.player())) {
                        handle(context.player(), payload);
                    }
                });
        // Deferred a tick: JOIN fires before the player is in the list (see HubServer).
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> server.execute(() -> {
            GuildManager.get(server).onJoin(handler.getPlayer());
            scheduleSync(false);
        }));
        // Once the leaver is out of the player list, so the members' tabs show them offline (review finding 91).
        net.ledok.eden_ld.hub.HubServer.onLeft((server, gone) -> scheduleSync(false));
        ServerTickEvents.END_SERVER_TICK.register(GuildServer::drain);
        ServerLifecycleEvents.SERVER_STOPPED.register(stopped -> {
            SYNC_PENDING.set(false);
            MEMBERSHIP_PENDING.set(false);
            SELF_PENDING.clear();
            BANK_PENDING.clear();
            // A creation fee's take answers no more once the server stops (review of 1.3.133).
            CREATING.clear();
        });
    }

    /**
     * Guild data to everyone online at the end of this tick, once (review finding 38). With {@code membership} the
     * open banks and waystones follow too (ranks decide bank rights, membership whose waystones are shared).
     * Thread-safe: it only raises flags.
     */
    public static void scheduleSync(boolean membership) {
        if (membership) {
            MEMBERSHIP_PENDING.set(true);
        }
        SYNC_PENDING.set(true);
    }

    /**
     * Like {@link #scheduleSync scheduleSync(false)}, and this guild's open banks are re-sent right after the sync
     * (review finding 97): for changes to the claim limit (a claim upgrade, a member's Prime), which the bank's
     * "used / limit" line shows. Thread-safe.
     */
    public static void scheduleSyncAndBank(UUID guildId) {
        BANK_PENDING.add(guildId);
        SYNC_PENDING.set(true);
    }

    /** Runs what {@link #scheduleSync} and failed actions asked for this tick (review finding 38). */
    private static void drain(MinecraftServer server) {
        boolean all = SYNC_PENDING.getAndSet(false);
        boolean membership = MEMBERSHIP_PENDING.getAndSet(false);
        if (all) {
            SELF_PENDING.clear();
            syncAll(server);
        } else if (!SELF_PENDING.isEmpty()) {
            for (java.util.Iterator<UUID> it = SELF_PENDING.iterator(); it.hasNext(); ) {
                ServerPlayer online = server.getPlayerList().getPlayer(it.next());
                it.remove();
                if (online != null) {
                    send(online);
                }
            }
        }
        if (membership) {
            BANK_PENDING.clear();
            GuildBankServer.pushAll(server);
            net.ledok.eden_ld.hub.HubServer.refreshWaystones(server);
        } else if (!BANK_PENDING.isEmpty()) {
            GuildManager manager = GuildManager.get(server);
            for (java.util.Iterator<UUID> it = BANK_PENDING.iterator(); it.hasNext(); ) {
                Guild guild = manager.byId(it.next());
                it.remove();
                if (guild != null) {
                    GuildBankServer.pushToViewers(server, guild);
                }
            }
        }
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
        switch (action.kind()) {
            case CREATE, INVITE, ACCEPT_INVITE, APPLY, JOIN, ACCEPT_APPLICATION -> {
                // No joining, founding, inviting or accepting mid-run or in the tutorial (accepting: review
                // finding 68).
                if (net.ledok.eden_ld.hub.BusyLocks.refuse(player)) {
                    return;
                }
            }
            default -> {
            }
        }
        if (action.kind() == HubPayloads.GuildAction.Action.CREATE) {
            create(server, player, action.text(), action.text2(), action.value());
            return;
        }
        // Whether the void mutators below will change anything, asked before they run (review finding 38): a refused
        // or empty action must not cost a server-wide resync.
        Guild mine = manager.guildOf(me);
        boolean officer = mine != null && mine.rank(me).atLeast(GuildRank.OFFICER);
        boolean willChange = switch (action.kind()) {
            case CANCEL_INVITE -> officer && manager.invitesFor(target).containsKey(mine.id);
            case DECLINE_INVITE -> manager.invitesFor(me).containsKey(target);
            case CANCEL_APPLICATION -> {
                Guild applied = manager.byId(target);
                yield applied != null && applied.applications.containsKey(me);
            }
            case DECLINE_APPLICATION -> officer && mine.applications.containsKey(target);
            // Accepting also drops an application whose applicant joined another guild meanwhile (an error result);
            // a busy applicant is refused and nothing changes (review finding 68).
            case ACCEPT_APPLICATION -> officer && mine.applications.containsKey(target)
                    && (manager.guildOf(target) != null || !net.ledok.busylib.BusyState.isBusy(target));
            default -> false;
        };
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
        if (action.kind() == HubPayloads.GuildAction.Action.CHAT) {
            return;
        }
        boolean changed = switch (action.kind()) {
            case CANCEL_INVITE, DECLINE_INVITE, CANCEL_APPLICATION, DECLINE_APPLICATION, ACCEPT_APPLICATION ->
                    willChange;
            default -> result == null;
        };
        if (changed) {
            // Ranks decide bank rights (open banks follow promotions, kicks, and disbanding); membership decides who
            // sees whose guild waystones. Once per tick, however many actions came in (review finding 38).
            scheduleSync(true);
        } else {
            // Nothing changed: only the actor's own view refreshes (review finding 38).
            SELF_PENDING.add(me);
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
            scheduleSync(false);
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
