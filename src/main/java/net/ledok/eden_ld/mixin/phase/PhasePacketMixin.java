package net.ledok.eden_ld.mixin.phase;

import net.ledok.eden_ld.tutorial.phase.PhaseContext;
import net.ledok.eden_ld.tutorial.phase.Phases;
import net.ledok.eden_ld.tutorial.phase.PrivateBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundBlockEventPacket;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundLevelEventPacket;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Objects;
import java.util.UUID;

/**
 * Sounds, particles and level events made in a phase reach only that phase (the shared world's still reach everyone,
 * the NPCs included). Updates of a private block reach a player only from their own copy.
 */
@Mixin(ServerCommonPacketListenerImpl.class)
public abstract class PhasePacketMixin {

    @Inject(method = "send(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketSendListener;)V",
            at = @At("HEAD"), cancellable = true)
    private void eden_ld$filterByPhase(Packet<?> packet, @Nullable PacketSendListener listener, CallbackInfo ci) {
        if (!((Object) this instanceof ServerGamePacketListenerImpl game) || game.player == null) {
            return;
        }
        UUID acting = PhaseContext.current();
        UUID receiver = Phases.of(game.player);
        if (acting == null && receiver == null) {
            return;
        }
        if (packet instanceof ClientboundSoundPacket || packet instanceof ClientboundLevelParticlesPacket
                || packet instanceof ClientboundLevelEventPacket) {
            if (acting != null && !acting.equals(receiver)) {
                ci.cancel();
            }
            return;
        }
        BlockPos pos = packet instanceof ClientboundBlockUpdatePacket update ? update.getPos()
                : packet instanceof ClientboundBlockEntityDataPacket data ? data.getPos()
                : packet instanceof ClientboundBlockEventPacket event ? event.getPos() : null;
        if (pos != null && !Objects.equals(acting, receiver) && eden_ld$isPrivate(game.player, pos)) {
            ci.cancel();
        }
    }

    private static boolean eden_ld$isPrivate(ServerPlayer player, BlockPos pos) {
        return PrivateBlocks.isPrivate(player.level(), pos);
    }
}
