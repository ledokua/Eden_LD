package net.ledok.eden_ld.compat.opac;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import xaero.pac.common.server.api.OpenPACServerAPI;

/** OPAC's claim protection, asked the way OPAC asks it itself when something gets hurt (only loaded when OPAC is). */
final class OpacProtection {

    private OpacProtection() {
    }

    static boolean protects(Entity attacker, Entity target) {
        MinecraftServer server = attacker.getServer();
        return server != null && OpenPACServerAPI.get(server).getChunkProtection()
                .onEntityInteraction(attacker, attacker, target, null, InteractionHand.MAIN_HAND, true, false);
    }
}
