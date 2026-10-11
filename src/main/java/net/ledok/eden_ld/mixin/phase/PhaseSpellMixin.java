package net.ledok.eden_ld.mixin.phase;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.ledok.eden_ld.tutorial.phase.PhaseContext;
import net.ledok.eden_ld.tutorial.phase.Phases;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.Coerce;

/** Spell Engine: a spell (its effects, sounds and particles) is cast in the caster's phase. */
@Pseudo
@Mixin(targets = "net.spell_engine.internals.SpellExecution", remap = false)
public abstract class PhaseSpellMixin {

    @WrapMethod(method = "performSpell", remap = false, require = 0)
    private static void eden_ld$castInPhase(@Coerce Object world, Player player, @Coerce Object spell,
                                            @Coerce Object target, @Coerce Object action, float progress,
                                            Operation<Void> original) {
        PhaseContext.run(Phases.of(player), () -> original.call(world, player, spell, target, action, progress));
    }
}
