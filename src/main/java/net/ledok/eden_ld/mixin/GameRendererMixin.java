package net.ledok.eden_ld.mixin;

import net.ledok.eden_ld.client.chat.EmojiFont;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Advances animated emojis once per rendered frame (before anything draws text). */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {

    @Inject(method = "render", at = @At("HEAD"))
    private void eden_ld$animateEmojis(DeltaTracker deltaTracker, boolean renderLevel, CallbackInfo ci) {
        EmojiFont.animate();
    }
}
