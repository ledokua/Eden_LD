package net.ledok.eden_ld.mixin;

import net.ledok.eden_ld.EdenLD;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A refused click or break on a two-part block (door, tall plant, waystone, bed) leaves no ghost half. The client
 * changes both halves when it predicts the action; when the server refuses it (claim protection, Waystones' creative-only
 * global waystones, tutorial-locked blocks…) vanilla re-sends only the clicked block and its neighbor on the clicked
 * face, or only the hit block for a break, so the other half stayed wrong on the client until a relog. Here the other
 * half follows, to the acting player only. When the action went through it's the same state the server sends anyway.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class TwoPartBlockResendMixin {

    @Unique
    private static boolean eden_ld$logged;

    @Shadow
    public ServerPlayer player;

    /** Right after vanilla's two re-sends (the clicked block, then its neighbor on the clicked face). */
    @Inject(method = "handleUseItemOn",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/server/network/ServerGamePacketListenerImpl;send(Lnet/minecraft/network/protocol/Packet;)V",
                    ordinal = 1, shift = At.Shift.AFTER),
            require = 0)
    private void eden_ld$resendOtherHalfAfterUse(ServerboundUseItemOnPacket packet, CallbackInfo ci) {
        eden_ld$logOnce();
        BlockPos clicked = packet.getHitResult().getBlockPos();
        BlockPos face = clicked.relative(packet.getHitResult().getDirection());
        eden_ld$resendOtherHalf(clicked);
        eden_ld$resendOtherHalf(face);
        // A refused placement of a two-tall block: the client also put the upper half above where it placed the item
        // (the clicked block when replaceable, else the face neighbor). The server has air there, so look at the item.
        ItemStack held = player.getItemInHand(packet.getHand());
        if (held.getItem() instanceof BlockItem item
                && item.getBlock().defaultBlockState().hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)) {
            eden_ld$send(clicked.above());
            eden_ld$send(face.above());
        }
    }

    /** Starting or finishing a break: whatever was refused, the hit block is still there when it has another half. */
    @Inject(method = "handlePlayerAction",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerPlayerGameMode;handleBlockBreakAction(Lnet/minecraft/core/BlockPos;Lnet/minecraft/network/protocol/game/ServerboundPlayerActionPacket$Action;Lnet/minecraft/core/Direction;II)V",
                    shift = At.Shift.AFTER),
            require = 0)
    private void eden_ld$resendOtherHalfAfterBreak(ServerboundPlayerActionPacket packet, CallbackInfo ci) {
        eden_ld$logOnce();
        if (packet.getAction() != ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK) {
            eden_ld$resendOtherHalf(packet.getPos());
        }
    }

    @Unique
    private static void eden_ld$logOnce() {
        if (!eden_ld$logged) {
            // Optional injections (require = 0): this line in the log shows they are in place.
            eden_ld$logged = true;
            EdenLD.LOGGER.info("[Blocks] Refused clicks and breaks re-send both halves of two-part blocks.");
        }
    }

    @Unique
    private void eden_ld$resendOtherHalf(BlockPos pos) {
        BlockPos other = eden_ld$otherHalf(player.serverLevel().getBlockState(pos), pos);
        if (other != null) {
            eden_ld$send(other);
        }
    }

    @Unique
    private void eden_ld$send(BlockPos pos) {
        ServerLevel level = player.serverLevel();
        if (level.isLoaded(pos)) {
            player.connection.send(new ClientboundBlockUpdatePacket(level, pos));
        }
    }

    @Unique
    @Nullable
    private static BlockPos eden_ld$otherHalf(BlockState state, BlockPos pos) {
        if (state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)) {
            return state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.LOWER ? pos.above() : pos.below();
        }
        if (state.hasProperty(BlockStateProperties.BED_PART) && state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
            Direction facing = state.getValue(BlockStateProperties.HORIZONTAL_FACING);
            return pos.relative(state.getValue(BlockStateProperties.BED_PART) == BedPart.FOOT ? facing : facing.getOpposite());
        }
        return null;
    }
}
