package net.ledok.eden_ld.world;

import com.mojang.serialization.DataResult;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.levelgen.WorldOptions;

import java.util.function.Supplier;

/**
 * The last {@code WorldGenSettings} encoding, for the same world options and registries (the same objects: a new
 * server or a registry reload makes new ones, and then it's encoded again). Each save gets its own copy.
 */
public final class WorldGenSettingsCache {

    private static WorldOptions options;
    private static RegistryAccess registries;
    private static Tag encoded;

    private WorldGenSettingsCache() {
    }

    public static synchronized DataResult<Tag> encode(WorldOptions worldOptions, RegistryAccess registryAccess,
                                                      Supplier<DataResult<Tag>> encoder) {
        if (encoded == null || worldOptions != options || registryAccess != registries) {
            DataResult<Tag> result = encoder.get();
            if (result.result().isEmpty()) {
                // A failure (logged by vanilla) is never cached.
                return result;
            }
            options = worldOptions;
            registries = registryAccess;
            encoded = result.result().get();
        }
        return DataResult.success(encoded.copy());
    }
}
