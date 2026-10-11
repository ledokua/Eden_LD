package net.ledok.eden_ld.mixin.phase;

import net.ledok.eden_ld.tutorial.phase.Phases;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Mobs don't target what's in another phase. */
@Mixin(Mob.class)
public abstract class PhaseMobMixin {

    @Inject(method = "setTarget", at = @At("HEAD"), cancellable = true)
    private void eden_ld$noTargetAcross(LivingEntity target, CallbackInfo ci) {
        if (target != null && !Phases.together((Mob) (Object) this, target)) {
            ci.cancel();
        }
    }
}
