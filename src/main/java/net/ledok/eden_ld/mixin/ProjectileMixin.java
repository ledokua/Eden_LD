package net.ledok.eden_ld.mixin;

import net.ledok.eden_ld.compat.CombatTeamsCompat;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Projectiles fly through friends and players who ignore their shooter (docs/PLAN-pvp-toggle.md: partymates,
 * guildmates, run teams, anyone with PvP off) instead of hitting them. Cancelling the damage
 * alone isn't enough: an arrow whose hit deals no damage bounces back, and before the
 * damage call the arrow already records the victim as its owner's last hurt mob — which
 * sends the owner's pets after the partymate. Not colliding at all avoids both.
 */
@Mixin(Projectile.class)
public abstract class ProjectileMixin {

    @Inject(method = "canHitEntity", at = @At("HEAD"), cancellable = true)
    private void eden_ld$passThroughPartymates(Entity target, CallbackInfoReturnable<Boolean> cir) {
        Entity owner = ((Projectile) (Object) this).getOwner();
        if (owner == null || target == null) {
            return;
        }
        CombatTeamsCompat.Relation relation = CombatTeamsCompat.resolve(owner, target);
        if (relation != null && !relation.friendlyFire()) {
            cir.setReturnValue(false);
        }
    }
}
