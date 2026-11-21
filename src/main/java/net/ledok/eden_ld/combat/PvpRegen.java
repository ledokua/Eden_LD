package net.ledok.eden_ld.combat;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.ledok.eden_ld.EdenLD;
import net.ledok.eden_ld.config.EdenConfig;
import net.ledok.eden_ld.world.Hazards;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Slower natural regeneration in PvP ({@code combat.json} {@code pvp_regen}, LeDok 2026-11-20): for
 * {@code window_seconds} after a player last took damage from another player ({@link Hazards#fromOtherPlayer}), each
 * heal from a full hunger bar or saturation is {@code natural_regen_multiplier} of vanilla's, while it still costs
 * the full exhaustion (the FoodData mixin shrinks only the heal; FloraFare curve mode is charged for the unshrunk heal). The combat tag's
 * {@code pvp_healing_received} applies on top, as to every heal. In memory, by uuid: a relog keeps the window, a
 * restart clears it.
 */
public final class PvpRegen {

    /** When each player last took PvP damage (System.currentTimeMillis). */
    private static final Map<UUID, Long> LAST_HIT = new ConcurrentHashMap<>();
    /** Above this many entries, a new hit first drops the expired ones. */
    private static final int PRUNE_ABOVE = 256;

    private PvpRegen() {
    }

    public static void initialize() {
        ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, baseDamage, damage, blocked) -> {
            if (entity instanceof ServerPlayer victim && damage > 0 && on(EdenLD.CONFIG.combat.pvp_regen)
                    && Hazards.fromOtherPlayer(victim, source)) {
                hit(victim.getUUID());
            }
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> LAST_HIT.clear());
        EdenConfig.Combat.PvpRegen config = EdenLD.CONFIG.combat.pvp_regen;
        if (on(config)) {
            EdenLD.LOGGER.info("[PvP regen] Natural regeneration ({}) heals x{} for {} s after PvP damage (combat.json "
                            + "pvp_regen; pvp_healing_received x{} applies on top while combat-tagged).",
                    net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("florafare")
                            ? "vanilla's and FloraFare's curve" : "vanilla's",
                    config.natural_regen_multiplier, config.window_seconds, EdenLD.CONFIG.combat.pvp_healing_received);
        } else {
            EdenLD.LOGGER.info("[PvP regen] Off (combat.json pvp_regen).");
        }
    }

    private static boolean on(EdenConfig.Combat.PvpRegen config) {
        return config != null && config.enabled && config.window_seconds > 0 && config.natural_regen_multiplier < 1;
    }

    private static void hit(UUID player) {
        long now = System.currentTimeMillis();
        if (LAST_HIT.size() > PRUNE_ABOVE) {
            long window = EdenLD.CONFIG.combat.pvp_regen.window_seconds * 1000L;
            LAST_HIT.values().removeIf(at -> now - at > window);
        }
        LAST_HIT.put(player, now);
    }

    /** FloraFare curve mode: the heal its exhaustion cost is computed from, or -1 to leave its own (server thread). */
    private static float curveCostHeal = -1;

    /** Before FloraFare's curve heal: the unshrunk heal to charge for, or -1 when the heal wasn't shrunk. */
    public static void curveCost(float heal) {
        curveCostHeal = heal;
    }

    /** FloraFare's cost input: the heal it measured ({@code healed}), or the unshrunk one set just before. */
    public static float takeCurveCost(float healed) {
        float heal = curveCostHeal;
        curveCostHeal = -1;
        return heal < 0 ? healed : heal;
    }

    /**
     * A natural regeneration heal of {@code amount} (vanilla's FoodData#tick, or FloraFare's curve heal), shrunk while
     * the player is in the PvP window.
     */
    public static float naturalHeal(Player player, float amount) {
        EdenConfig.Combat.PvpRegen config = EdenLD.CONFIG.combat.pvp_regen;
        if (!on(config) || player.level().isClientSide()) {
            return amount;
        }
        Long at = LAST_HIT.get(player.getUUID());
        if (at == null) {
            return amount;
        }
        if (System.currentTimeMillis() - at > config.window_seconds * 1000L) {
            LAST_HIT.remove(player.getUUID(), at);
            return amount;
        }
        return amount * (float) Math.max(0, config.natural_regen_multiplier);
    }
}
