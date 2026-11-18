package net.ledok.eden_ld.compat.arenas;

import net.ledok.arenas_ld.api.ArenasApi;
import net.ledok.eden_ld.hub.activity.ActivitySource;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;

import java.lang.reflect.Field;
import java.util.Optional;

/**
 * The only class using ArenasApi 4.39.0's run settings: a dungeon's or raid's base loot rolls, for the hub's
 * «Створити лобі» panel (LeDok, 1.3.118). "Uses the global value" (-1) comes from
 * {@code ArenasApi.completionLootRolls} (4.40.4+, 1.3.119), else Arenas' config {@code completion_loot_rolls}; if neither
 * can be read, the default isn't shown as a number.
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
        return Optional.of(new ActivitySource.LootRolls(globalRolls(server), true));
    }

    /** {@code ArenasApi.completionLootRolls} (4.40.4+), looked up once; null on an older Arenas_LD. */
    @org.jetbrains.annotations.Nullable
    private static final java.lang.reflect.Method COMPLETION_ROLLS = completionRollsMethod();

    /** Arenas' {@code completion_loot_rolls}: through its API when it has one, else its config; NaN when neither works. */
    private static double globalRolls(MinecraftServer server) {
        if (COMPLETION_ROLLS != null) {
            try {
                return ((Number) COMPLETION_ROLLS.invoke(null, server)).doubleValue();
            } catch (ReflectiveOperationException | RuntimeException e) {
                // Fall back to the config below.
            }
        }
        try {
            Class<?> config = Class.forName("net.ledok.arenas_ld.config.ArenasLdConfig");
            Object instance = config.getMethod("getInstance").invoke(null);
            Field field = config.getField("completion_loot_rolls");
            return field.getDouble(instance);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return Double.NaN;
        }
    }

    @org.jetbrains.annotations.Nullable
    private static java.lang.reflect.Method completionRollsMethod() {
        try {
            return ArenasApi.class.getMethod("completionLootRolls", MinecraftServer.class);
        } catch (ReflectiveOperationException | LinkageError e) {
            return null;
        }
    }
}
