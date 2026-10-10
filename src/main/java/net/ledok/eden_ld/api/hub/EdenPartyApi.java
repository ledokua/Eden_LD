package net.ledok.eden_ld.api.hub;

import net.ledok.eden_ld.hub.party.Party;
import net.ledok.eden_ld.hub.party.PartyManager;
import net.ledok.eden_ld.hub.party.PartyRank;
import net.ledok.eden_ld.hub.HubServer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Read access to Eden_LD parties for other mods (source-stable). Server side only;
 * call on the server thread. Membership listeners fire on the server thread for
 * every join/leave, including implicit ones (kick, disband, leader succession).
 */
public final class EdenPartyApi {

    private EdenPartyApi() {
    }

    /**
     * Immutable view of one party. Parties are temporary: they exist only while at
     * least one member is online, and disband below 2 members — never cache across
     * sessions. Friendly fire between members is off unless suspended (see
     * {@link #suspendFriendlyFireProtection}).
     */
    public record PartyInfo(UUID id, String name, UUID leader, Set<UUID> members) {
    }

    public interface MembershipListener {
        default void onJoin(UUID partyId, UUID player) {
        }

        default void onLeave(UUID partyId, UUID player) {
        }
    }

    private static final List<MembershipListener> LISTENERS = new CopyOnWriteArrayList<>();

    public static Optional<PartyInfo> getParty(MinecraftServer server, UUID player) {
        Party party = PartyManager.get(server).partyOf(player);
        if (party == null) {
            return Optional.empty();
        }
        return Optional.of(new PartyInfo(party.id, party.title().getString(), party.leader(),
                Set.copyOf(party.members.keySet())));
    }

    public static boolean sameParty(MinecraftServer server, UUID a, UUID b) {
        return PartyManager.get(server).sameParty(a, b);
    }

    /**
     * Whether Eden's protection applies between two players right now: partymates or
     * guildmates, neither suspended. This is the check behind all of Eden's friendly-fire rules.
     */
    public static boolean isProtected(MinecraftServer server, UUID a, UUID b) {
        return PartyManager.protects(server, a, b);
    }

    /**
     * Switches off Eden's party protection for {@code player} until
     * {@link #resumeFriendlyFireProtection} is called with the same {@code reason} — for PvP modes
     * (duels, arenas) where partymates may face each other. While suspended, Eden no longer
     * blocks damage to or from the player, lets projectiles hit, lets pets target, and stops
     * reporting partymates as teammates to Spell Engine / Better Combat (clients included —
     * the state is synced). Deciding who may hit whom is then entirely the caller's job.
     *
     * <p>Reasons stack like BusyLib's: protection returns once every reason is resumed.
     * Suspensions work whether or not the player is in a party, and are cleared when the
     * player disconnects. Server thread only.
     */
    public static void suspendFriendlyFireProtection(ServerPlayer player, ResourceLocation reason) {
        MinecraftServer server = player.getServer();
        if (server != null && PartyManager.get(server).suspendFriendlyFireProtection(player.getUUID(), reason)) {
            HubServer.syncParties(server);
        }
    }

    /** Removes one suspension reason; protection returns when none are left. Server thread only. */
    public static void resumeFriendlyFireProtection(ServerPlayer player, ResourceLocation reason) {
        MinecraftServer server = player.getServer();
        if (server != null && PartyManager.get(server).resumeFriendlyFireProtection(player.getUUID(), reason)) {
            HubServer.syncParties(server);
        }
    }

    public static boolean isFriendlyFireProtectionSuspended(MinecraftServer server, UUID player) {
        return PartyManager.get(server).friendlyFireProtectionSuspended(player);
    }

    public static boolean isLeader(MinecraftServer server, UUID player) {
        Party party = PartyManager.get(server).partyOf(player);
        return party != null && party.rank(player) == PartyRank.LEADER;
    }

    public static void addMembershipListener(MembershipListener listener) {
        LISTENERS.add(listener);
    }

    /** Internal — fired by PartyManager; not API. */
    public static void notifyJoin(UUID partyId, UUID player) {
        LISTENERS.forEach(listener -> listener.onJoin(partyId, player));
    }

    /** Internal — fired by PartyManager; not API. */
    public static void notifyLeave(UUID partyId, UUID player) {
        LISTENERS.forEach(listener -> listener.onLeave(partyId, player));
    }
}
