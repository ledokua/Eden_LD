package net.ledok.eden_ld.mixin;

import net.ledok.eden_ld.world.AsyncSavedData;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A flushing save ({@code /save-all flush}, and the server stop's, before the world folder is unlocked) returns only
 * once {@link AsyncSavedData} has written every world data file. The 5-minute autosave doesn't flush, so it doesn't wait.
 */
@Mixin(MinecraftServer.class)
public abstract class MinecraftServerSaveFlushMixin {

    @Inject(method = "saveAllChunks", at = @At("RETURN"))
    private void eden_ld$waitForDataFiles(boolean suppressLog, boolean flush, boolean force, CallbackInfoReturnable<Boolean> cir) {
        if (flush) {
            AsyncSavedData.flush();
        }
    }
}
