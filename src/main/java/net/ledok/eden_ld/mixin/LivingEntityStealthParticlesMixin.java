package net.ledok.eden_ld.mixin;

import net.ledok.eden_ld.EdenLD;
import net.ledok.eden_ld.combat.Stealth;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * A player in stealth ({@link Stealth}) sends no effect particles: invisible players still trailed their effects'
 * swirls. Vanilla runs this whenever the effects change, so the particles come back when the stealth ends.
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityStealthParticlesMixin {

    @Shadow
    @Final
    private static EntityDataAccessor<List<ParticleOptions>> DATA_EFFECT_PARTICLES;

    @Inject(method = "updateInvisibilityStatus", at = @At("TAIL"))
    private void eden_ld$noParticlesInStealth(CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (EdenLD.CONFIG.combat.stealth_hides_effect_particles && Stealth.isHidden(self)) {
            self.getEntityData().set(DATA_EFFECT_PARTICLES, List.of());
        }
    }
}
