package net.ledok.eden_ld.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.ledok.eden_ld.compat.CombatTeamsCompat;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.spell_engine.internals.target.EntityRelation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Spell Engine summons that heal ("automatic_targeting": "FRIENDLY", Paladins' Lightwell) pick only their owner and
 * targets whose relation is exactly FRIENDLY. Eden's team matcher reports party, guild and run teammates as ALLY (no
 * friendly fire), so they were never picked: the Lightwell healed its priest alone. Here an ally counts as friendly for
 * that choice only; who may be hurt or helped stays Spell Engine's and Eden's call. Named as in production; nothing
 * without Spell Engine.
 */
@Pseudo
@Mixin(targets = "net.spell_engine.entity.SummonedEntity", remap = false)
abstract class SummonHealsAlliesMixin {

    @WrapOperation(method = "shouldHealTarget", at = @At(value = "INVOKE",
            target = "Lnet/spell_engine/internals/target/EntityRelations;getRelation(Lnet/minecraft/class_1309;Lnet/minecraft/class_1297;)Lnet/spell_engine/internals/target/EntityRelation;",
            remap = false), remap = false, require = 0)
    private EntityRelation eden_ld$alliesToo(LivingEntity summon, Entity target, Operation<EntityRelation> original) {
        EntityRelation relation = original.call(summon, target);
        // Ignored pairs come back as allies too (Spell Engine has no "ignore"): never pick them, the heal can't land.
        return relation == EntityRelation.ALLY && !CombatTeamsCompat.ignores(summon, target)
                ? EntityRelation.FRIENDLY : relation;
    }
}
