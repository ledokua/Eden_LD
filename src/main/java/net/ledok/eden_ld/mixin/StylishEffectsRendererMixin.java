package net.ledok.eden_ld.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import net.ledok.eden_ld.client.compat.TrinketsEffectsArea;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Stylish Effects draws the inventory's effect widgets right next to the panel's left edge, where
 * Trinkets' extra slot columns are — and Trinkets draws over them. The widgets now start left of those
 * columns ({@link TrinketsEffectsArea}), with that much less room. Nothing happens without Stylish
 * Effects ({@code @Pseudo}), and a later version without this method just isn't patched ({@code require = 0}).
 */
@Pseudo
@Mixin(targets = "fuzs.stylisheffects.client.gui.effects.AbstractEffectRenderer", remap = false)
public abstract class StylishEffectsRendererMixin {

    @ModifyVariable(method = "setScreenDimensions", at = @At("HEAD"), argsOnly = true, ordinal = 0, remap = false, require = 0)
    private int eden_ld$roomBesideTrinkets(int availableWidth, @Local(argsOnly = true) Object screen,
                                           @Local(argsOnly = true, ordinal = 2) int startX) {
        return availableWidth - TrinketsEffectsArea.shift(screen, startX);
    }

    @ModifyVariable(method = "setScreenDimensions", at = @At("HEAD"), argsOnly = true, ordinal = 2, remap = false, require = 0)
    private int eden_ld$startBesideTrinkets(int startX, @Local(argsOnly = true) Object screen) {
        return startX - TrinketsEffectsArea.shift(screen, startX);
    }
}
