package net.ledok.eden_ld.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.ledok.eden_ld.EdenLD;
import net.ledok.eden_ld.world.HopperBackoff;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

import java.util.function.BooleanSupplier;

/**
 * Hoppers back off from big storages of other mods that can't take or give anything ({@code hoppers.json}
 * {@code back_off_on_large_storages}); see {@link HopperBackoff}. Wraps the push and the pull in vanilla's
 * {@code tryMoveItems}, so Fabric's transfer hooks inside them still decide how items move.
 */
@Mixin(HopperBlockEntity.class)
public abstract class HopperBackoffMixin implements HopperBackoff.Holder {

    @Unique
    private final HopperBackoff.State eden_ld$push = new HopperBackoff.State();
    @Unique
    private final HopperBackoff.State eden_ld$pull = new HopperBackoff.State();

    @Override
    public HopperBackoff.State eden_ld$pushBackoff() {
        return eden_ld$push;
    }

    @Override
    public HopperBackoff.State eden_ld$pullBackoff() {
        return eden_ld$pull;
    }

    @WrapOperation(method = "tryMoveItems", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/entity/HopperBlockEntity;ejectItems(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/entity/HopperBlockEntity;)Z"))
    private static boolean eden_ld$backOffPush(Level level, BlockPos pos, HopperBlockEntity hopper, Operation<Boolean> original,
                                               @Local(argsOnly = true) BlockState state) {
        if (!EdenLD.CONFIG.hoppers.back_off_on_large_storages) {
            return original.call(level, pos, hopper);
        }
        Direction facing = state.getValue(HopperBlock.FACING);
        BlockPos target = pos.relative(facing);
        HopperBackoff.State backoff = ((HopperBackoff.Holder) hopper).eden_ld$pushBackoff();
        if (HopperBackoff.waiting(backoff, level, target, hopper)) {
            return false;
        }
        boolean moved = original.call(level, pos, hopper);
        HopperBackoff.record(backoff, moved, level, target, facing.getOpposite(), hopper);
        return moved;
    }

    @WrapOperation(method = "tryMoveItems", at = @At(value = "INVOKE", target = "Ljava/util/function/BooleanSupplier;getAsBoolean()Z"))
    private static boolean eden_ld$backOffPull(BooleanSupplier pull, Operation<Boolean> original,
                                               @Local(argsOnly = true) Level level, @Local(argsOnly = true) BlockPos pos,
                                               @Local(argsOnly = true) HopperBlockEntity hopper) {
        if (!EdenLD.CONFIG.hoppers.back_off_on_large_storages) {
            return original.call(pull);
        }
        BlockPos source = pos.above();
        HopperBackoff.State backoff = ((HopperBackoff.Holder) hopper).eden_ld$pullBackoff();
        if (HopperBackoff.waiting(backoff, level, source, hopper)) {
            return false;
        }
        boolean moved = original.call(pull);
        HopperBackoff.record(backoff, moved, level, source, Direction.DOWN, hopper);
        return moved;
    }
}
