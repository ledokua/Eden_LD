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
 * Spell Engine's bleed (every 25 ticks: 1 per stack times 0.5 standing to 2 running) deals {@code hazards.json}
 * {@code bleed."spell_engine:bleed"} of max health per stack instead, keeping the movement multiplier. Named as in
 * production (intermediary): Spell Engine isn't on Eden's classpath. Does nothing without it.
 */
@Pseudo
@Mixin(targets = "net.spell_engine.api.effect.BleedStatusEffect", remap = false)
abstract class SpellEngineBleedMixin {

    @WrapOperation(method = "method_5572", at = @At(value = "INVOKE", target = "Lnet/minecraft/class_1309;method_5643(Lnet/minecraft/class_1282;F)Z", remap = false),
            remap = false, require = 0)
    private boolean eden_ld$bleed(LivingEntity target, DamageSource source, float amount, Operation<Boolean> original,
                                  @Local(argsOnly = true) int amplifier) {
        // Spell Engine's damage is 1.0 x stacks x the movement multiplier: the multiplier is what's left.
        int stacks = amplifier + 1;
        float scaled = Hazards.bleed("spell_engine:bleed", target, stacks, amount / stacks, amount);
        return Hazards.asTick("bleed", () -> original.call(target, source, scaled));
    }
}
