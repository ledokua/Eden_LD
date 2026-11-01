package net.ledok.eden_ld.compat.xaero;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;

/**
 * The only Xaero's Minimap entry point the rest of the mod touches: no {@code xaero.*} imports, so it is always safe to
 * class-load; the minimap's types live in {@link XaeroTracker} behind {@link #LOADED}.
 */
public final class XaeroHook {

    public static final boolean LOADED = FabricLoader.getInstance().isModLoaded("xaerominimap");

    private XaeroHook() {
    }

    /** Guild and party members tracked on each other's minimap and world map, from the server's start. */
    public static void initialize() {
        if (LOADED) {
            ServerLifecycleEvents.SERVER_STARTED.register(XaeroTracker::register);
        }
    }
}
