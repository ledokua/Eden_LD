package net.ledok.eden_ld.mixin;

import net.ledok.eden_ld.world.AsyncSavedData;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.storage.DimensionDataStorage;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.io.File;
import java.util.function.BiFunction;

/** A data file isn't read (or checked for) while its newer version is still waiting for {@link AsyncSavedData}. */
@Mixin(DimensionDataStorage.class)
public abstract class DimensionDataStorageAsyncMixin {

    @Shadow
    @Final
    private File dataFolder;

    /** Checks whether the file exists before reading it: a first write still queued would make it look missing. */
    @Inject(method = "readSavedData", at = @At("HEAD"))
    private void eden_ld$awaitBeforeLoad(BiFunction<CompoundTag, HolderLookup.Provider, ?> reader, DataFixTypes types,
                                         String name, CallbackInfoReturnable<?> cir) {
        AsyncSavedData.awaitFile(new File(dataFolder, name + ".dat"));
    }

    @Inject(method = "readTagFromDisk", at = @At("HEAD"))
    private void eden_ld$awaitBeforeRead(String name, DataFixTypes types, int version,
                                         CallbackInfoReturnable<?> cir) {
        AsyncSavedData.awaitFile(new File(dataFolder, name + ".dat"));
    }
}
