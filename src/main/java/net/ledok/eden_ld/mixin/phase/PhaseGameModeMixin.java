package net.ledok.eden_ld.mixin.phase;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.ledok.eden_ld.tutorial.phase.PhaseContext;
import net.ledok.eden_ld.tutorial.phase.Phases;
import net.ledok.eden_ld.tutorial.phase.PrivateBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Breaking a block, using an item and using a block happen in the player's phase; using a private block opens its
 * scope (their own copy).
 */
@Mixin(ServerPlayerGameMode.class)
public abstract class PhaseGameModeMixin {

    @Shadow
    @Final
    protected ServerPlayer player;

    @WrapMethod(method = "destroyBlock")
    private boolean eden_ld$destroyInPhase(BlockPos pos, Operation<Boolean> original) {
        boolean destroyed = PhaseContext.call(Phases.of(player), () -> original.call(pos));
        if (destroyed) {
            PrivateBlocks.cleared(player.level(), pos);
        }
        return destroyed;
    }

    @WrapMethod(method = "useItem")
    private InteractionResult eden_ld$useItemInPhase(ServerPlayer user, Level level, ItemStack stack, InteractionHand hand,
                                                     Operation<InteractionResult> original) {
        return PhaseContext.call(Phases.of(user), () -> original.call(user, level, stack, hand));
    }

    @WrapMethod(method = "useItemOn")
    private InteractionResult eden_ld$useItemOnInPhase(ServerPlayer user, Level level, ItemStack stack, InteractionHand hand,
                                                       BlockHitResult hit, Operation<InteractionResult> original) {
        boolean scoped = PrivateBlocks.pushUse(user, level, hit.getBlockPos());
        try {
            return PhaseContext.call(Phases.of(user), () -> original.call(user, level, stack, hand, hit));
        } finally {
            if (scoped) {
                PrivateBlocks.popScope();
            }
        }
    }
}
