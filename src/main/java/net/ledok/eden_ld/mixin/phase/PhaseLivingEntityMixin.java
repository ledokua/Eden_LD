package net.ledok.eden_ld.mixin.phase;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.ledok.eden_ld.tutorial.phase.PhaseContext;
import net.ledok.eden_ld.tutorial.phase.Phases;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** No damage across phases; a hurt entity acts in its phase; phased mobs drop no loot or XP. */
@Mixin(LivingEntity.class)
public abstract class PhaseLivingEntityMixin {

    @WrapMethod(method = "hurt")
    private boolean eden_ld$hurtInPhase(DamageSource source, float amount, Operation<Boolean> original) {
        LivingEntity self = (LivingEntity) (Object) this;
        Entity attacker = source.getEntity();
        if (attacker != null && !Phases.together(self, attacker)) {
            return false;
        }
        return PhaseContext.call(Phases.of(self), () -> original.call(source, amount));
    }

    @Inject(method = "dropAllDeathLoot", at = @At("HEAD"), cancellable = true)
    private void eden_ld$noPhasedLoot(ServerLevel level, DamageSource source, CallbackInfo ci) {
        if (!((Object) this instanceof Player) && Phases.of((Entity) (Object) this) != null) {
            ci.cancel();
        }
    }

    @Inject(method = "dropExperience", at = @At("HEAD"), cancellable = true)
    private void eden_ld$noPhasedXp(Entity attacker, CallbackInfo ci) {
        if (!((Object) this instanceof Player) && Phases.of((Entity) (Object) this) != null) {
            ci.cancel();
        }
    }
}
