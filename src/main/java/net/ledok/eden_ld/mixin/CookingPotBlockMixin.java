package net.ledok.eden_ld.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.fabricmc.loader.api.FabricLoader;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.ledok.eden_ld.hub.daily.QuestConditions;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.ItemInteractionResult;
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
 * <p>
 * Hands-On Delight counts that serving itself (its PotServing, at the end of the same use), so with it installed Eden
 * leaves the count to it and only says where it was made: the whole use counts at the cooking pot. Without that, the
 * serving counted twice for craft quests, and Hands-On's count went to the crafting table (no menu is open).
 */
@Pseudo
@Mixin(targets = "vectorwing.farmersdelight.common.block.CookingPotBlock")
public abstract class CookingPotBlockMixin {

    @Unique
    private static final ResourceLocation COOKING_POT = ResourceLocation.fromNamespaceAndPath("farmersdelight", "cooking_pot");
    @Unique
    private static final boolean HANDS_ON = FabricLoader.getInstance().isModLoaded("handson_delight");

    @WrapMethod(method = "useItemOn", require = 0)
    private ItemInteractionResult eden_ld$atThePot(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player,
                                                   InteractionHand hand, BlockHitResult hit,
                                                   Operation<ItemInteractionResult> original) {
        if (!HANDS_ON || !(player instanceof ServerPlayer)) {
            return original.call(stack, state, level, pos, player, hand, hit);
        }
        ItemInteractionResult[] result = new ItemInteractionResult[1];
        QuestConditions.madeAt(COOKING_POT, () -> result[0] = original.call(stack, state, level, pos, player, hand, hit));
        return result[0];
    }

    @WrapOperation(method = "useItemOn", require = 0, at = @At(value = "INVOKE",
            target = "Lvectorwing/farmersdelight/common/block/entity/CookingPotBlockEntity;useHeldItemOnMeal(Lnet/minecraft/world/item/ItemStack;)Lnet/minecraft/world/item/ItemStack;"))
    private ItemStack eden_ld$countServing(CookingPotBlockEntity pot, ItemStack container, Operation<ItemStack> original,
                                           ItemStack stack, BlockState state, Level level, BlockPos pos, Player player,
                                           InteractionHand hand, BlockHitResult hit) {
        ItemStack serving = original.call(pot, container);
        if (!serving.isEmpty() && player instanceof ServerPlayer && !HANDS_ON) {
            ItemStack made = serving.copy();
            QuestConditions.madeAt(COOKING_POT, () -> made.onCraftedBy(level, player, made.getCount()));
        }
        return serving;
    }
}
