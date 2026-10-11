package net.ledok.eden_ld.mixin.phase;

import net.ledok.eden_ld.tutorial.phase.Phases;
import net.minecraft.server.level.ServerEntity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerPlayerConnection;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Set;

/** A player only gets the entities of their phase (plus shared decorations and NPCs); others are taken away. */
@Mixin(targets = "net.minecraft.server.level.ChunkMap$TrackedEntity")
public abstract class PhaseTrackedEntityMixin {

    @Shadow
    @Final
    Entity entity;

    @Shadow
    @Final
    Set<ServerPlayerConnection> seenBy;

    @Shadow
    @Final
    ServerEntity serverEntity;

    @Inject(method = "updatePlayer", at = @At("HEAD"), cancellable = true)
    private void eden_ld$hideOtherPhases(ServerPlayer player, CallbackInfo ci) {
        if (player != entity && !Phases.sees(player, entity)) {
            if (seenBy.remove(player.connection)) {
                serverEntity.removePairing(player);
            }
            ci.cancel();
        }
    }
}
