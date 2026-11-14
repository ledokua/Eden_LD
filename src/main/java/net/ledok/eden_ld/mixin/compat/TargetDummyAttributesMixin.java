package net.ledok.eden_ld.mixin.compat;

import net.ledok.eden_ld.combat.DamageReduction;
import net.ledok.eden_ld.combat.GearStats;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Dummmmmmy's target dummy carries Eden's {@code eden_ld:damage_reduction} and {@code eden_ld:max_health_percent}, so
 * armor put on it counts ({@code combat.TargetDummies}). Nothing without Dummmmmmy.
 */
@Pseudo
@Mixin(targets = "net.mehvahdjukaar.dummmmmmy.common.TargetDummyEntity", remap = false)
abstract class TargetDummyAttributesMixin {

    @Inject(method = "makeAttributes", at = @At("RETURN"), remap = false, require = 0)
    private static void eden_ld$edenAttributes(CallbackInfoReturnable<AttributeSupplier.Builder> cir) {
        AttributeSupplier.Builder builder = cir.getReturnValue();
        if (DamageReduction.ATTRIBUTE != null) {
            builder.add(DamageReduction.ATTRIBUTE);
        }
        if (GearStats.MAX_HEALTH_PERCENT != null) {
            builder.add(GearStats.MAX_HEALTH_PERCENT);
        }
    }
}
