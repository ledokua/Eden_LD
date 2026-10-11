package net.ledok.eden_ld.tutorial.phase;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.ledok.eden_ld.EdenLD;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Marker;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.decoration.HangingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.AbstractMinecart;
import net.minecraft.world.entity.vehicle.Boat;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Phasing for the tutorial ({@code docs/PLAN-tutorial.md} section 3, ported from tutorial-mod-for-yggdrasil):
 * everyone in the tutorial shares one room, but each player is in their own phase. Entities of different phases don't
 * see, hurt, target or push each other; an unphased decoration is seen by all, and a shared entity
 * ({@code tutorial.always_visible}: the guide NPCs, a target dummy) is seen and can be touched by all.
 * Keeps an index of each phase's entities, to clear them when the player leaves or logs out.
 */
public final class Phases {

    /** Phase → its non-player entities (in memory; rebuilt as entities load). */
    private static final Map<UUID, Set<UUID>> ENTITIES = new ConcurrentHashMap<>();

    private Phases() {
    }

    public static void initialize() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            PhaseContext.clear();
            PrivateBlocks.endTick();
        });
    }

    @Nullable
    public static UUID of(@Nullable Entity entity) {
        return entity instanceof Phased phased ? phased.eden_ld$phase() : null;
    }

    /**
     * Whether these two can touch: hurt, target, push. A shared entity ({@code tutorial.always_visible}, unphased —
     * a target dummy, the guide) is everyone's, so any phase may hit it.
     */
    public static boolean together(Entity a, @Nullable Entity b) {
        if (b == null) {
            return true;
        }
        UUID phaseA = of(a);
        UUID phaseB = of(b);
        return Objects.equals(phaseA, phaseB) || phaseA == null && alwaysVisible(a) || phaseB == null && alwaysVisible(b);
    }

    /** Whether {@code viewer} sees (and hears) {@code entity}: the same phase, or a shared decoration or NPC. */
    public static boolean sees(Entity viewer, Entity entity) {
        UUID phase = of(entity);
        if (Objects.equals(of(viewer), phase)) {
            return true;
        }
        return phase == null && (isDecoration(entity) || alwaysVisible(entity));
    }

    private static boolean isDecoration(Entity entity) {
        return entity instanceof HangingEntity || entity instanceof ArmorStand || entity instanceof net.minecraft.world.entity.Display
                || entity instanceof Boat || entity instanceof AbstractMinecart || entity instanceof EndCrystal
                || entity instanceof Marker;
    }

    private static boolean alwaysVisible(Entity entity) {
        var list = EdenLD.CONFIG.tutorial.always_visible;
        if (list.isEmpty()) {
            return false;
        }
        ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        return list.contains(id.toString()) || list.contains(id.getNamespace() + ":");
    }

    // ---------------------------------------------------------------- index

    static void indexed(UUID phase, Entity entity) {
        if (!(entity instanceof Player)) {
            ENTITIES.computeIfAbsent(phase, key -> ConcurrentHashMap.newKeySet()).add(entity.getUUID());
        }
    }

    static void unindexed(UUID phase, Entity entity) {
        Set<UUID> set = ENTITIES.get(phase);
        if (set != null) {
            set.remove(entity.getUUID());
            if (set.isEmpty()) {
                ENTITIES.remove(phase);
            }
        }
    }

    /** Called by the entity mixin when an entity's phase changes. */
    public static void changed(Entity entity, @Nullable UUID before, @Nullable UUID after) {
        if (before != null) {
            unindexed(before, entity);
        }
        if (after != null) {
            indexed(after, entity);
        }
    }

    /** Removes every loaded mob, item and projectile of the phase (the player left, logged out or finished). */
    public static void discardAll(MinecraftServer server, UUID phase) {
        Set<UUID> ids = ENTITIES.remove(phase);
        if (ids == null) {
            return;
        }
        for (UUID id : Set.copyOf(ids)) {
            for (ServerLevel level : server.getAllLevels()) {
                Entity entity = level.getEntity(id);
                if (entity != null && !(entity instanceof Player)) {
                    entity.discard();
                    break;
                }
            }
        }
    }

    /**
     * Whether a phased entity still has its player here: online and still in that phase. Entities left behind (their
     * player logged out or finished) are removed when they next tick.
     */
    public static boolean hasOwner(MinecraftServer server, UUID phase) {
        ServerPlayer owner = server.getPlayerList().getPlayer(phase);
        return owner != null && phase.equals(of(owner));
    }

    public static Set<UUID> entities(UUID phase) {
        return ENTITIES.getOrDefault(phase, Collections.emptySet());
    }
}
