package net.ledok.eden_ld.mixin;

import net.ledok.eden_ld.compat.CombatTeamsCompat;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Spell Engine: players who ignore each other (docs/PLAN-pvp-toggle.md) are out of each other's spells. Spell Engine's
 * relations have no "ignore" — each allows damage or healing — so its one action gate, which spell targeting, every
 * impact, spell projectiles and melee skills ask (SpellTarget, SpellImpacts, SpellProjectile, Melee), refuses the pair
 * here: no damage, heal, buff, slow, pull or knockback, on the client (targeting, highlights) and the server alike.
 * Friends and enemies keep Spell Engine's own answer from Eden's team matcher.
 */
@Pseudo
@Mixin(targets = "net.spell_engine.internals.target.EntityRelations", remap = false)
public abstract class SpellRelationMixin {

    @Inject(method = "actionAllowed", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private static void eden_ld$ignored(@Coerce Object focusMode, @Coerce Object intent, LivingEntity attacker,
                                        Entity target, CallbackInfoReturnable<Boolean> cir) {
        if (CombatTeamsCompat.ignores(attacker, target)) {
            cir.setReturnValue(false);
        }
    }
}
