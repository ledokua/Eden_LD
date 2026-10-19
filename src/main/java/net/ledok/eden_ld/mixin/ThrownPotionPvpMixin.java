package net.ledok.eden_ld.mixin;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.ledok.eden_ld.compat.CombatTeamsCompat;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.ThrownPotion;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Splash potions between players (docs/PLAN-pvp-toggle.md): a thrower's splash skips players they ignore entirely,
 * gives friends only its beneficial effects and enemies only its harmful and neutral ones — so a PvP-off guildmate
 * can't heal a fighter risk-free, and nobody poisons a PvP-off player. Effects reach the thrower themselves, mobs and
 * ownerless potions as vanilla has it ({@link CombatTeamsCompat#effectLands}).
 */
@Mixin(ThrownPotion.class)
public abstract class ThrownPotionPvpMixin {

    @WrapWithCondition(method = "applySplash", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/effect/MobEffect;applyInstantenousEffect(Lnet/minecraft/world/entity/Entity;"
                    + "Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/entity/LivingEntity;ID)V"))
    private boolean eden_ld$instantLands(MobEffect effect, Entity source, Entity owner, LivingEntity target, int amplifier,
                                         double health) {
        return CombatTeamsCompat.effectLands(owner, target, effect);
    }

    @WrapOperation(method = "applySplash", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/LivingEntity;addEffect(Lnet/minecraft/world/effect/MobEffectInstance;"
                    + "Lnet/minecraft/world/entity/Entity;)Z"))
    private boolean eden_ld$effectLands(LivingEntity target, MobEffectInstance effect, Entity source,
                                        Operation<Boolean> original) {
        if (!CombatTeamsCompat.effectLands(((ThrownPotion) (Object) this).getOwner(), target, effect.getEffect().value())) {
            return false;
        }
        return original.call(target, effect, source);
    }
}
