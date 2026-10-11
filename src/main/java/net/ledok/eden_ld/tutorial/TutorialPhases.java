package net.ledok.eden_ld.tutorial;

import net.ledok.eden_ld.tutorial.phase.Phased;
import net.ledok.eden_ld.tutorial.phase.Phases;
import net.ledok.eden_ld.tutorial.phase.PrivateBlocks;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

/** A tutorial player's own phase: their id. Leaving it clears what they made there. */
final class TutorialPhases {

    private TutorialPhases() {
    }

    static void enter(ServerPlayer player) {
        ((Phased) player).eden_ld$setPhase(player.getUUID());
    }

    static void leave(ServerPlayer player) {
        UUID phase = Phases.of(player);
        if (phase == null) {
            return;
        }
        ((Phased) player).eden_ld$setPhase(null);
        Phases.discardAll(player.server, phase);
        PrivateBlocks.clearPhase(phase);
    }

    /** Logging out: their mobs and items go (the room is theirs only while they are there). */
    static void loggedOut(ServerPlayer player) {
        UUID phase = Phases.of(player);
        if (phase != null) {
            Phases.discardAll(player.server, phase);
            PrivateBlocks.menuClosed(player);
        }
    }
}
