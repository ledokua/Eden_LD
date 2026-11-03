package net.ledok.eden_ld.mixin;

import net.ledok.eden_ld.world.AsyncSavedData;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.level.saveddata.SavedData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.io.File;

/** World data files are written off the server thread ({@link AsyncSavedData}); Lootr's overrides call this too. */
@Mixin(SavedData.class)
public abstract class SavedDataAsyncMixin {

    @Inject(method = "save(Ljava/io/File;Lnet/minecraft/core/HolderLookup$Provider;)V", at = @At("HEAD"), cancellable = true)
    private void eden_ld$saveOffThread(File file, HolderLookup.Provider registries, CallbackInfo ci) {
        if (AsyncSavedData.save((SavedData) (Object) this, file, registries)) {
            ci.cancel();
        }
    }
}
