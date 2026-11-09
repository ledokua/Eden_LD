package net.ledok.eden_ld.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.ledok.eden_ld.rental.RentalStash;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Rental items only go into their renter's own inventory and Trinkets slots: every placement a screen makes (clicks,
 * number keys, shift-clicks, drags) asks the slot first, so chests, ender chests, crafting grids, anvils, trades,
 * shops and modded storage screens all refuse them.
 */
@Mixin(AbstractContainerMenu.class)
abstract class RentalSlotMixin {

    @WrapOperation(method = "*", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/inventory/Slot;mayPlace(Lnet/minecraft/world/item/ItemStack;)Z"))
    private boolean eden_ld$rentalOnlyInOwnSlots(Slot slot, ItemStack stack, Operation<Boolean> original) {
        return original.call(slot, stack) && RentalStash.mayHold(slot.container, stack);
    }
}
