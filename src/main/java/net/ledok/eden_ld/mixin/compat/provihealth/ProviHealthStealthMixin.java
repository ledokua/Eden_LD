package net.ledok.eden_ld.mixin.compat.provihealth;

import net.ledok.eden_ld.combat.Stealth;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Provi's Health Bars shows no bar (over the entity, nor the target bar on the HUD) for someone in stealth
 * ({@link Stealth}) who is invisible to this player: the bar gave the hidden player away. Nothing happens without it.
 */
@Pseudo
@Mixin(targets = "com.provismet.provihealth.util.Visibility", remap = false)
public abstract class ProviHealthStealthMixin {

    @Inject(method = "isVisible", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private static void eden_ld$hideInStealth(LivingEntity entity, CallbackInfoReturnable<Boolean> cir) {
        if (Stealth.hiddenFrom(entity, Minecraft.getInstance().player)) {
            cir.setReturnValue(false);
        }
    }
}
