package net.ledok.eden_ld.mixin;

import net.ledok.eden_ld.compat.CombatTeamsCompat;
import net.ledok.eden_ld.compat.opac.OpacCompat;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
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
 * <p>
 * The same gate keeps a player's harmful spells off mobs that Open Parties and Claims protects from them (animals and
 * villagers in someone else's claim): OPAC already stopped the damage, but Spell Engine pushes area-spell targets back
 * on its own after the hit, and its pulls and debuffs never ask OPAC. Players are left to the PvP rules above.
 */
@Pseudo
@Mixin(targets = "net.spell_engine.internals.target.EntityRelations", remap = false)
public abstract class SpellRelationMixin {

    @Inject(method = "actionAllowed", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private static void eden_ld$ignored(@Coerce Object focusMode, @Coerce Object intent, LivingEntity attacker,
                                        Entity target, CallbackInfoReturnable<Boolean> cir) {
        if (CombatTeamsCompat.ignores(attacker, target)
                || attacker instanceof Player && !(target instanceof Player) && attacker != target
                && intent instanceof Enum<?> kind && kind.name().equals("HARMFUL")
                && OpacCompat.protects(attacker, target)) {
            cir.setReturnValue(false);
        }
    }
}
