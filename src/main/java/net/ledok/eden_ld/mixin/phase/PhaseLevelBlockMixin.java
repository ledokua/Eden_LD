package net.ledok.eden_ld.mixin.phase;

import net.ledok.eden_ld.tutorial.phase.PrivateBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Inside a private block's scope: its block entity is the player's copy, its state theirs, and a same-block state
 * change stays theirs. Outside any scope (nearly always) these return at once.
 */
@Mixin(Level.class)
public abstract class PhaseLevelBlockMixin {

    @Inject(method = "getBlockEntity", at = @At("RETURN"), cancellable = true)
    private void eden_ld$privateBlockEntity(BlockPos pos, CallbackInfoReturnable<BlockEntity> cir) {
        if (PrivateBlocks.scopeOpen() && cir.getReturnValue() != null) {
            BlockEntity copy = PrivateBlocks.scopedBlockEntity((Level) (Object) this, pos);
            if (copy != null) {
                cir.setReturnValue(copy);
            }
        }
    }

    @Inject(method = "getBlockState", at = @At("HEAD"), cancellable = true)
    private void eden_ld$privateBlockState(BlockPos pos, CallbackInfoReturnable<BlockState> cir) {
        if (PrivateBlocks.scopeOpen()) {
            BlockState state = PrivateBlocks.scopedState((Level) (Object) this, pos);
            if (state != null) {
                cir.setReturnValue(state);
            }
        }
    }

    @Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z",
            at = @At("HEAD"), cancellable = true)
    private void eden_ld$privateSetBlock(BlockPos pos, BlockState state, int flags, int recursionLeft,
                                         CallbackInfoReturnable<Boolean> cir) {
        if (PrivateBlocks.scopeOpen() && PrivateBlocks.scopedSetBlock((Level) (Object) this, pos, state)) {
            cir.setReturnValue(true);
        }
    }
}
