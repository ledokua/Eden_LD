package net.ledok.eden_ld.mixin.compat.bleed;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.ledok.eden_ld.world.Hazards;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Souls Weaponry's blood loss (its bleed meter filled: base 6 + 10% of max health in its config) deals
 * {@code hazards.json} {@code bleed."soulsweaponry:bleed_burst"} instead, before its own bleed resistance. Named as
 * in production; nothing without Souls Weaponry.
 */
@Pseudo
@Mixin(targets = "net.soulsweaponry.api.entitystats.EntityBleed", remap = false)
abstract class SoulsWeaponryBleedMixin {

    @WrapOperation(method = "triggerBloodLoss", at = @At(value = "INVOKE",
            target = "Lnet/soulsweaponry/api/entitystats/EntityBleed;getBleedDamage(Lnet/minecraft/class_1309;F)F",
            remap = false), remap = false, require = 0)
    private static float eden_ld$burst(LivingEntity target, float amount, Operation<Float> original) {
        return original.call(target, Hazards.bleed("soulsweaponry:bleed_burst", target, 1, 1.0, amount));
    }

    @WrapOperation(method = "triggerBloodLoss", at = @At(value = "INVOKE", target = "Lnet/minecraft/class_1309;method_5643(Lnet/minecraft/class_1282;F)Z", remap = false),
            remap = false, require = 0)
    private static boolean eden_ld$markBleed(LivingEntity target, DamageSource source, float amount,
                                             Operation<Boolean> original) {
        return Hazards.asTick("bleed", () -> original.call(target, source, amount));
    }
}
