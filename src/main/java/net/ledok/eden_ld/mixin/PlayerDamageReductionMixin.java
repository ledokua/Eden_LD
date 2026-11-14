package net.ledok.eden_ld.mixin;

import net.ledok.eden_ld.combat.DamageReduction;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Players carry {@code eden_ld:damage_reduction} ({@link DamageReduction}), {@code eden_ld:max_health_percent}
 * ({@code GearStats}) and the stamina attributes.
 */
@Mixin(Player.class)
public abstract class PlayerDamageReductionMixin {

    @Inject(method = "createAttributes", at = @At("RETURN"))
    private static void eden_ld$damageReduction(CallbackInfoReturnable<AttributeSupplier.Builder> cir) {
        if (DamageReduction.ATTRIBUTE != null) {
            cir.getReturnValue().add(DamageReduction.ATTRIBUTE);
        }
        if (net.ledok.eden_ld.combat.GearStats.MAX_HEALTH_PERCENT != null) {
            cir.getReturnValue().add(net.ledok.eden_ld.combat.GearStats.MAX_HEALTH_PERCENT);
        }
        if (net.ledok.eden_ld.combat.Stamina.MAX_STAMINA != null) {
            cir.getReturnValue().add(net.ledok.eden_ld.combat.Stamina.MAX_STAMINA)
                    .add(net.ledok.eden_ld.combat.Stamina.STAMINA_REGEN)
                    .add(net.ledok.eden_ld.combat.Stamina.ROLL_COST)
                    .add(net.ledok.eden_ld.combat.Stamina.EXHAUSTION_DELAY);
        }
    }
}
