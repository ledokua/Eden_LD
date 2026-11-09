package net.ledok.eden_ld.mixin.compat.combatroll;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.ledok.eden_ld.client.ClientStamina;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Combat Roll's client check: with Eden's stamina active, a roll needs stamina ({@link ClientStamina#canRoll}) instead
 * of a charge, and each roll pays at once. Nothing happens without Combat Roll ({@code @Pseudo}, {@code require = 0}).
 */
@Pseudo
@Mixin(targets = "net.combat_roll.internals.RollManager", remap = false)
public abstract class RollManagerStaminaMixin {

    @ModifyExpressionValue(method = "isRollAvailable", remap = false, require = 0,
            at = @At(value = "FIELD", target = "Lnet/combat_roll/internals/RollManager;availableRolls:I", remap = false))
    private int eden_ld$staminaInsteadOfCharges(int availableRolls) {
        return ClientStamina.active() ? (ClientStamina.canRoll() ? 1 : 0) : availableRolls;
    }

    /** No charge recharging (and its "ready" sound) while stamina replaces charges. */
    @Inject(method = "rechargeRoll", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void eden_ld$noCharges(CallbackInfo ci) {
        if (ClientStamina.active()) {
            ci.cancel();
        }
    }

    @Inject(method = "onRoll", at = @At("HEAD"), remap = false, require = 0)
    private void eden_ld$payStamina(CallbackInfo ci) {
        ClientStamina.rolled();
    }
}
