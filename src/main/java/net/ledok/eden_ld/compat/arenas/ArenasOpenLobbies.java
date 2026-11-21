package net.ledok.eden_ld.compat.arenas;

import net.ledok.arenas_ld.api.ArenasApi;
import net.ledok.eden_ld.hub.IgnoreList;
import net.ledok.eden_ld.hub.activity.ActivitySource;
import net.ledok.eden_ld.minestar.MinestarHook;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The only class using ArenasApi 4.31.0's open lobbies (the Activities cards' badges, 1.3.98; the «Лобі» list's
 * Join and Request, 1.3.99).
 * {@link ArenasIntegration} calls in here only after checking the running Arenas_LD has it.
 */
final class ArenasOpenLobbies {

    /** The running Arenas_LD has 4.33.0's lobby creation (min levels, modifier names), checked once. */
    static final boolean MIN_LEVEL = ArenasIntegration.createApi();

    private ArenasOpenLobbies() {
    }

    /**
     * Every lobby forming now, by activity: public ones for everyone; friends-only ones (joined by request) only for
     * the owner's friends and its members (LeDok 2026-11-16), and not for friends the owner ignores (review finding
     * 66). An in-memory read in Arenas_LD.
     */
    static Map<ResourceLocation, List<ActivitySource.OpenLobby>> of(ServerPlayer viewer) {
        UUID self = viewer.getUUID();
        Set<UUID> friends = null;
        Map<ResourceLocation, List<ActivitySource.OpenLobby>> out = new LinkedHashMap<>();
        for (ArenasApi.OpenLobby lobby : ArenasApi.listOpenLobbies(viewer.server)) {
            boolean member = lobby.members().contains(self);
            boolean request = lobby.access() == ArenasApi.LobbyAccess.REQUEST;
            if (request && !member) {
                if (friends == null) {
                    friends = MinestarHook.friendUuids(self);
                }
                if (!friends.contains(lobby.owner()) || IgnoreList.ignores(viewer.server, lobby.owner(), self)) {
                    continue;
                }
            }
            out.computeIfAbsent(lobby.activity(), id -> new ArrayList<>()).add(new ActivitySource.OpenLobby(
                    lobby.lobbyId(), lobby.ownerName(), lobby.members().size(), lobby.maxMembers(), lobby.difficulty(),
                    lobby.hardcore(), request, member, lobby.ready(), MIN_LEVEL ? ArenasLobbyCreation.minLevel(lobby) : 0,
                    MIN_LEVEL ? ArenasLobbyCreation.modifierNames(viewer.server, lobby) : List.of(),
                    lobby.createdAtTick()));
        }
        return out;
    }

    /**
     * Arenas_LD's own join/request flow (busy, entry gate, one lobby at a time, full); its lobby screen opens. A
     * friends-only lobby first gets the same check as {@link #of} (review finding 66): only the owner's friends whom
     * the owner doesn't ignore may ask, since a modified client could send any lobby id it read elsewhere and the owner
     * got requests from strangers and ignored players. Refused like a lobby that's gone.
     */
    @org.jetbrains.annotations.Nullable
    static net.minecraft.network.chat.Component join(ServerPlayer player, ResourceLocation activity, UUID lobby) {
        UUID self = player.getUUID();
        for (ArenasApi.OpenLobby open : ArenasApi.listOpenLobbies(player.server)) {
            if (!open.lobbyId().equals(lobby)) {
                continue;
            }
            if (open.access() == ArenasApi.LobbyAccess.REQUEST && !open.members().contains(self)
                    && (!MinestarHook.friendUuids(self).contains(open.owner())
                    || IgnoreList.ignores(player.server, open.owner(), self))) {
                return net.minecraft.network.chat.Component.translatable("message.eden_ld.activities.unknown");
            }
            break;
        }
        return ArenasApi.joinOpenLobby(player, activity, lobby);
    }
}
