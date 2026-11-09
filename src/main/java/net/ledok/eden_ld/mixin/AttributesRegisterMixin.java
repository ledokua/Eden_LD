package net.ledok.eden_ld.mixin;

import net.ledok.eden_ld.combat.DamageReduction;
import net.minecraft.world.entity.ai.attributes.Attributes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Eden's attributes are registered with vanilla's: the class mods (Spell Engine's RPG Series armor) resolve the attributes
 * in their equipment configs while they register items, which can happen before Eden's own init.
 */
@Mixin(Attributes.class)
public abstract class AttributesRegisterMixin {

    @Inject(method = "<clinit>", at = @At("TAIL"))
    private static void eden_ld$registerAttributes(CallbackInfo ci) {
        DamageReduction.register();
        net.ledok.eden_ld.combat.Stamina.registerAttributes();
    }
}
