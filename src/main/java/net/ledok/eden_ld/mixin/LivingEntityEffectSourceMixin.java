package net.ledok.eden_ld.mixin;

import net.ledok.eden_ld.combat.LiandrysTorment;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Who put an effect on this entity (Spell Engine passes the caster), for effects that scale with them ({@link LiandrysTorment}). */
@Mixin(LivingEntity.class)
public abstract class LivingEntityEffectSourceMixin {

    @Inject(method = "onEffectAdded", at = @At("TAIL"))
    private void eden_ld$effectAdded(MobEffectInstance effect, @Nullable Entity source, CallbackInfo ci) {
        if (!((LivingEntity) (Object) this).level().isClientSide) {
            LiandrysTorment.applied((LivingEntity) (Object) this, effect, source, true);
        }
    }

    @Inject(method = "onEffectUpdated", at = @At("TAIL"))
    private void eden_ld$effectUpdated(MobEffectInstance effect, boolean reapply, @Nullable Entity source, CallbackInfo ci) {
        if (!((LivingEntity) (Object) this).level().isClientSide) {
            LiandrysTorment.applied((LivingEntity) (Object) this, effect, source, false);
        }
    }
}
