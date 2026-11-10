package net.ledok.eden_ld.mixin;

import net.ledok.eden_ld.combat.MeleeSpellHaste;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Spell Engine's physical_melee and physical_melee_dual schools: their HASTE trait (attack speed multipliers − 1, the
 * two lambdas in {@code ExternalSpellSchools.init}) capped by {@link MeleeSpellHaste}. Read by the server (cooldowns,
 * casts) and the client (cooldown display), each with its own config. Nothing without Spell Engine; a Spell Engine
 * update that renumbers the lambdas just leaves haste uncapped.
 */
@Pseudo
@Mixin(targets = "net.spell_engine.api.spell.ExternalSpellSchools", remap = false)
abstract class MeleeSpellHasteMixin {

    @Inject(method = {"lambda$init$1", "lambda$init$4"}, at = @At("RETURN"), cancellable = true, remap = false,
            require = 0)
    private static void eden_ld$capHaste(CallbackInfoReturnable<Double> cir) {
        Double haste = cir.getReturnValue();
        if (haste != null) {
            cir.setReturnValue(MeleeSpellHaste.cap(haste));
        }
    }
}
