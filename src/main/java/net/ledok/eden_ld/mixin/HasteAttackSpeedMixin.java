package net.ledok.eden_ld.mixin;

import net.ledok.eden_ld.EdenLD;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Haste's attack speed at {@code combat.haste_attack_speed_per_level} per level instead of vanilla's 10%: its mining
 * speed stays. Every effect modifier (applying it, and the potion tooltip) is made here from the effect's template.
 */
@Mixin(targets = "net.minecraft.world.effect.MobEffect$AttributeTemplate")
abstract class HasteAttackSpeedMixin {

    @Unique
    private static final ResourceLocation EDEN_LD$HASTE = ResourceLocation.withDefaultNamespace("effect.haste");

    @Shadow
    @Final
    private ResourceLocation id;

    @Shadow
    @Final
    private AttributeModifier.Operation operation;

    @Inject(method = "create", at = @At("HEAD"), cancellable = true)
    private void eden_ld$hasteAttackSpeed(int amplifier, CallbackInfoReturnable<AttributeModifier> cir) {
        if (EDEN_LD$HASTE.equals(id) && EdenLD.CONFIG != null) {
            cir.setReturnValue(new AttributeModifier(id,
                    EdenLD.CONFIG.combat.haste_attack_speed_per_level * (amplifier + 1), operation));
        }
    }
}
