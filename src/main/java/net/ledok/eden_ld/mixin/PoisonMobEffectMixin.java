package net.ledok.eden_ld.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.ledok.eden_ld.world.Hazards;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * A poison tick scales with health ({@link Hazards#poison}): its damage type ({@code minecraft:magic}) is
 * shared with harming potions, so it's set here, at the effect, rather than by damage type. It still never
 * takes the last point of health.
 */
@Mixin(targets = "net.minecraft.world.effect.PoisonMobEffect")
public abstract class PoisonMobEffectMixin {

    @WrapOperation(method = "applyEffectTick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/LivingEntity;hurt(Lnet/minecraft/world/damagesource/DamageSource;F)Z"))
    private boolean eden_ld$scaledPoison(LivingEntity entity, DamageSource source, float amount, Operation<Boolean> original) {
        return original.call(entity, source, Hazards.poison(entity, amount));
    }
}
