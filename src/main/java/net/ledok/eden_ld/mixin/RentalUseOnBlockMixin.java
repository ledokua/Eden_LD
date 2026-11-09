package net.ledok.eden_ld.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.ledok.eden_ld.rental.RentalItems;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * A rental item used on a block isn't taken by it (display blocks, pots, shelves, lecterns, jukeboxes, campfires): the
 * block acts as for an empty hand, so chests and doors still open.
 */
@Mixin(ServerPlayerGameMode.class)
abstract class RentalUseOnBlockMixin {

    @WrapOperation(method = "useItemOn", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/state/BlockState;useItemOn(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/InteractionHand;Lnet/minecraft/world/phys/BlockHitResult;)Lnet/minecraft/world/ItemInteractionResult;"))
    private ItemInteractionResult eden_ld$rentalNotIntoBlocks(BlockState state, ItemStack stack, Level level,
                                                              Player player, InteractionHand hand, BlockHitResult hit,
                                                              Operation<ItemInteractionResult> original) {
        if (RentalItems.isRental(stack)) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        return original.call(state, stack, level, player, hand, hit);
    }
}
