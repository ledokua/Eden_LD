package net.ledok.eden_ld.mixin;

import net.ledok.eden_ld.client.compat.EmiReloadFix;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * EMI's reload thread gives up with a one-line warning ("World is null", "Recipe Manager is null")
 * when it starts before the client has a world, and leaves EMI on "Reloading..." for good; Eden
 * reloads it again once the world is there ({@link EmiReloadFix}). Nothing happens without EMI
 * ({@code @Pseudo}), and a later EMI without this call just isn't patched ({@code require = 0}).
 */
@Pseudo
@Mixin(targets = "dev.emi.emi.runtime.EmiReloadManager$ReloadWorker", remap = false)
public abstract class EmiReloadWorkerMixin {

    @Inject(method = "run", at = @At(value = "INVOKE", target = "Ldev/emi/emi/runtime/EmiReloadLog;warn(Ljava/lang/String;)V",
            remap = false), remap = false, require = 0)
    private void eden_ld$stoppedWithoutWorld(CallbackInfo ci) {
        EmiReloadFix.stoppedWithoutWorld();
    }
}
