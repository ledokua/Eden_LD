package net.ledok.eden_ld.mixin;

import net.ledok.eden_ld.EdenLD;
import net.ledok.eden_ld.combat.CombatTag;
import net.ledok.eden_ld.combat.DamageReduction;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A player's damage reduction ({@link DamageReduction}) after armor, effects and enchantments; and less healing for a
 * player in PvP combat ({@code combat.json} {@code pvp_healing_received}): spells, potions, regeneration and lifesteal all
 * heal through here.
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityDamageReductionMixin {

    @Inject(method = "getDamageAfterMagicAbsorb", at = @At("RETURN"), cancellable = true)
    private void eden_ld$damageReduction(DamageSource source, float amount, CallbackInfoReturnable<Float> cir) {
        if ((Object) this instanceof Player player && !player.level().isClientSide()) {
            cir.setReturnValue(DamageReduction.apply(player, source, cir.getReturnValueF()));
        }
    }

    @ModifyVariable(method = "heal", at = @At("HEAD"), argsOnly = true)
    private float eden_ld$pvpHealing(float amount) {
        if ((Object) this instanceof Player player && !player.level().isClientSide() && amount > 0
                && CombatTag.millisLeft(player.getUUID()) > 0) {
            return (float) (amount * EdenLD.CONFIG.combat.pvp_healing_received);
        }
        return amount;
    }
}
