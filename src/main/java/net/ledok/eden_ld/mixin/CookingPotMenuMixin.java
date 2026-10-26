package net.ledok.eden_ld.mixin;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Farmer's Delight 3.3.6 (Refabricated): shift-clicking meals out of the cooking pot never told the output slot how
 * many were taken (vanilla's furnace menu calls {@code onQuickCraft}), so the slot reported 0 crafted and those meals
 * missed the "crafted" statistic and Eden's craft quests — only plain clicks counted. The amount taken is now passed
 * on as vanilla does. Nothing happens without Farmer's Delight ({@code @Pseudo}).
 */
@Pseudo
@Mixin(targets = "vectorwing.farmersdelight.common.block.entity.container.CookingPotMenu")
public abstract class CookingPotMenuMixin {

    /** The menu's meal output slot. */
    @Unique
    private static final int MEAL_OUTPUT = 8;

    @Unique
    private ItemStack eden_ld$meals = ItemStack.EMPTY;

    @Inject(method = "quickMoveStack", at = @At("HEAD"), require = 0)
    private void eden_ld$rememberMeals(Player player, int index, CallbackInfoReturnable<ItemStack> cir) {
        eden_ld$meals = index == MEAL_OUTPUT
                ? ((AbstractContainerMenu) (Object) this).getSlot(index).getItem().copy() : ItemStack.EMPTY;
    }

    @Inject(method = "quickMoveStack", at = @At("RETURN"), require = 0)
    private void eden_ld$countMeals(Player player, int index, CallbackInfoReturnable<ItemStack> cir) {
        ItemStack before = eden_ld$meals;
        eden_ld$meals = ItemStack.EMPTY;
        if (!before.isEmpty()) {
            Slot slot = ((AbstractContainerMenu) (Object) this).getSlot(index);
            slot.onQuickCraft(slot.getItem(), before);
        }
    }
}
