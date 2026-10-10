package net.ledok.eden_ld.mixin;

import com.mojang.blaze3d.audio.Channel;
import org.lwjgl.openal.AL10;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * OpenAL caps a source's gain at its AL_MAX_GAIN (1 by default). Vanilla never asks for more; the
 * mention ping does (to make up for master volume), so such a source gets its cap raised.
 */
@Mixin(Channel.class)
public abstract class AudioChannelMixin {

    @Shadow
    @Final
    private int source;

    @Inject(method = "setVolume", at = @At("HEAD"))
    private void eden_ld$allowLoud(float volume, CallbackInfo ci) {
        if (volume > 1f) {
            AL10.alSourcef(source, AL10.AL_MAX_GAIN, volume);
        }
    }
}
