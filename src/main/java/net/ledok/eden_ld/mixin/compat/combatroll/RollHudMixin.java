package net.ledok.eden_ld.mixin.compat.combatroll;

import net.ledok.eden_ld.client.ClientStamina;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Combat Roll's charge widget hides while Eden's stamina replaces charges (the stamina bar shows instead). */
@Pseudo
@Mixin(targets = "net.combat_roll.client.gui.HudRenderHelper", remap = false)
public abstract class RollHudMixin {

    @Inject(method = "render", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private static void eden_ld$hideCharges(CallbackInfo ci) {
        if (ClientStamina.active()) {
            ci.cancel();
        }
    }
}
