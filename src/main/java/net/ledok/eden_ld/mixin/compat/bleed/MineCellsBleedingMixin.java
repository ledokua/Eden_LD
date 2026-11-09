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
 * Mine Cells' bleeding: its tick (0.5, faster with stacks) and its burst at 6 stacks (12) deal {@code hazards.json}
 * {@code bleed."minecells:bleeding"} and {@code "minecells:bleeding_burst"} instead. Named as in production; nothing
 * without Mine Cells.
 */
@Pseudo
@Mixin(targets = "com.github.mim1q.minecells.effect.BleedingStatusEffect", remap = false)
abstract class MineCellsBleedingMixin {

    @WrapOperation(method = "method_5572", at = @At(value = "INVOKE", target = "Lnet/minecraft/class_1309;method_5643(Lnet/minecraft/class_1282;F)Z", remap = false),
            remap = false, require = 0)
    private boolean eden_ld$bleed(LivingEntity target, DamageSource source, float amount, Operation<Boolean> original,
                                  @Local(argsOnly = true) int amplifier) {
        float scaled = Hazards.bleed("minecells:bleeding", target, amplifier + 1, 1.0, amount);
        return Hazards.asTick("bleed", () -> original.call(target, source, scaled));
    }

    @WrapOperation(method = "apply", at = @At(value = "INVOKE", target = "Lnet/minecraft/class_1309;method_5643(Lnet/minecraft/class_1282;F)Z", remap = false),
            remap = false, require = 0)
    private static boolean eden_ld$burst(LivingEntity target, DamageSource source, float amount,
                                         Operation<Boolean> original) {
        float scaled = Hazards.bleed("minecells:bleeding_burst", target, 6, 1.0, amount);
        return Hazards.asTick("bleed", () -> original.call(target, source, scaled));
    }
}
