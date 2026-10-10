package net.ledok.eden_ld.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import net.ledok.eden_ld.world.Hazards;
import net.minecraft.core.Holder;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Hazards scale with health ({@link Hazards}): the damage a hazard deals is set as it arrives, armor leaves
 * it alone, and enchantments and effects still reduce it. Also remembers what the PvP rule needs: whether
 * another player set this one alight, or gave them their poison or wither.
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityHazardMixin implements Hazards.HazardState {

    @Unique
    private boolean eden_ld$pvpFire;
    @Unique
    private boolean eden_ld$pvpPoison;
    @Unique
    private boolean eden_ld$pvpWither;

    @ModifyVariable(method = "hurt", at = @At("HEAD"), argsOnly = true)
    private float eden_ld$hazardDamage(float amount, @Local(argsOnly = true) DamageSource source) {
        return Hazards.scale((LivingEntity) (Object) this, source, amount);
    }

    /** Armor doesn't reduce hazards (nor wears down from them): a 90%-armor tank can't sit in lava. */
    @Inject(method = "getDamageAfterArmorAbsorb", at = @At("HEAD"), cancellable = true)
    private void eden_ld$hazardIgnoresArmor(DamageSource source, float amount, CallbackInfoReturnable<Float> cir) {
        if (Hazards.ignoresArmor(source)) {
            cir.setReturnValue(amount);
        }
    }

    @Inject(method = "addEffect(Lnet/minecraft/world/effect/MobEffectInstance;Lnet/minecraft/world/entity/Entity;)Z",
            at = @At("RETURN"))
    private void eden_ld$effectSource(MobEffectInstance instance, @Nullable Entity source, CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValueZ()) {
            return;
        }
        Player player = Hazards.playerBehind(source);
        boolean byOtherPlayer = player != null && player != (Object) this;
        MobEffect effect = instance.getEffect().value();
        if (effect == MobEffects.POISON.value()) {
            eden_ld$pvpPoison = byOtherPlayer;
        } else if (effect == MobEffects.WITHER.value()) {
            eden_ld$pvpWither = byOtherPlayer;
        }
    }

    @Override
    public boolean eden_ld$pvpFire() {
        return eden_ld$pvpFire;
    }

    @Override
    public void eden_ld$setPvpFire(boolean pvp) {
        eden_ld$pvpFire = pvp;
    }

    @Override
    public boolean eden_ld$pvpEffect(Holder<MobEffect> effect) {
        MobEffect value = effect.value();
        return value == MobEffects.POISON.value() ? eden_ld$pvpPoison : value == MobEffects.WITHER.value() && eden_ld$pvpWither;
    }
}
