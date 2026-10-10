package net.ledok.eden_ld.mixin;

import net.ledok.eden_ld.hub.daily.DailyProgressHooks;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Counts eaten food for daily quests (only real eating — not placing berries). */
@Mixin(Player.class)
public abstract class PlayerEatMixin {

    @Inject(method = "eat", at = @At("HEAD"))
    private void eden_ld$countEat(Level level, ItemStack food, FoodProperties properties,
                                  CallbackInfoReturnable<ItemStack> cir) {
        if ((Object) this instanceof ServerPlayer player && !food.isEmpty()) {
            DailyProgressHooks.onAte(player, food);
        }
    }
}
