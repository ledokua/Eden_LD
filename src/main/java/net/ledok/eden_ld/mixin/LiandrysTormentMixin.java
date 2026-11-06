package net.ledok.eden_ld.mixin;

import net.ledok.eden_ld.combat.LiandrysTorment;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * More Relics' Liandry's Torment ticks with the caster's spell power ({@link LiandrysTorment}) instead of 3% of the
 * target's max health. More Relics isn't on Eden's classpath, so the override is named as in production
 * (intermediary {@code method_5572}, MobEffect#applyEffectTick). Does nothing without More Relics.
 */
@Pseudo
@Mixin(targets = "more_relics.spell.effect.LiandrysTornmentStatusEffect", remap = false)
public abstract class LiandrysTormentMixin {

    @Inject(method = {"method_5572", "applyEffectTick"}, at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void eden_ld$scaleWithCaster(LivingEntity target, int amplifier, CallbackInfoReturnable<Boolean> cir) {
        cir.setReturnValue(LiandrysTorment.tick(target, amplifier));
    }
}
