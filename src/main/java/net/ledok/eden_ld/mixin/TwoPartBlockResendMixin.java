package net.ledok.eden_ld.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.ledok.eden_ld.EdenLD;
import net.ledok.eden_ld.world.BlockResync;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A refused click or break leaves no ghost block. The client changes the block (both halves of a door, tall plant,
 * waystone or bed) when it predicts the action; when the server refuses it (claim protection, Waystones' creative-only
 * global waystones, tutorial-locked blocks…) vanilla re-sends only the clicked block and its neighbor on the clicked
 * face, or only the hit block for a break, so the other half stayed wrong on the client until a relog (1.3.26: it
 * follows at once). Since 1.3.32 a refused click (the use didn't go through) and a break the server kept also have
 * those blocks, with their block entity data, sent again after the server's answer to the prediction
 * ({@link BlockResync}): a block entity the predicted break removed comes back empty on the client otherwise. All to
 * the acting player only; a click or break that went through sends nothing extra but 1.3.26's other half.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class TwoPartBlockResendMixin {

    @Unique
    private static boolean eden_ld$logged;

    /** Whether this packet's use went through (set by the call below; false when it wasn't even tried). */
    @Unique
    private boolean eden_ld$useWentThrough;

    @Shadow
    public ServerPlayer player;

    @Inject(method = "handleUseItemOn", at = @At("HEAD"), require = 0)
    private void eden_ld$useNotYetTried(ServerboundUseItemOnPacket packet, CallbackInfo ci) {
        eden_ld$useWentThrough = false;
    }

    @WrapOperation(method = "handleUseItemOn", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerPlayerGameMode;useItemOn(Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/world/level/Level;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/InteractionHand;Lnet/minecraft/world/phys/BlockHitResult;)Lnet/minecraft/world/InteractionResult;"),
            require = 0)
    private InteractionResult eden_ld$noteUse(ServerPlayerGameMode gameMode, ServerPlayer user, Level level, ItemStack stack,
                                              InteractionHand hand, BlockHitResult hit, Operation<InteractionResult> original) {
        InteractionResult result = original.call(gameMode, user, level, stack, hand, hit);
        eden_ld$useWentThrough = result.consumesAction();
        return result;
    }

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
        if (!eden_ld$useWentThrough) {
            eden_ld$resyncLater(clicked);
            eden_ld$resyncLater(face);
        }
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
            // Still there: refused, or not broken yet; the client may have predicted it gone.
            if (!player.serverLevel().getBlockState(packet.getPos()).isAir()) {
                eden_ld$resyncLater(packet.getPos());
            }
        }
    }

    /** The block (and its other half) again, with block entity data, after the server's answer to the prediction. */
    @Unique
    private void eden_ld$resyncLater(BlockPos pos) {
        BlockResync.queue(player, pos);
        BlockPos other = eden_ld$otherHalf(player.serverLevel().getBlockState(pos), pos);
        if (other != null) {
            BlockResync.queue(player, other);
        }
    }

    @Unique
    private static void eden_ld$logOnce() {
        if (!eden_ld$logged) {
            // Optional injections (require = 0): this line in the log shows they are in place.
            eden_ld$logged = true;
            EdenLD.LOGGER.info("[Blocks] Refused clicks and breaks re-send the block (both halves, block entity data).");
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
