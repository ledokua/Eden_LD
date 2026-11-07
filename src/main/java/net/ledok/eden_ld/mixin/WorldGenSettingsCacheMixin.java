package net.ledok.eden_ld.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import net.ledok.eden_ld.EdenLD;
import net.ledok.eden_ld.world.WorldGenSettingsCache;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.levelgen.WorldGenSettings;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.storage.PrimaryLevelData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * level.dat's {@code WorldGenSettings} (the world's options and every dimension) are encoded once per server and reused
 * ({@code saving.json} {@code cache_world_gen_settings}); every save re-encoded them, 18% of the live autosave with
 * fabric-dimensions' codec.
 */
@Mixin(PrimaryLevelData.class)
public abstract class WorldGenSettingsCacheMixin {

    @WrapOperation(method = "setTagData", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/levelgen/WorldGenSettings;encode(Lcom/mojang/serialization/DynamicOps;Lnet/minecraft/world/level/levelgen/WorldOptions;Lnet/minecraft/core/RegistryAccess;)Lcom/mojang/serialization/DataResult;"))
    private DataResult<Tag> eden_ld$encodeOnce(DynamicOps<Tag> ops, WorldOptions options, RegistryAccess registries,
                                               Operation<DataResult<Tag>> original) {
        if (!EdenLD.CONFIG.saving.cache_world_gen_settings) {
            return original.call(ops, options, registries);
        }
        return WorldGenSettingsCache.encode(options, registries, () -> original.call(ops, options, registries));
    }
}
