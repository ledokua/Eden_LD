package net.ledok.eden_ld.hub.party;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.ledok.eden_ld.EdenLD;
import net.ledok.eden_ld.api.hub.EdenPartyApi;
import net.ledok.eden_ld.hub.HubServer;
import net.ledok.eden_ld.hub.HubSettings;
import net.ledok.eden_ld.minestar.MinestarHook;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * All party state and mutations. Parties are TEMPORARY — in-memory only, born when
 * an invite from a solo player is accepted, destroyed when the member count drops
 * below 2 or the last member logs off. Disconnected members get a grace window
 * (config {@code party_offline_grace_seconds}) before they are auto-kicked; a
 * disconnecting leader hands leadership to the oldest online member immediately.
 * Every public mutation validates rank and policy server-side and returns a
 * translatable error, or null on success — the caller re-syncs clients afterwards.
 */
public class PartyManager {

    private static final long INVITE_EXPIRY_MS = 5 * 60 * 1000;
    private static final int CHAT_HISTORY_LIMIT = 40;
    public static final int MAX_NAME_LENGTH = 24;

    private static final Map<MinecraftServer, PartyManager> INSTANCES = new ConcurrentHashMap<>();

    private final Map<UUID, Party> parties = new HashMap<>();
    private final Map<UUID, UUID> memberIndex = new HashMap<>();
    /** Players whose party protection is switched off, by reason (e.g. a duel mod's match). */
    private final Map<UUID, Set<net.minecraft.resources.ResourceLocation>> friendlyFireSuspensions = new HashMap<>();
    /** invitee → (inviter → invite). Solo inviters have no party yet ({@code partyId} null). */
    private final Map<UUID, Map<UUID, Invite>> invites = new HashMap<>();

    public record Invite(UUID inviter, String inviterName, String targetName,
                         @Nullable UUID partyId, long expiresAt) {
        public boolean expired() {
            return System.currentTimeMillis() > expiresAt;
        }
    }

    public static PartyManager get(MinecraftServer server) {
        return INSTANCES.computeIfAbsent(server, s -> new PartyManager());
    }

