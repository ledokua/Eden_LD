package net.ledok.eden_ld.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.ledok.eden_ld.hub.daily.Uncrafting;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.RecipeCraftingHolder;
import net.minecraft.world.inventory.ResultSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * A crafting-grid craft that undoes another one (dried kelp from its block…) is marked while vanilla awards its crafted
 * stat, so "craft" quests skip it ({@link Uncrafting}, review finding 54). Both taking and shift-clicking the result
 * award through here.
 */
@Mixin(ResultSlot.class)
abstract class ResultSlotUncraftMixin {

    @Shadow
    @Final
    private Player player;

    @WrapMethod(method = "checkTakeAchievements")
    private void eden_ld$markUncraft(ItemStack stack, Operation<Void> original) {
        Slot slot = (Slot) (Object) this;
        Uncrafting.award(player.level(), slot.container instanceof RecipeCraftingHolder holder ? holder.getRecipeUsed() : null,
                () -> original.call(stack));
    }
}
