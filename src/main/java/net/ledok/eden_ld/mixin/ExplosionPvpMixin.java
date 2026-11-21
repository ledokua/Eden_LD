package net.ledok.eden_ld.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.ledok.eden_ld.combat.PvpIgnore;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Explosion;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Explosions leave alone the players (and pets) their cause ignores (docs/PLAN-pvp-toggle.md: no knockback for ignored
 * pairs): a wind charge, primed TNT, an end crystal or a bed or anchor someone set off skips them entirely, as if they
 * ignored explosions. Refusing the damage alone (review finding 57) still let the push through, which can throw a
 * PvP-off player into lava or out of a duel. Friends are still pushed (a partymate's wind charge launches them); their
 * damage is refused by the damage gate as before. The cause is the blast's damage attacker (the
 * TNT's igniter, the crystal's breaker, the charge's thrower), or the player using the bed or anchor
 * ({@link PvpIgnore#attacker}).
 */
@Mixin(Explosion.class)
public abstract class ExplosionPvpMixin {

    @Shadow
    @Final
    private DamageSource damageSource;

    @WrapOperation(method = "explode", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Entity;ignoreExplosion(Lnet/minecraft/world/level/Explosion;)Z"))
    private boolean eden_ld$ignoredSides(Entity entity, Explosion explosion, Operation<Boolean> original) {
        return original.call(entity, explosion) || PvpIgnore.ignores(PvpIgnore.attacker(damageSource), entity);
    }
}
