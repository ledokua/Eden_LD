package net.ledok.eden_ld.mixin;

import net.ledok.eden_ld.client.compat.EmiReloadFix;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * When EMI starts a reload, and when a tag or recipe sync reaches it ({@link EmiReloadFix} reloads EMI once more when
 * the server's data kept coming after a reload had started). Nothing happens without EMI ({@code @Pseudo}); a later EMI
 * without these methods just isn't patched ({@code require = 0}).
 */
@Pseudo
@Mixin(targets = "dev.emi.emi.runtime.EmiReloadManager", remap = false)
public abstract class EmiReloadManagerMixin {

    @Inject(method = "reload", at = @At("HEAD"), remap = false, require = 0)
    private static void eden_ld$reloadStarted(CallbackInfo ci) {
        EmiReloadFix.reloadStarted();
    }

    /** After the sync: a reload it started was recorded above; one it only half-completed leaves EMI waiting. */
    @Inject(method = {"reloadTags", "reloadRecipes"}, at = @At("TAIL"), remap = false, require = 0)
    private static void eden_ld$synced(CallbackInfo ci) {
        EmiReloadFix.synced();
    }
}
