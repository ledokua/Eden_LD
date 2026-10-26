package net.ledok.eden_ld.compat;

import net.fabricmc.loader.api.FabricLoader;
import net.ledok.eden_ld.EdenLD;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;

import java.lang.reflect.Method;
import java.util.UUID;

/**
 * Optional Realm Keys access via reflection (same approach as {@link EconomyCompat}): which dimensions a player has
 * unlocked. Realm Keys' own {@code RealmKeysData.hasAccess} answers it whole: exempt dimensions, global unlocks and the
 * player's unlocks. Without the mod (or when its API moved) every dimension counts as open.
 */
public final class RealmKeysCompat {

    public static final boolean LOADED = FabricLoader.getInstance().isModLoaded("realm-keys");

    private static Method getServerState;
    private static Method hasAccess;
    private static boolean resolved;
    private static boolean available;

    private RealmKeysCompat() {
    }

    /** Whether the player may enter {@code dimension}; true when Realm Keys isn't installed. */
    public static boolean hasAccess(MinecraftServer server, UUID player, ResourceLocation dimension) {
        if (!LOADED || !resolve()) {
            return true;
        }
        try {
            Object data = getServerState.invoke(null, server);
            return (boolean) hasAccess.invoke(data, player, dimension.toString());
        } catch (Exception e) {
            EdenLD.LOGGER.warn("[RealmKeys] Access check threw; treating every dimension as open.", e);
            available = false;
            return true;
        }
    }

    private static synchronized boolean resolve() {
        if (resolved) {
            return available;
        }
        resolved = true;
        try {
            Class<?> data = Class.forName("net.realm_keys.data.RealmKeysData");
            getServerState = data.getMethod("getServerState", MinecraftServer.class);
            hasAccess = data.getMethod("hasAccess", UUID.class, String.class);
            available = true;
        } catch (Exception e) {
            EdenLD.LOGGER.warn("[RealmKeys] API not found; dimension conditions on quests won't be checked.", e);
            available = false;
        }
        return available;
    }
}
