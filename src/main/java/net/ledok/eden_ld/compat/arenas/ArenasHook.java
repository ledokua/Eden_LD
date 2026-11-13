package net.ledok.eden_ld.compat.arenas;

import net.fabricmc.loader.api.FabricLoader;
import net.ledok.eden_ld.EdenLD;

/**
 * The only Arenas_LD entry point the rest of the mod touches. No {@code net.ledok.arenas_ld}
 * imports, so it is always safe to class-load; everything mod-specific lives in
 * {@link ArenasIntegration} behind {@link #LOADED}.
 */
public final class ArenasHook {

    public static final boolean LOADED = FabricLoader.getInstance().isModLoaded("arenas_ld");

    /** Arenas_LD asks Eden before sending a raid/dungeon/arena invite ({@code hub.ActivityInvites}). */
    private static volatile boolean invitesFiltered;

    private ArenasHook() {
    }

    /** Whether Arenas_LD checks Eden's invite settings (so the Settings tab shows them). */
    public static boolean invitesFiltered() {
        return invitesFiltered;
    }

    static void invitesFiltered(boolean filtered) {
        invitesFiltered = filtered;
    }

    /** Adds Arenas_LD's dungeons, raids and arenas to the Activities tab, gates them by level, and records their runs. */
    public static void initialize() {
        if (!LOADED) {
            return;
        }
        ArenasIntegration.register();
        EdenLD.LOGGER.info("[Arenas] Activities integration initialized.");
    }
}
