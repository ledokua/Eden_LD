package net.ledok.eden_ld.mixin;

import net.ledok.eden_ld.compat.CombatTeamsCompat;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Pets and summons follow their owner's PvP (docs/PLAN-pvp-toggle.md): they never take as a target a player, pet or
 * summon their owner may not hurt — friends, or a side the owner ignores (PvP off, a duel outsider). The damage checks
 * already refuse the hits; this keeps them from chasing and swinging at someone for nothing (an owner's order through
 * {@code OwnerHurtTargetGoal}, a wolf's grudge, a summon's own targeting, modded pets alike). Mobs without an owner and
 * targets without one are untouched.
 */
@Mixin(Mob.class)
public abstract class PetTargetPvpMixin {

    @Inject(method = "setTarget", at = @At("HEAD"), cancellable = true)
    private void eden_ld$noTargetAcrossPvp(LivingEntity target, CallbackInfo ci) {
        Mob self = (Mob) (Object) this;
        if (target == null || self.level().isClientSide()) {
            return;
        }
        CombatTeamsCompat.Relation relation = CombatTeamsCompat.resolve(self, target);
        if (relation != null && !relation.friendlyFire()) {
            ci.cancel();
        }
    }
}
