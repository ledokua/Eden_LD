package net.ledok.eden_ld.compat;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/**
 * NPCs_LD: starting a dialog without an NPC (the hub tabs' tours), as its {@code /npcs_ld talk} does — a dialog
 * sitting in a wait (the guide waiting for a page) is parked and comes back when this one ends. The only class
 * touching NPCs_LD types is {@link Api}.
 */
public final class NpcsCompat {

    public static final boolean LOADED = FabricLoader.getInstance().isModLoaded("npcs_ld");

    private NpcsCompat() {
    }

    /** Whether NPCs_LD has this dialog loaded. */
    public static boolean exists(ResourceLocation dialog) {
        return LOADED && Api.exists(dialog);
    }

    /** Opens the dialog for the player; false when NPCs_LD or the dialog isn't there. */
    public static boolean start(ServerPlayer player, ResourceLocation dialog) {
        return LOADED && Api.start(player, dialog);
    }

    private static final class Api {
        static boolean exists(ResourceLocation dialog) {
            return net.ledok.npcsld.dialog.DialogManager.get().graph(dialog) != null;
        }

        static boolean start(ServerPlayer player, ResourceLocation dialog) {
            if (!exists(dialog)) {
                return false;
            }
            net.ledok.npcsld.dialog.runtime.DialogSessions.open(player, null, dialog);
            return true;
        }
    }
}
