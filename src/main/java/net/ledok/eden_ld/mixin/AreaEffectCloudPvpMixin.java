package net.ledok.eden_ld.mixin;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.ledok.eden_ld.compat.CombatTeamsCompat;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.AreaEffectCloud;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.alchemy.PotionContents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Lingering clouds between players (docs/PLAN-pvp-toggle.md), as {@link ThrownPotionPvpMixin} does for splashes: a
 * cloud passes over the players its owner ignores — and over anyone none of its effects would land on (a friend in a
 * poison cloud, an enemy in a healing one) — without counting them as touched, so it doesn't shrink for them; on the
 * rest, only the effects that land do (friends: beneficial, enemies: harmful and neutral).
 */
@Mixin(AreaEffectCloud.class)
public abstract class AreaEffectCloudPvpMixin {

    @Shadow
    private PotionContents potionContents;

    @Shadow
    public abstract LivingEntity getOwner();

    @WrapOperation(method = "tick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/LivingEntity;isAffectedByPotions()Z"))
    private boolean eden_ld$passOver(LivingEntity target, Operation<Boolean> original) {
        return original.call(target) && CombatTeamsCompat.anyEffectLands(getOwner(), target, potionContents.getAllEffects());
    }

    @WrapWithCondition(method = "tick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/effect/MobEffect;applyInstantenousEffect(Lnet/minecraft/world/entity/Entity;"
                    + "Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/entity/LivingEntity;ID)V"))
    private boolean eden_ld$instantLands(MobEffect effect, Entity source, Entity owner, LivingEntity target, int amplifier,
                                         double health) {
        return CombatTeamsCompat.effectLands(owner, target, effect);
    }

    @WrapOperation(method = "tick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/LivingEntity;addEffect(Lnet/minecraft/world/effect/MobEffectInstance;"
                    + "Lnet/minecraft/world/entity/Entity;)Z"))
    private boolean eden_ld$effectLands(LivingEntity target, MobEffectInstance effect, Entity source,
                                        Operation<Boolean> original) {
        if (!CombatTeamsCompat.effectLands(getOwner(), target, effect.getEffect().value())) {
            return false;
        }
        return original.call(target, effect, source);
    }
}
