package net.ledok.eden_ld.compat.xaero;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.ledok.eden_ld.EdenLD;
import net.minecraft.server.MinecraftServer;

/**
 * The only Xaero's Minimap entry point the rest of the mod touches: no {@code xaero.*} imports, so it is always safe to
 * class-load; the minimap's types live in {@link XaeroTracker} behind {@link #LOADED}.
 */
public final class XaeroHook {

    public static final boolean LOADED = FabricLoader.getInstance().isModLoaded("xaerominimap");

    private XaeroHook() {
    }

    /** Tries once a second (and on each join) for this long after the start before giving up. */
    private static final int GIVE_UP_TICKS = 5 * 60 * 20;

    private static boolean pending;
    private static int ticks;

    /**
     * Guild and party members tracked on each other's minimap and world map, from the server's start. The minimap's
     * server data appears in its own SERVER_STARTED listener, which runs after Eden's (server entrypoints load after
     * main ones), so registering waits for the first tick that finds it.
     */
    public static void initialize() {
        if (!LOADED) {
            return;
        }
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            pending = true;
            ticks = 0;
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> pending = false);
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (!pending) {
                return;
            }
            ticks++;
            if (ticks % 20 == 1) {
                tryRegister(server);
            }
            if (pending && ticks >= GIVE_UP_TICKS) {
                pending = false;
                EdenLD.LOGGER.warn("[Xaero] The minimap's server data isn't there: guild and party members stay off the maps.");
            }
        });
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> server.execute(() -> {
            if (pending) {
                tryRegister(server);
            }
        }));
    }

    private static void tryRegister(MinecraftServer server) {
        if (XaeroTracker.register(server)) {
            pending = false;
        }
    }
}
