package net.ledok.eden_ld.mixin;

import net.ledok.eden_ld.world.AsyncSavedData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Lootr's {@code /lootr} refresh and cull list every file in {@code data/lootr/}, then delete some on their own thread:
 * {@link AsyncSavedData}'s queue is written first, so no write still waiting brings a deleted file back. Does nothing
 * without Lootr.
 */
@Pseudo
@Mixin(targets = "noobanidus.mods.lootr.common.data.DataStorage", remap = false)
public abstract class LootrDataFilesMixin {

    @Inject(method = "getAllLootrFiles", at = @At("HEAD"), require = 0)
    private static void eden_ld$writeQueuedFirst(CallbackInfoReturnable<?> cir) {
        AsyncSavedData.flush();
    }
}
