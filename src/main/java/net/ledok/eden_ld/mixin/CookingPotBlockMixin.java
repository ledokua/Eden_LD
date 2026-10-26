package net.ledok.eden_ld.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.ledok.eden_ld.hub.daily.QuestConditions;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import vectorwing.farmersdelight.common.block.entity.CookingPotBlockEntity;

/**
 * Farmer's Delight's cooking pot also serves a meal when it's right-clicked with a bowl (or the meal's other
 * container) — no menu, so the meal never counted as made. It now counts like one taken from the output slot: the
 * "crafted" statistic, so craft quests, at the cooking pot station. Nothing happens without Farmer's Delight
 * ({@code @Pseudo}).
 */
@Pseudo
@Mixin(targets = "vectorwing.farmersdelight.common.block.CookingPotBlock")
public abstract class CookingPotBlockMixin {

    @Unique
    private static final ResourceLocation COOKING_POT = ResourceLocation.fromNamespaceAndPath("farmersdelight", "cooking_pot");

    @WrapOperation(method = "useItemOn", require = 0, at = @At(value = "INVOKE",
            target = "Lvectorwing/farmersdelight/common/block/entity/CookingPotBlockEntity;useHeldItemOnMeal(Lnet/minecraft/world/item/ItemStack;)Lnet/minecraft/world/item/ItemStack;"))
    private ItemStack eden_ld$countServing(CookingPotBlockEntity pot, ItemStack container, Operation<ItemStack> original,
                                           ItemStack stack, BlockState state, Level level, BlockPos pos, Player player,
                                           InteractionHand hand, BlockHitResult hit) {
        ItemStack serving = original.call(pot, container);
        if (!serving.isEmpty() && player instanceof ServerPlayer) {
            ItemStack made = serving.copy();
            QuestConditions.madeAt(COOKING_POT, () -> made.onCraftedBy(level, player, made.getCount()));
        }
        return serving;
    }
}
