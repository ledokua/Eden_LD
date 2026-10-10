package net.ledok.eden_ld.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * EMI's reload state for {@link net.ledok.eden_ld.client.compat.EmiReloadFix}: which half of the
 * server's data (bit 1 tags, bit 2 recipes) it has seen since its last reload, and its reload
 * thread. Nothing happens without EMI ({@code @Pseudo}); only called when EMI is loaded.
 */
@Pseudo
@Mixin(targets = "dev.emi.emi.runtime.EmiReloadManager", remap = false)
public interface EmiReloadManagerAccessor {

    @Accessor(value = "loadedResourcesMask", remap = false)
    static void eden_ld$setLoadedResourcesMask(int mask) {
        throw new AssertionError();
    }

    @Accessor(value = "thread", remap = false)
    static Thread eden_ld$thread() {
        throw new AssertionError();
    }
}
