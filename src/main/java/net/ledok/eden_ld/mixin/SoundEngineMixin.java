package net.ledok.eden_ld.mixin;

import net.ledok.eden_ld.client.chat.MentionSound;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.sounds.SoundSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The mention ping's own gain while it starts ({@link MentionSound}): past the usual 0-1 cap, so
 * it can make up for a lowered master volume.
 */
@Mixin(SoundEngine.class)
public abstract class SoundEngineMixin {

    @Inject(method = "calculateVolume(FLnet/minecraft/sounds/SoundSource;)F", at = @At("RETURN"), cancellable = true)
    private void eden_ld$mentionGain(float volume, SoundSource source, CallbackInfoReturnable<Float> cir) {
        float gain = MentionSound.startingGain();
        if (gain >= 0) {
            cir.setReturnValue(gain);
        }
    }
}
