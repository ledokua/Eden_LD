package net.ledok.eden_ld.compat.arenas;

import net.ledok.arenas_ld.api.ArenasApi;
import net.ledok.eden_ld.hub.activity.ActivityLiveStatus;
import net.ledok.eden_ld.hub.activity.ActivitySource;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The Moderation window's controller actions through ArenasApi 4.9.0+ ({@code ## Admin} in Arenas_LD's API.md).
 * The only class using the 4.9.0 types: {@link ArenasIntegration} calls in here only after checking the running
 * Arenas_LD has them. A {@code Component} answer is Arenas_LD's refusal, null when done.
 */
final class ArenasAdmin {

    private ArenasAdmin() {
    }

    /** Lands on a free spot next to the controller, any dimension; refused for a player in a run or busy. */
    @Nullable
    static Component teleport(ServerPlayer admin, ResourceLocation id) {
        return ArenasApi.teleportToController(admin, id);
    }

    /** Arenas_LD doesn't check op here: the caller does ({@code AdminTools}). */
    @Nullable
    static Component rename(MinecraftServer server, ResourceLocation id, String name) {
        return ArenasApi.rename(server, id, name);
    }

    /** Arenas_LD checks op itself; the screen stays open at any distance, bound to the controller's dimension. */
    @Nullable
    static Component openAdmin(ServerPlayer admin, ResourceLocation id) {
        return ArenasApi.openAdmin(admin, id);
    }

    /** One run (the instance it's in), or every run (null); refused when none matches. */
    @Nullable
    static Component forceEnd(MinecraftServer server, ResourceLocation id, @Nullable ActivitySource.InstanceRef instance) {
        ArenasApi.InstanceRef at = instance == null ? null
                : new ArenasApi.InstanceRef(ResourceKey.create(Registries.DIMENSION, instance.dimension()), instance.pos());
        return ArenasApi.forceEnd(server, id, at);
    }

    /** Refused while the controller still exists. */
    @Nullable
    static Component forget(MinecraftServer server, ResourceLocation id) {
        return ArenasApi.forget(server, id);
    }

    /** Memory only (never loads a chunk), so the 2 s poll can ask it for every activity. */
    static Optional<ActivityLiveStatus> status(MinecraftServer server, ResourceLocation id) {
        return ArenasApi.status(server, id).map(ArenasAdmin::live);
    }

    private static ActivityLiveStatus live(ArenasApi.ActivityStatus status) {
        List<ActivityLiveStatus.Instance> instances = new ArrayList<>();
        for (ArenasApi.InstanceStatus instance : status.instances()) {
            ActivityLiveStatus.State state = state(instance.state());
            instances.add(new ActivityLiveStatus.Instance(instance.instance().dimension().location(),
                    instance.instance().pos(), state, instance.cooldownTicksLeft(), instance.pendingRemoval()));
        }
        List<ActivityLiveStatus.Run> runs = new ArrayList<>();
        for (ArenasApi.RunStatus run : status.runs()) {
            runs.add(new ActivityLiveStatus.Run(run.instance().dimension().location(), run.instance().pos(),
                    run.difficulty(), run.players(), run.phase(), run.ticksElapsed(), run.ticksLeft(), run.closing()));
        }
        // By name, so a warning a newer Arenas_LD adds is skipped rather than breaking the poll.
        Set<ActivityLiveStatus.Warning> warnings = EnumSet.noneOf(ActivityLiveStatus.Warning.class);
        for (ArenasApi.Warning warning : status.warnings()) {
            try {
                warnings.add(ActivityLiveStatus.Warning.valueOf(warning.name()));
            } catch (IllegalArgumentException ignored) {
            }
        }
        return new ActivityLiveStatus(instances, runs, status.openLobbies(), status.waitingPlayers(), warnings);
    }

    private static ActivityLiveStatus.State state(ArenasApi.InstanceState state) {
        return switch (state.name()) {
            case "RUNNING" -> ActivityLiveStatus.State.RUNNING;
            case "COOLDOWN" -> ActivityLiveStatus.State.COOLDOWN;
            default -> ActivityLiveStatus.State.FREE;
        };
    }
}