    /** Lifecycle hooks: grace-period pruning, leader succession, last-logout cleanup. */
    public static void initialize() {
        ServerLifecycleEvents.SERVER_STOPPED.register(INSTANCES::remove);
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (server.getTickCount() % 20 == 0) {
                get(server).tick(server);
            }
        });
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
                get(server).onJoin(handler.getPlayer()));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                get(server).onDisconnect(server, handler.getPlayer()));
    }

    // ---------------------------------------------------------------- queries

    @Nullable
    public Party partyOf(UUID player) {
        UUID id = memberIndex.get(player);
        return id == null ? null : parties.get(id);
    }

    @Nullable
    public Party byId(UUID id) {
        return parties.get(id);
    }

    /** Lookup by the unique tag (display names are NOT unique). */
    @Nullable
    public Party byTag(String tag) {
        return parties.values().stream()
                .filter(p -> p.tag.equalsIgnoreCase(tag))
                .findFirst().orElse(null);
    }

    /** All parties (read-only view), e.g. for admin command suggestions. */
    public java.util.Collection<Party> all() {
        return java.util.Collections.unmodifiableCollection(parties.values());
    }

    public boolean sameParty(UUID a, UUID b) {
        UUID pa = memberIndex.get(a);
        return pa != null && pa.equals(memberIndex.get(b));
    }

    /**
     * Whether party protection (no damage, projectiles pass through, pets hold off, combat
     * mods treat them as teammates) applies between {@code a} and {@code b}: partymates,
     * and neither has friendly fire suspended.
     */
    public boolean protects(UUID a, UUID b) {
        return sameParty(a, b) && !friendlyFireProtectionSuspended(a) && !friendlyFireProtectionSuspended(b);
    }

    /**
     * Eden's full friendly-fire rule: partymates or guildmates, neither suspended. This is what
     * damage, projectiles, pets, and the combat-mod matchers ask.
     */
    public static boolean protects(MinecraftServer server, UUID a, UUID b) {
        PartyManager parties = get(server);
        if (parties.friendlyFireProtectionSuspended(a) || parties.friendlyFireProtectionSuspended(b)) {
            return false;
        }
        return parties.sameParty(a, b)
                || net.ledok.eden_ld.hub.guild.GuildManager.get(server).sameGuild(a, b);
    }

    public boolean friendlyFireProtectionSuspended(UUID player) {
        Set<net.minecraft.resources.ResourceLocation> reasons = friendlyFireSuspensions.get(player);
        return reasons != null && !reasons.isEmpty();
    }

    /** True when this changed whether the player is suspended (first reason added). */
    public boolean suspendFriendlyFireProtection(UUID player, net.minecraft.resources.ResourceLocation reason) {
        boolean wasSuspended = friendlyFireProtectionSuspended(player);
        friendlyFireSuspensions.computeIfAbsent(player, k -> new java.util.HashSet<>()).add(reason);
        return !wasSuspended;
    }

    /** True when this changed whether the player is suspended (last reason removed). */
    public boolean resumeFriendlyFireProtection(UUID player, net.minecraft.resources.ResourceLocation reason) {
        Set<net.minecraft.resources.ResourceLocation> reasons = friendlyFireSuspensions.get(player);
        if (reasons == null || !reasons.remove(reason)) {
            return false;
        }
        if (reasons.isEmpty()) {
            friendlyFireSuspensions.remove(player);
            return true;
        }
        return false;
    }

    /** "{@code <creator>_party}", numbered while taken. */
    private String uniqueTag(String creatorName) {
        String base = creatorName.isBlank() ? "party" : creatorName + "_party";
        String tag = base;
        int n = 2;
        while (byTag(tag) != null) {
            tag = base + n++;
        }
        return tag;
    }

    /** Invites waiting for this player: inviter → invite (expired/stale ones pruned). */
    public Map<UUID, Invite> invitesFor(UUID player) {
        Map<UUID, Invite> waiting = invites.get(player);
        if (waiting == null) {
            return Map.of();
        }
        waiting.entrySet().removeIf(e -> {
            Invite invite = e.getValue();
            if (invite.expired()) {
                return true;
            }
            // A party invite whose party died, or a solo invite whose inviter got
            // into a party they may not invite to, is dead.
            if (invite.partyId != null) {
                return !parties.containsKey(invite.partyId);
            }
            return memberIndex.containsKey(invite.inviter);
        });
        return waiting;
    }

    /** Unexpired invites sent by this party (target uuid → invite). */
    public Map<UUID, Invite> outgoingInvites(UUID partyId) {
        Map<UUID, Invite> result = new HashMap<>();
        for (Map.Entry<UUID, Map<UUID, Invite>> entry : invites.entrySet()) {
            for (Invite invite : entry.getValue().values()) {
                if (partyId.equals(invite.partyId) && !invite.expired()) {
                    result.put(entry.getKey(), invite);
                }
            }
        }
        return result;
    }

    /** Unexpired solo invites sent by this (party-less) player (target uuid → invite). */
    public Map<UUID, Invite> soloOutgoingInvites(UUID inviter) {
        Map<UUID, Invite> result = new HashMap<>();
        for (Map.Entry<UUID, Map<UUID, Invite>> entry : invites.entrySet()) {
            Invite invite = entry.getValue().get(inviter);
            if (invite != null && invite.partyId == null && !invite.expired()) {
                result.put(entry.getKey(), invite);
            }
        }
        return result;
    }

    /** Parties this player could join directly under their join policy. */
    public List<Party> joinableFor(ServerPlayer player) {
        if (partyOf(player.getUUID()) != null) {
            return List.of();
        }
        Set<UUID> friends = MinestarHook.friendUuids(player.getUUID());
        List<Party> joinable = new ArrayList<>();
        for (Party party : parties.values()) {
            if (party.members.size() >= maxSize()) {
                continue;
            }
            boolean allowed = switch (party.joinPolicy) {
                case INVITE_ONLY -> false;
                case OPEN -> true;
                case FRIENDS -> party.members.keySet().stream().anyMatch(friends::contains);
            };
            if (allowed) {
                joinable.add(party);
            }
        }
        return joinable;
    }

    private int maxSize() {
        return Math.max(2, EdenLD.CONFIG.hub.party_max_size);
    }

    private static Component error(String key, Object... args) {
        return Component.translatable("message.eden_ld.party." + key, args);
    }

    private static void notify(MinecraftServer server, UUID player, String key, Object... args) {
        ServerPlayer online = server.getPlayerList().getPlayer(player);
        if (online != null) {
            online.sendSystemMessage(Component.translatable("message.eden_ld.party." + key, args)
                    .withStyle(ChatFormatting.GOLD));
        }
    }

    private void notifyMembers(MinecraftServer server, Party party, String key, Object... args) {
        for (UUID member : party.members.keySet()) {
            notify(server, member, key, args);
        }
    }

    // ---------------------------------------------------------------- invites

    /**
     * Whether {@code inviter} may invite {@code target} right now, and if not, why. Shared
     * by the invite action, the invite picker, and the Friends tab buttons so they agree.
     * Busy state is the caller's concern (BusyLib lives in the hub layer).
     */
    public enum InviteState {
        OK, IN_PARTY, PENDING, BLOCKED, NO_RANK, FULL, BUSY
    }

    public InviteState inviteState(ServerPlayer inviter, ServerPlayer target) {
        if (inviter == target || partyOf(target.getUUID()) != null) {
            return InviteState.IN_PARTY;
        }
        Party party = partyOf(inviter.getUUID());
        if (party != null && !party.mayInvite(inviter.getUUID())) {
            return InviteState.NO_RANK;
        }
        UUID partyId = party == null ? null : party.id;
        boolean duplicate = invitesFor(target.getUUID()).values().stream().anyMatch(existing ->
                partyId != null ? partyId.equals(existing.partyId)
                        : existing.inviter.equals(inviter.getUUID()));
        if (duplicate) {
            return InviteState.PENDING;
        }
        MinecraftServer server = inviter.getServer();
        if (server != null) {
            HubSettings.InvitesFrom accepts = HubSettings.partyInvitesFrom(server, target.getUUID());
            // Someone the target ignores reads the same as their privacy setting: not accepting invites from you.
            if (accepts == HubSettings.InvitesFrom.NOBODY
                    || accepts == HubSettings.InvitesFrom.FRIENDS
                    && !MinestarHook.friendUuids(target.getUUID()).contains(inviter.getUUID())
                    || net.ledok.eden_ld.hub.IgnoreList.ignores(server, target.getUUID(), inviter.getUUID())) {
                return InviteState.BLOCKED;
            }
        }
        int taken = party != null
                ? party.members.size() + outgoingInvites(party.id).size()
                : 1 + soloOutgoingInvites(inviter.getUUID()).size();
        if (taken >= maxSize()) {
            return InviteState.FULL;
        }
        return InviteState.OK;
    }

    /**
     * Solo players may invite too — accepting a solo invite is what creates the party
     * (the inviter becomes its leader).
     */
    @Nullable
    public Component invite(ServerPlayer inviter, ServerPlayer target) {
        String targetName = target.getGameProfile().getName();
        switch (inviteState(inviter, target)) {
            case IN_PARTY -> {
                return error("target_in_party", targetName);
            }
            case NO_RANK -> {
                return error("no_permission");
            }
            case PENDING -> {
                return error("already_invited", targetName);
            }
            case BLOCKED -> {
                return error("invites_blocked", targetName);
            }
            case FULL -> {
                return error("full");
            }
            default -> {
            }
        }
        Party party = partyOf(inviter.getUUID());
        UUID partyId = party == null ? null : party.id;
        invites.computeIfAbsent(target.getUUID(), k -> new HashMap<>())
                .put(inviter.getUUID(), new Invite(inviter.getUUID(),
                        inviter.getGameProfile().getName(), target.getGameProfile().getName(),
                        partyId, System.currentTimeMillis() + INVITE_EXPIRY_MS));
        return null;
    }

    /** Removes invites to {@code target} the actor is entitled to cancel. */
    public void cancelInvite(UUID actor, UUID target) {
        Map<UUID, Invite> waiting = invites.get(target);
        if (waiting == null) {
            return;
        }
        Party party = partyOf(actor);
        waiting.values().removeIf(invite -> invite.inviter.equals(actor)
                || (party != null && party.id.equals(invite.partyId) && party.mayInvite(actor)));
    }

    /** Accepts the invite sent by {@code inviter}; creates the party if the inviter is still solo. */
    @Nullable
    public Component acceptInvite(ServerPlayer player, UUID inviter) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return null;
        }
        Invite invite = invitesFor(player.getUUID()).get(inviter);
        if (invite == null) {
            return error("no_such_invite");
        }
        if (partyOf(player.getUUID()) != null) {
            return error("already_in_party");
        }
        Party party = invite.partyId != null ? parties.get(invite.partyId) : null;
        if (party == null) {
            // Solo inviter: the party is born now, with the inviter as leader.
            ServerPlayer inviterPlayer = server.getPlayerList().getPlayer(inviter);
            if (inviterPlayer == null) {
                declineInvite(player.getUUID(), inviter);
                return error("no_such_invite");
            }
            party = createFor(inviterPlayer);
        }
        if (party.members.size() >= maxSize()) {
            return error("full");
        }
        join(server, party, player);
        return null;
    }

    public void declineInvite(UUID player, UUID inviter) {
        Map<UUID, Invite> waiting = invites.get(player);
        if (waiting != null) {
            waiting.remove(inviter);
        }
    }

    // ---------------------------------------------------------------- lifecycle

    private Party createFor(ServerPlayer leader) {
        String leaderName = leader.getGameProfile().getName();
        // No name until someone renames it: every reader sees "<leader>'s Party" in their own language.
        Party party = new Party(UUID.randomUUID(), uniqueTag(leaderName), "");
        party.members.put(leader.getUUID(), PartyRank.LEADER);
        party.memberNames.put(leader.getUUID(), leaderName);
        party.memberOrder.add(leader.getUUID());
        parties.put(party.id, party);
        memberIndex.put(leader.getUUID(), party.id);
        invites.remove(leader.getUUID());
        // The leader's other solo invites now belong to this party.
        for (Map<UUID, Invite> waiting : invites.values()) {
            waiting.computeIfPresent(leader.getUUID(), (k, invite) ->
                    invite.partyId == null
                            ? new Invite(invite.inviter, invite.inviterName, invite.targetName,
                                    party.id, invite.expiresAt)
                            : invite);
        }
        EdenPartyApi.notifyJoin(party.id, leader.getUUID());
        return party;
    }

    private void join(MinecraftServer server, Party party, ServerPlayer player) {
        notifyMembers(server, party, "member_joined", player.getGameProfile().getName());
        party.members.put(player.getUUID(), PartyRank.MEMBER);
        party.memberNames.put(player.getUUID(), player.getGameProfile().getName());
        party.memberOrder.add(player.getUUID());
        memberIndex.put(player.getUUID(), party.id);
        invites.remove(player.getUUID());
        // Members can't necessarily invite in their new party — drop their solo invites.
        for (Map<UUID, Invite> waiting : invites.values()) {
            waiting.values().removeIf(invite ->
                    invite.inviter.equals(player.getUUID()) && invite.partyId == null);
        }
        EdenPartyApi.notifyJoin(party.id, player.getUUID());
    }

    /** Direct join under the party's join policy (no invite, no request queue). */
    @Nullable
    public Component joinDirect(ServerPlayer player, UUID partyId) {
        MinecraftServer server = player.getServer();
        Party party = parties.get(partyId);
        if (server == null || party == null) {
            return error("no_such_party");
        }
        if (partyOf(player.getUUID()) != null) {
            return error("already_in_party");
        }
        if (party.members.size() >= maxSize()) {
            return error("full");
        }
        boolean allowed = switch (party.joinPolicy) {
            case INVITE_ONLY -> false;
            case OPEN -> true;
            case FRIENDS -> {
                Set<UUID> friends = MinestarHook.friendUuids(player.getUUID());
                yield party.members.keySet().stream().anyMatch(friends::contains);
            }
        };
        if (!allowed) {
            return error(party.joinPolicy == Party.JoinPolicy.FRIENDS
                    ? "not_friends" : "invite_only");
        }
        join(server, party, player);
        return null;
    }

    /** Leaving is never blocked (busy or not); the leader role moves on if needed. */
    @Nullable
    public Component leave(ServerPlayer player) {
        Party party = partyOf(player.getUUID());
        MinecraftServer server = player.getServer();
        if (party == null || server == null) {
            return error("not_in_party");
        }
        removeMember(server, party, player.getUUID(), null);
        return null;
    }

    @Nullable
    public Component kick(MinecraftServer server, Party party, UUID actor, UUID target) {
        if (!party.mayManage(actor) || actor.equals(target)) {
            return error("no_permission");
        }
        if (!party.members.containsKey(target)) {
            return error("not_a_member");
        }
        if (party.rank(target).level >= party.rank(actor).level) {
            return error("outranked");
        }
        removeMember(server, party, target, null);
        return null;
    }

    @Nullable
    public Component disband(MinecraftServer server, Party party, UUID actor) {
        if (party.rank(actor) != PartyRank.LEADER) {
            return error("leader_only");
        }
        forceDisband(server, party);
        return null;
    }

    /** Removes the party unconditionally — used by disband, shrink-below-2, and admins. */
    public void forceDisband(MinecraftServer server, Party party) {
        List<UUID> members = List.copyOf(party.members.keySet());
        parties.remove(party.id);
        for (UUID member : members) {
            memberIndex.remove(member);
            EdenPartyApi.notifyLeave(party.id, member);
            notify(server, member, "disbanded", party.title());
        }
        // Invites to the dead party die with it.
        for (Map<UUID, Invite> waiting : invites.values()) {
            waiting.values().removeIf(invite -> party.id.equals(invite.partyId));
        }
    }

    /**
     * Removes one member; disbands when fewer than 2 remain, promotes a successor
     * when the leader left. {@code offlineKickName} non-null marks a grace-period
     * auto-kick and is announced to the remaining members.
     */
    private void removeMember(MinecraftServer server, Party party, UUID member,
                              @Nullable String offlineKickName) {
        boolean wasLeader = party.rank(member) == PartyRank.LEADER;
        party.members.remove(member);
        party.memberNames.remove(member);
        party.memberOrder.remove(member);
        party.offlineSince.remove(member);
        memberIndex.remove(member);
        EdenPartyApi.notifyLeave(party.id, member);

        if (offlineKickName != null) {
            notifyMembers(server, party, "removed_offline", offlineKickName);
        }
        if (party.members.size() < 2) {
            forceDisband(server, party);
            return;
        }
        if (wasLeader) {
            promoteSuccessor(server, party);
        }
    }

    /** Oldest active member first: online vices in join order, then online members, then anyone. */
    private void promoteSuccessor(MinecraftServer server, Party party) {
        UUID successor = null;
        for (boolean requireOnline : new boolean[]{true, false}) {
            for (PartyRank rank : new PartyRank[]{PartyRank.VICE, PartyRank.MEMBER}) {
                for (UUID candidate : party.memberOrder) {
                    if (party.rank(candidate) == rank
                            && (!requireOnline || !party.offlineSince.containsKey(candidate))) {
                        successor = candidate;
                        break;
                    }
                }
                if (successor != null) {
                    break;
                }
            }
            if (successor != null) {
                break;
            }
        }
        if (successor != null) {
            party.members.put(successor, PartyRank.LEADER);
            notify(server, successor, "now_leader", party.title());
        }
    }

    // ---------------------------------------------------------------- connection lifecycle

    private void onJoin(ServerPlayer player) {
        Party party = partyOf(player.getUUID());
        if (party != null) {
            party.offlineSince.remove(player.getUUID());
            party.memberNames.put(player.getUUID(), player.getGameProfile().getName());
        }
    }

    private void onDisconnect(MinecraftServer server, ServerPlayer player) {
        // Suspensions never outlive the session: a match that lost its player must not
        // leave them unprotected forever.
        friendlyFireSuspensions.remove(player.getUUID());
        Party party = partyOf(player.getUUID());
        if (party == null) {
            return;
        }
        party.offlineSince.put(player.getUUID(), System.currentTimeMillis());
        boolean anyoneOnline = party.members.keySet().stream()
                .anyMatch(member -> !party.offlineSince.containsKey(member));
        if (!anyoneOnline) {
            // Parties are temporary: the last logout destroys the party.
            forceDisband(server, party);
            return;
        }
        if (party.rank(player.getUUID()) == PartyRank.LEADER) {
            party.members.put(player.getUUID(), PartyRank.VICE);
            promoteSuccessor(server, party);
        }
    }

    /** Once a second: auto-kick members whose offline grace ran out. */
    private void tick(MinecraftServer server) {
        long grace = Math.max(0, EdenLD.CONFIG.hub.party_offline_grace_seconds) * 1000L;
        long now = System.currentTimeMillis();
        boolean changed = false;
        for (Party party : List.copyOf(parties.values())) {
            if (!parties.containsKey(party.id)) {
                continue;
            }
            for (Map.Entry<UUID, Long> offline : List.copyOf(party.offlineSince.entrySet())) {
                if (now - offline.getValue() >= grace && parties.containsKey(party.id)) {
                    removeMember(server, party, offline.getKey(),
                            party.memberNames.getOrDefault(offline.getKey(), "?"));
                    changed = true;
                }
            }
        }
        if (changed) {
            HubServer.syncParties(server);
        }
    }

    // ---------------------------------------------------------------- ranks

    @Nullable
    public Component promote(Party party, UUID actor, UUID target) {
        if (party.rank(actor) != PartyRank.LEADER || !party.members.containsKey(target)) {
            return error("leader_only");
        }
        if (party.rank(target) != PartyRank.MEMBER) {
            return error("no_permission");
        }
        party.members.put(target, PartyRank.VICE);
        return null;
    }

    @Nullable
    public Component demote(Party party, UUID actor, UUID target) {
        if (party.rank(actor) != PartyRank.LEADER || party.rank(target) != PartyRank.VICE) {
            return error("leader_only");
        }
        party.members.put(target, PartyRank.MEMBER);
        return null;
    }

    @Nullable
    public Component transfer(Party party, UUID actor, UUID target) {
        if (party.rank(actor) != PartyRank.LEADER || !party.members.containsKey(target) || actor.equals(target)) {
            return error("leader_only");
        }
        party.members.put(actor, PartyRank.VICE);
        party.members.put(target, PartyRank.LEADER);
        return null;
    }

    // ---------------------------------------------------------------- settings

    @Nullable
    public Component rename(Party party, UUID actor, String name) {
        if (!party.mayManage(actor)) {
            return error("no_permission");
        }
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty() || trimmed.length() > MAX_NAME_LENGTH) {
            return error("bad_name");
        }
        if (!net.ledok.eden_ld.chat.ChatGuard.allowedText(trimmed)) {
            return Component.translatable("message.eden_ld.text.illegal_characters");
        }
        party.name = trimmed;
        return null;
    }

    @Nullable
    public Component setInvitePolicy(Party party, UUID actor, Party.InvitePolicy policy) {
        if (!party.mayManage(actor)) {
            return error("no_permission");
        }
        party.invitePolicy = policy;
        return null;
    }

    @Nullable
    public Component setJoinPolicy(Party party, UUID actor, Party.JoinPolicy policy) {
        if (!party.mayManage(actor)) {
            return error("no_permission");
        }
        party.joinPolicy = policy;
        return null;
    }

    // ---------------------------------------------------------------- chat history

    public void appendChat(Party party, Party.ChatLine line) {
        party.chatHistory.add(line);
        Iterator<Party.ChatLine> it = party.chatHistory.iterator();
        while (party.chatHistory.size() > CHAT_HISTORY_LIMIT && it.hasNext()) {
            it.next();
            it.remove();
        }
    }
}
