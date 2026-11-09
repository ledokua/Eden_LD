package net.ledok.eden_ld.mixin;

import net.ledok.eden_ld.rental.RentalStash;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Renters pick nothing up from the ground. */
@Mixin(ItemEntity.class)
abstract class RentalPickupMixin {

    @Shadow
    public abstract ItemStack getItem();

    @Inject(method = "playerTouch", at = @At("HEAD"), cancellable = true)
    private void eden_ld$noPickupWhileRenting(Player player, CallbackInfo ci) {
        if (!player.level().isClientSide() && !RentalStash.mayPickUp(player, getItem())) {
            ci.cancel();
        }
    }
}
