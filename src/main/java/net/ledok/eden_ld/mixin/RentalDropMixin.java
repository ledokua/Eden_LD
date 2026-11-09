package net.ledok.eden_ld.mixin;

import net.ledok.eden_ld.rental.RentalStash;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Rental items are never dropped (Q, thrown out of a screen, a kit's overflow): back to the renter, or gone. */
@Mixin(ServerPlayer.class)
abstract class RentalDropMixin {

    @Inject(method = "drop(Lnet/minecraft/world/item/ItemStack;ZZ)Lnet/minecraft/world/entity/item/ItemEntity;",
            at = @At("HEAD"), cancellable = true)
    private void eden_ld$keepRental(ItemStack stack, boolean throwRandomly, boolean retainOwnership,
                                    CallbackInfoReturnable<ItemEntity> cir) {
        if (RentalStash.onDrop((ServerPlayer) (Object) this, stack)) {
            cir.setReturnValue(null);
        }
    }
}
