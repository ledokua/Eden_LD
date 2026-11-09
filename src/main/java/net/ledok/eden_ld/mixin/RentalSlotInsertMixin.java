package net.ledok.eden_ld.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.ledok.eden_ld.rental.RentalStash;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** As {@link RentalSlotMixin}, for the slot's own insert (a plain click puts the cursor's stack in through it). */
@Mixin(Slot.class)
abstract class RentalSlotInsertMixin {

    @WrapOperation(method = "safeInsert(Lnet/minecraft/world/item/ItemStack;I)Lnet/minecraft/world/item/ItemStack;",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/inventory/Slot;mayPlace(Lnet/minecraft/world/item/ItemStack;)Z"))
    private boolean eden_ld$rentalOnlyInOwnSlots(Slot slot, ItemStack stack, Operation<Boolean> original) {
        return original.call(slot, stack) && RentalStash.mayHold(slot.container, stack);
    }
}
