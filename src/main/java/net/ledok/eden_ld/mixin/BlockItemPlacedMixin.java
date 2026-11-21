package net.ledok.eden_ld.mixin;

import net.ledok.eden_ld.hub.daily.DailyProgressHooks;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Review finding 92: where a player placed a block, so mining it again doesn't count for break_blocks quests (place a
 * Silk Touch ore, mine it, repeat).
 */
@Mixin(BlockItem.class)
public abstract class BlockItemPlacedMixin {

    @Inject(method = "placeBlock", at = @At("RETURN"))
    private void eden_ld$rememberPlaced(BlockPlaceContext context, BlockState state, CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ() && context.getPlayer() instanceof ServerPlayer player) {
            DailyProgressHooks.placed(player, context.getClickedPos(), state);
        }
    }
}
