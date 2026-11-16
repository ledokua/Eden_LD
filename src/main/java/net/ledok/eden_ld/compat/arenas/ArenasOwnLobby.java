package net.ledok.eden_ld.compat.arenas;

import net.ledok.arenas_ld.api.ArenasApi;
import net.ledok.eden_ld.hub.activity.ActivitySource;
import net.minecraft.server.level.ServerPlayer;

import java.util.Optional;

/**
 * The only class using ArenasApi 4.21.0's own-lobby calls (the hub's "My lobby" button). {@link ArenasIntegration}
 * calls in here only after checking the running Arenas_LD has them.
 */
final class ArenasOwnLobby {

    private ArenasOwnLobby() {
    }

    /** An in-memory read in Arenas_LD. */
    static Optional<ActivitySource.OwnLobby> of(ServerPlayer player) {
        return ArenasApi.ownLobby(player).map(lobby ->
                new ActivitySource.OwnLobby(lobby.name(), lobby.members(), lobby.maxMembers(), lobby.inRun(),
                        lobby.activity(), lobby.owner()));
    }

    /** The lobby's own screen, from anywhere (ready, leave, invite and run modifiers all work there). */
    static boolean open(ServerPlayer player) {
        return ArenasApi.openOwnLobby(player);
    }
}
