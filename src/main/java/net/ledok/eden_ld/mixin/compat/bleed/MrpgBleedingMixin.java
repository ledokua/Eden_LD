package net.ledok.eden_ld.mixin.compat.bleed;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.ledok.eden_ld.world.Hazards;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/**
 * mrpg_lib's bleeding (flat 1 per stack, plus 1-5% of max health on low health) deals {@code hazards.json}
 * {@code bleed."mrpg_lib:bleeding"} instead; its interval stays. Named as in production; nothing without mrpg_lib.
 */
@Pseudo
@Mixin(targets = "com.mrpg_lib.effect.BleedingEffect", remap = false)
abstract class MrpgBleedingMixin {

    @WrapOperation(method = "method_5572", at = @At(value = "INVOKE", target = "Lnet/minecraft/class_1309;method_5643(Lnet/minecraft/class_1282;F)Z", remap = false),
            remap = false, require = 0)
    private boolean eden_ld$bleed(LivingEntity target, DamageSource source, float amount, Operation<Boolean> original,
                                  @Local(argsOnly = true) int amplifier) {
        float scaled = Hazards.bleed("mrpg_lib:bleeding", target, amplifier + 1, 1.0, amount);
        return Hazards.asTick("bleed", () -> original.call(target, source, scaled));
    }
}
