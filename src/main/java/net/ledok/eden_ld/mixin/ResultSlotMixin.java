package net.ledok.eden_ld.mixin;

import net.ledok.eden_ld.hub.daily.DailyProgressHooks;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ResultSlot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Counts crafted items for daily quests. {@code removeCount} holds the whole batch on
 * shift-click crafting, so one call per take is exact; vanilla resets it right after.
 */
@Mixin(ResultSlot.class)
public abstract class ResultSlotMixin {

    @Shadow
    @Final
    private Player player;

    @Shadow
    private int removeCount;

    @Inject(method = "checkTakeAchievements", at = @At("HEAD"))
    private void eden_ld$countCraft(ItemStack stack, CallbackInfo ci) {
        if (removeCount > 0 && player instanceof ServerPlayer serverPlayer) {
            DailyProgressHooks.onCrafted(serverPlayer, stack, removeCount);
        }
    }
}
