package net.ledok.eden_ld.compat;

import java.lang.reflect.Method;

import net.fabricmc.fabric.api.entity.event.v1.ServerEntityWorldChangeEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.ledok.eden_ld.EdenLD;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.border.WorldBorder;

/**
 * Mine Cells: a player who leaves its dimension gets their own world border back to normal, so they can mount boats,
 * dragons and other vehicles again.
 * <p>
 * Mine Cells gives every entity a private world border. In its dimensions that border is shrunk to the player's
 * 1024-block cell, refreshed every second, and never reset once they leave. Its {@code startRiding} hook refuses any
 * vehicle more than 2 blocks outside that border. Leaving by waystone (or any other teleport) kept the cell-sized
 * border, centered on the old Mine Cells coordinates, so every vehicle was "outside" it until a relog, which makes a
 * new player object. The client
 * makes a new player on every dimension change, so only the server side needs this. Nothing without Mine Cells.
 */
public final class MineCellsCompat {

    private static final String NAMESPACE = "minecells";

    private MineCellsCompat() {
    }

    public static void initialize() {
        if (!FabricLoader.getInstance().isModLoaded(NAMESPACE)) {
            return;
        }
        Method border;
        try {
            border = Class.forName("com.github.mim1q.minecells.accessor.MineCellsBorderEntity")
                    .getMethod("getMineCellsBorder");
        } catch (ReflectiveOperationException | LinkageError e) {
            EdenLD.LOGGER.warn("[Mine Cells] Couldn't find the entity border accessor; the leave-dimension reset is off.",
                    e);
            return;
        }
        ServerEntityWorldChangeEvents.AFTER_PLAYER_CHANGE_WORLD.register((player, origin, destination) ->
                resetIfLeft(border, player, destination));
    }

    private static void resetIfLeft(Method accessor, ServerPlayer player, ServerLevel destination) {
        if (NAMESPACE.equals(destination.dimension().location().getNamespace())) {
            return;
        }
        try {
            if (accessor.invoke(player) instanceof WorldBorder border
                    && border.getSize() < WorldBorder.MAX_SIZE) {
                border.setCenter(0.0, 0.0);
                border.setSize(WorldBorder.MAX_SIZE);
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            EdenLD.LOGGER.warn("[Mine Cells] Couldn't reset {}'s entity border.", player.getGameProfile().getName(), e);
        }
    }
}
