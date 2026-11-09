package net.ledok.eden_ld.mixin.compat.asteorbar;

import com.afoxxvi.asteorbar.overlay.parts.SimpleBarOverlay;
import net.ledok.eden_ld.EdenLD;
import net.ledok.eden_ld.client.ClientStamina;
import net.ledok.eden_ld.combat.Stamina;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Stamina ({@link ClientStamina}) in AsteorBar's oxygen bar: shown while it isn't full; underwater (or with air
 * missing) the oxygen bar keeps its place. Grey while exhausted. Nothing happens without AsteorBar.
 */
@Pseudo
@Mixin(targets = "com.afoxxvi.asteorbar.overlay.parts.AirLevelOverlay", remap = false)
public abstract class AirBarStaminaMixin {

    private static boolean eden_ld$air(Player player) {
        return player.isEyeInFluid(FluidTags.WATER) || player.isEyeInFluid(FluidTags.LAVA) || player.getAirSupply() < player.getMaxAirSupply();
    }

    @Inject(method = "shouldRender", at = @At("RETURN"), cancellable = true, remap = false, require = 0)
    private void eden_ld$showStamina(Player player, CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValueZ() && ClientStamina.showBar(player)) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "getParameters", at = @At("RETURN"), remap = false, require = 0)
    private void eden_ld$staminaValues(Player player, CallbackInfoReturnable<SimpleBarOverlay.Parameters> cir) {
        if (!ClientStamina.active() || eden_ld$air(player)) {
            return;
        }
        SimpleBarOverlay.Parameters parameters = cir.getReturnValue();
        parameters.value = ClientStamina.value();
        parameters.capacity = Stamina.max(player);
        boolean exhausted = ClientStamina.exhausted();
        parameters.fillColor = exhausted ? EdenLD.CONFIG.combat.stamina_exhausted_color : EdenLD.CONFIG.combat.stamina_color;
        parameters.boundColor = exhausted ? 0xFF3A3A3A : 0xFF5C4A12;
    }
}
