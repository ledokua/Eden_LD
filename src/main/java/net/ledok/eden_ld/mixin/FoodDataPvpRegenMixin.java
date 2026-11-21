package net.ledok.eden_ld.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.ledok.eden_ld.combat.PvpRegen;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Natural regeneration after PvP damage ({@link PvpRegen}, {@code combat.json} {@code pvp_regen}): both of vanilla's
 * heals in FoodData#tick (saturation, and a full hunger bar) are shrunk; the exhaustion vanilla adds right after is
 * untouched, so the food cost stays. Arenas_LD wraps the same call for its run stats; the wraps chain.
 */
@Mixin(FoodData.class)
public abstract class FoodDataPvpRegenMixin {

    @WrapOperation(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/player/Player;heal(F)V"))
    private void eden_ld$pvpRegen(Player player, float amount, Operation<Void> original) {
        original.call(player, PvpRegen.naturalHeal(player, amount));
    }
}
