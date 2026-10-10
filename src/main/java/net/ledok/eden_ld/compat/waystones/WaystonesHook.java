package net.ledok.eden_ld.compat.waystones;

import net.fabricmc.loader.api.FabricLoader;
import net.ledok.eden_ld.EdenLD;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * The only Waystones entry point the rest of the mod touches. No {@code net.blay09}
 * imports, so it is always safe to class-load; everything mod-specific lives in
 * {@link WaystonesIntegration} behind {@link #LOADED}.
 */
public final class WaystonesHook {

    public static final boolean LOADED = FabricLoader.getInstance().isModLoaded("waystones");

    /**
     * A waystone the player may travel to: activated by them, or global. {@code owner} is
     * null for ownerless stones; {@code ownerName} is what Waystones remembers (may be empty).
     * {@code alias} and {@code favorite} are the player's own Waystones settings (the mod's
     * per-player rename and its built-in Favorites group).
     */
    public record Stone(UUID id, Component name, ResourceLocation dimension, BlockPos pos, boolean global,
                        @Nullable UUID owner, String ownerName, @Nullable Component alias, boolean favorite) {
    }

    private WaystonesHook() {
    }

    /**
     * Wires Waystones' events. {@code onPlayerChanged} fires when one player's list changed
     * (they activated a stone); {@code onAllChanged} when any stone was renamed, moved, or
     * removed. Both on the server thread.
     */
    public static void initialize(Consumer<ServerPlayer> onPlayerChanged, Runnable onAllChanged) {
        if (!LOADED) {
            return;
        }
        WaystonesIntegration.register(onPlayerChanged, onAllChanged);
        EdenLD.LOGGER.info("[Waystones] Hub integration initialized.");
    }

    /** Destinations for the hub tab (activated, global, and guildmates' TEAM stones); empty without the mod. */
    public static List<Stone> targets(ServerPlayer player) {
        return LOADED ? WaystonesIntegration.targets(player) : List.of();
    }

    /** Stars/unstars through Waystones' own Favorites group, so its menus agree. */
    public static void setFavorite(ServerPlayer player, UUID waystone, boolean favorite) {
        if (LOADED) {
            WaystonesIntegration.setFavorite(player, waystone, favorite);
        }
    }

    /** Forgets a waystone the player activated (global and guild-shared ones always stay listed). */
    public static void forget(ServerPlayer player, UUID waystone) {
        if (LOADED) {
            WaystonesIntegration.forget(player, waystone);
        }
    }

    /** The player's own name for a waystone (Waystones' alias); blank clears it. */
    public static void setAlias(ServerPlayer player, UUID waystone, String alias) {
        if (LOADED) {
            WaystonesIntegration.setAlias(player, waystone, alias);
        }
    }

    /**
     * Teleports through Waystones' pipeline (validation, pets, events other mods listen to)
     * but with no Waystones cost or cooldown — hub teleports follow Eden's own rules.
     * {@code onDone} gets null on success or the mod's error message, on the server thread.
     */
    public static void teleport(ServerPlayer player, UUID waystone, Consumer<@Nullable Component> onDone) {
        if (LOADED) {
            WaystonesIntegration.teleport(player, waystone, onDone);
        }
    }
}
