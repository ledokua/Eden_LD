package net.ledok.eden_ld.mixin;

import net.ledok.eden_ld.EdenLD;
import net.ledok.eden_ld.config.EdenConfig;
import net.minecraft.core.Holder;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Diminishing returns on attack speed ({@code combat.json} {@code attack_speed_*}): above the soft cap only a share of
 * the rest counts, and never more than the hard cap (a stacked relic trance, Haste and a dagger reached 8.45). Runs only
 * when the value is recalculated (modifiers changed), as vanilla caches it.
 */
@Mixin(AttributeInstance.class)
public abstract class AttackSpeedCurveMixin {

    @Shadow
    @Final
    private Holder<Attribute> attribute;

    @Inject(method = "calculateValue", at = @At("RETURN"), cancellable = true)
    private void eden_ld$attackSpeedCurve(CallbackInfoReturnable<Double> cir) {
        if (!attribute.equals(Attributes.ATTACK_SPEED)) {
            return;
        }
        EdenConfig.Combat config = EdenLD.CONFIG == null ? null : EdenLD.CONFIG.combat;
        if (config == null || config.attack_speed_soft_cap <= 0) {
            return;
        }
        double value = cir.getReturnValueD();
        if (value > config.attack_speed_soft_cap) {
            value = config.attack_speed_soft_cap + (value - config.attack_speed_soft_cap) * config.attack_speed_share_above_cap;
        }
        if (config.attack_speed_hard_cap > 0) {
            value = Math.min(value, config.attack_speed_hard_cap);
        }
        cir.setReturnValue(value);
    }
}
