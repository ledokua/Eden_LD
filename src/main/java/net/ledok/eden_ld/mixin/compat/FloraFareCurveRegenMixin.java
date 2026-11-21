package net.ledok.eden_ld.mixin.compat;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.ledok.eden_ld.combat.PvpRegen;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * PvP regen ({@link PvpRegen}) for FloraFare's {@code regenMode "curve"} (the Fallen Eden server's): FloraFare turns
 * vanilla's natural heals off and heals in {@code NaturalRegeneration#curveHeal} instead, then charges exhaustion for
 * the health it actually gained. The heal is shrunk here, and while it is, the cost is computed from the heal FloraFare
 * meant (up to the missing health), so food goes at the normal rate for less healing. Named as in production; nothing
 * without FloraFare.
 */
@Pseudo
@Mixin(targets = "net.tend1tnuy.florafare.food.NaturalRegeneration", remap = false)
abstract class FloraFareCurveRegenMixin {

    @WrapOperation(method = "curveHeal", at = @At(value = "INVOKE", target = "Lnet/minecraft/class_1657;method_6025(F)V", remap = false),
            remap = false, require = 0)
    private static void eden_ld$pvpRegen(Player player, float amount, Operation<Void> original) {
        float heal = PvpRegen.naturalHeal(player, amount);
        PvpRegen.curveCost(heal < amount ? Math.min(amount, Math.max(0, player.getMaxHealth() - player.getHealth())) : -1);
        original.call(player, heal);
    }

    @ModifyArg(method = "curveHeal", at = @At(value = "INVOKE",
            target = "Lnet/tend1tnuy/florafare/food/RegenMath;exhaustionCost(FFFF)F", remap = false),
            index = 0, remap = false, require = 0)
    private static float eden_ld$fullCost(float healed) {
        return PvpRegen.takeCurveCost(healed);
    }
}
