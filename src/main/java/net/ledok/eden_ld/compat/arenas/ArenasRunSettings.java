package net.ledok.eden_ld.compat.arenas;

import net.ledok.arenas_ld.api.ArenasApi;
import net.ledok.eden_ld.hub.activity.ActivitySource;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;

import java.lang.reflect.Field;
import java.util.Optional;

/**
 * The only class using ArenasApi 4.39.0's run settings: a dungeon's or raid's base loot rolls, for the hub's
 * «Створити лобі» panel (LeDok, 1.3.118). "Uses the global value" (-1) is read from Arenas' config
 * {@code completion_loot_rolls}, which has no API yet; if that can't be read, the default isn't shown as a number.
 */
final class ArenasRunSettings {

    private ArenasRunSettings() {
    }

    static Optional<ActivitySource.LootRolls> baseLootRolls(MinecraftServer server, ResourceLocation activity) {
        Optional<Double> own = ArenasApi.baseLootRolls(server, activity);
        if (own.isEmpty()) {
            return Optional.empty();
        }
        if (own.get() >= 0) {
            return Optional.of(new ActivitySource.LootRolls(own.get(), false));
        }
        return Optional.of(new ActivitySource.LootRolls(globalRolls(), true));
    }

    /** Arenas' {@code completion_loot_rolls}, or NaN when its config can't be read. */
    private static double globalRolls() {
        try {
            Class<?> config = Class.forName("net.ledok.arenas_ld.config.ArenasLdConfig");
            Object instance = config.getMethod("getInstance").invoke(null);
            Field field = config.getField("completion_loot_rolls");
            return field.getDouble(instance);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return Double.NaN;
        }
    }
}
