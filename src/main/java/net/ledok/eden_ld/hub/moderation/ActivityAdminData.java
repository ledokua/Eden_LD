package net.ledok.eden_ld.hub.moderation;

import com.mojang.authlib.GameProfile;
import net.ledok.eden_ld.hub.activity.Activities;
import net.ledok.eden_ld.hub.activity.Activity;
import net.ledok.eden_ld.hub.activity.ActivityHistory;
import net.ledok.eden_ld.hub.activity.ActivityLiveStatus;
import net.ledok.eden_ld.hub.activity.ActivitySource;
import net.ledok.eden_ld.hub.activity.RunStats;
import net.ledok.eden_ld.hub.lang.ServerLang;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.GameProfileCache;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The live part of the Moderation window's Activities section ({@code HubPayloads.ActivityLive}), polled
 * about every 2 s while it's open: a compact summary of every activity (the list's markers) and, for the
 * selected one only, its live status (when its source reports one) and its run history. Player names are
 * resolved here (online, else the server's profile cache). Server thread.
 * <pre>
 * rows:   [{id, running, cooldown, lobbies, queued, warnings (bitmask), closed}]
 * detail: {id, status?: {instances: [{dim, x, y, z, state, cooldown, removal}],
 *                        runs: [{dim, x, y, z, difficulty, players: [names], phase (JSON), elapsed, left, closing}],
 *                        lobbies, queued, warnings},
 *          history: {stats: [{difficulty, runs, wins, rate, median, best, wave}],
 *                    recent: [{ago (s), difficulty, won, forced, millis, wave, players: [names]}]}}
 * </pre>
 * Times are in ticks except {@code median}, {@code best} and {@code millis} (ms) and {@code ago} (seconds).
 */
final class ActivityAdminData {

    /** Runs listed in the detail (the history keeps more). */
    static final int RECENT_SHOWN = 10;

    private ActivityAdminData() {
    }

    static CompoundTag live(MinecraftServer server, ServerPlayer admin, String selected) {
        CompoundTag tag = new CompoundTag();
        ListTag rows = new ListTag();
        Activity picked = null;
        ResourceLocation selectedId = selected.isBlank() ? null : ResourceLocation.tryParse(selected);
        // Sources looked up once for every row (Activities.status per id would walk every source's list again).
        Map<ResourceLocation, ActivitySource> sources = Activities.sources(server);
        for (Activity activity : Activities.all(server)) {
            rows.add(summary(server, activity.id(), sources.get(activity.id())));
            if (activity.id().equals(selectedId)) {
                picked = activity;
            }
        }
        tag.put("rows", rows);
        if (picked != null) {
            tag.put("detail", detail(server, admin, picked, sources.get(picked.id())));
        }
        return tag;
    }

    /** One row's markers: runs, cooldowns, lobbies, queue, warnings, closed. */
    private static CompoundTag summary(MinecraftServer server, ResourceLocation id, @Nullable ActivitySource source) {
        CompoundTag row = new CompoundTag();
        row.putString("id", id.toString());
        Activities.status(server, id, source).ifPresent(status -> {
            row.putInt("running", status.running());
            row.putInt("cooldown", status.coolingDown());
            row.putInt("lobbies", status.openLobbies());
            row.putInt("queued", status.queued());
            row.putInt("warnings", ActivityLiveStatus.Warning.mask(status.warnings()));
        });
        row.putBoolean("closed", Activities.closed(server, id));
        return row;
    }

    private static CompoundTag detail(MinecraftServer server, ServerPlayer admin, Activity activity,
                                      @Nullable ActivitySource source) {
        CompoundTag detail = new CompoundTag();
        detail.putString("id", activity.id().toString());
        Optional<ActivityLiveStatus> status = Activities.status(server, activity.id(), source);
        status.ifPresent(live -> detail.put("status", status(server, admin, live)));
        detail.put("history", history(server, activity));
        return detail;
    }

    private static CompoundTag status(MinecraftServer server, ServerPlayer admin, ActivityLiveStatus live) {
        CompoundTag tag = new CompoundTag();
        ListTag instances = new ListTag();
        for (ActivityLiveStatus.Instance instance : live.instances()) {
            CompoundTag i = place(instance.dimension(), instance.pos());
            i.putInt("state", instance.state().ordinal());
            i.putInt("cooldown", instance.state() == ActivityLiveStatus.State.COOLDOWN
                    ? Math.max(0, instance.cooldownTicksLeft()) : 0);
            i.putBoolean("removal", instance.pendingRemoval());
            instances.add(i);
        }
        tag.put("instances", instances);
        ListTag runs = new ListTag();
        for (ActivityLiveStatus.Run run : live.runs()) {
            CompoundTag r = place(run.dimension(), run.instance());
            r.putString("difficulty", run.difficulty() == null ? "" : run.difficulty());
            r.put("players", names(server, run.players()));
            r.putString("phase", Component.Serializer.toJson(ServerLang.localize(admin, run.phase()), server.registryAccess()));
            r.putLong("elapsed", Math.max(0, run.ticksElapsed()));
            r.putLong("left", run.ticksLeft() < 0 ? -1 : run.ticksLeft());
            r.putBoolean("closing", run.closing());
            runs.add(r);
        }
        tag.put("runs", runs);
        tag.putInt("lobbies", live.openLobbies());
        tag.putInt("queued", live.queued());
        tag.putInt("warnings", ActivityLiveStatus.Warning.mask(live.warnings()));
        return tag;
    }

    private static CompoundTag history(MinecraftServer server, Activity activity) {
        ActivityHistory history = ActivityHistory.get(server);
        CompoundTag tag = new CompoundTag();
        // The activity's difficulties in their order, then any it no longer has.
        Map<String, RunStats> all = history.stats(activity.id());
        Map<String, RunStats> ordered = new LinkedHashMap<>();
        for (Activity.Difficulty difficulty : activity.difficulties()) {
            RunStats stats = all.get(difficulty.id());
            if (stats != null) {
                ordered.put(difficulty.id(), stats);
            }
        }
        all.forEach(ordered::putIfAbsent);
        ListTag stats = new ListTag();
        ordered.forEach((difficulty, s) -> {
            CompoundTag d = new CompoundTag();
            d.putString("difficulty", difficulty);
            d.putInt("runs", s.runs());
            d.putInt("wins", s.wins());
            d.putInt("rate", s.winRate());
            d.putLong("median", s.medianMillis());
            d.putLong("best", s.bestMillis());
            d.putInt("wave", s.bestWave());
            stats.add(d);
        });
        tag.put("stats", stats);
        long now = System.currentTimeMillis() / 1000L;
        ListTag recent = new ListTag();
        for (ActivityHistory.Run run : history.recent(activity.id(), RECENT_SHOWN)) {
            CompoundTag r = new CompoundTag();
            r.putLong("ago", Math.max(0, now - run.epochSeconds()));
            r.putString("difficulty", run.difficulty() == null ? "" : run.difficulty());
            r.putBoolean("won", run.won());
            r.putBoolean("forced", run.forced());
            r.putLong("millis", run.durationMillis());
            r.putInt("wave", run.wave());
            r.put("players", names(server, run.players()));
            recent.add(r);
        }
        tag.put("recent", recent);
        return tag;
    }

    private static CompoundTag place(ResourceLocation dimension, BlockPos pos) {
        CompoundTag tag = new CompoundTag();
        tag.putString("dim", dimension.toString());
        tag.putInt("x", pos.getX());
        tag.putInt("y", pos.getY());
        tag.putInt("z", pos.getZ());
        return tag;
    }

    /** Player names for the UI: online, else the profile cache, else the UUID's first part. */
    static ListTag names(MinecraftServer server, List<UUID> players) {
        ListTag names = new ListTag();
        for (UUID uuid : players) {
            names.add(StringTag.valueOf(name(server, uuid)));
        }
        return names;
    }

    static String name(MinecraftServer server, UUID uuid) {
        ServerPlayer online = server.getPlayerList().getPlayer(uuid);
        if (online != null) {
            return online.getGameProfile().getName();
        }
        GameProfileCache cache = server.getProfileCache();
        String known = cache == null ? null : cache.get(uuid).map(GameProfile::getName).orElse(null);
        return known != null ? known : uuid.toString().substring(0, 8);
    }
}
