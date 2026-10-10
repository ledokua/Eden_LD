package net.ledok.eden_ld.hub.waystone;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.ledok.eden_ld.EdenLD;
import net.ledok.eden_ld.combat.CombatTag;
import net.ledok.eden_ld.compat.LuckPermsCompat;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Eden's own rules for hub waystone teleports (Waystones' costs and cooldowns don't
 * apply to them): a per-player cooldown, overridable per role through LuckPerms meta,
 * and the PvP combat tag ({@link CombatTag}). In memory; survives relogging, not restarts.
 */
public final class HubTeleportRules {

    public static final String COOLDOWN_META = "eden_ld.waystone_cooldown";

    private static final Map<UUID, Long> NEXT_TELEPORT = new ConcurrentHashMap<>();

    private HubTeleportRules() {
    }

    public static void initialize() {
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> NEXT_TELEPORT.clear());
    }

    /** Milliseconds left on the player's PvP combat tag ({@link CombatTag}), 0 when free. */
    public static long pvpMillisLeft(UUID player) {
        return CombatTag.millisLeft(player);
    }

    /** Milliseconds left on the player's teleport cooldown, 0 when ready. */
    public static long cooldownMillisLeft(UUID player) {
        Long next = NEXT_TELEPORT.get(player);
        return next == null ? 0 : Math.max(0, next - System.currentTimeMillis());
    }

    /** The player's cooldown length: LuckPerms meta {@link #COOLDOWN_META}, else the config. */
    public static int cooldownSeconds(ServerPlayer player) {
        return Math.max(0, LuckPermsCompat.metaInt(player, COOLDOWN_META)
                .orElse(EdenLD.CONFIG.hub.waystone_cooldown_seconds));
    }

    public static void markTeleported(ServerPlayer player) {
        int seconds = cooldownSeconds(player);
        if (seconds > 0) {
            NEXT_TELEPORT.put(player.getUUID(), System.currentTimeMillis() + seconds * 1000L);
        }
    }
}
