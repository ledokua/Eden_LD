package net.ledok.eden_ld.mixin;

import net.ledok.eden_ld.rental.RentalItems;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickAction;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Rental items aren't stuffed into bundles (or other items that take items on a click). */
@Mixin(AbstractContainerMenu.class)
abstract class RentalClickOverrideMixin {

    @Inject(method = "tryItemClickBehaviourOverride", at = @At("HEAD"), cancellable = true)
    private void eden_ld$noRentalInItems(Player player, ClickAction action, Slot slot, ItemStack slotStack,
                                         ItemStack carried, CallbackInfoReturnable<Boolean> cir) {
        if (!player.level().isClientSide() && (RentalItems.isRental(slotStack) || RentalItems.isRental(carried))) {
            cir.setReturnValue(false);
        }
    }
}
