package net.ledok.eden_ld.hub.activity;

import net.ledok.eden_ld.EdenData;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Every activity's run history, server-wide (the Moderation window's Activities section): the last
 * {@link #RECENT} runs, and per difficulty its {@link RunStats}. One entry per run, not per player.
 * A <b>forced</b> run (ended by staff) is listed but left out of the stats. Kept in
 * {@code data/eden_ld/activity_history.dat}. Server thread.
 */
public final class ActivityHistory extends SavedData {

    private static final Factory<ActivityHistory> FACTORY = new Factory<>(ActivityHistory::new, ActivityHistory::load, null);
    /** Runs kept per activity. */
    public static final int RECENT = 50;
    /** The stats key of runs without a difficulty (arenas). */
    public static final String NO_DIFFICULTY = "_";

    /** A finished run: when (epoch seconds), its difficulty (null for arenas), and how it went. */
    public record Run(long epochSeconds, @Nullable String difficulty, boolean won, boolean forced, long durationMillis,
                      int wave, List<UUID> players) {
        public Run {
            players = List.copyOf(players);
        }
    }

    private static final class Entry {
        final Deque<Run> recent = new ArrayDeque<>();
        final Map<String, RunStats> stats = new LinkedHashMap<>();
    }

    private final Map<String, Entry> entries = new HashMap<>();

    public static ActivityHistory get(MinecraftServer server) {
        return EdenData.get(server, FACTORY, "activity_history");
    }

    public static String key(@Nullable String difficulty) {
        return difficulty == null || difficulty.isBlank() ? NO_DIFFICULTY : difficulty;
    }

    public void record(ResourceLocation id, Run run) {
        Entry entry = entries.computeIfAbsent(id.toString(), key -> new Entry());
        RunStats.push(entry.recent, run, RECENT);
        // Ended by staff: listed, but it says nothing about how hard the activity is.
        if (!run.forced()) {
            entry.stats.computeIfAbsent(key(run.difficulty()), key -> new RunStats())
                    .record(run.won(), run.durationMillis(), run.wave());
        }
        setDirty();
    }

    /** The newest {@code limit} runs, newest first. */
    public List<Run> recent(ResourceLocation id, int limit) {
        Entry entry = entries.get(id.toString());
        List<Run> out = new ArrayList<>();
        if (entry != null) {
            var newest = entry.recent.descendingIterator();
            while (newest.hasNext() && out.size() < limit) {
                out.add(newest.next());
            }
        }
        return out;
    }

    /** Stats per difficulty key ({@link #NO_DIFFICULTY} for arenas), in the order first played. */
    public Map<String, RunStats> stats(ResourceLocation id) {
        Entry entry = entries.get(id.toString());
        return entry == null ? Map.of() : java.util.Collections.unmodifiableMap(entry.stats);
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        CompoundTag all = new CompoundTag();
        entries.forEach((id, entry) -> {
            CompoundTag activity = new CompoundTag();
            ListTag recent = new ListTag();
            for (Run run : entry.recent) {
                CompoundTag r = new CompoundTag();
                r.putLong("at", run.epochSeconds());
                if (run.difficulty() != null) {
                    r.putString("difficulty", run.difficulty());
                }
                r.putBoolean("won", run.won());
                if (run.forced()) {
                    r.putBoolean("forced", true);
                }
                r.putLong("millis", run.durationMillis());
                r.putInt("wave", run.wave());
                ListTag players = new ListTag();
                for (UUID player : run.players()) {
                    players.add(net.minecraft.nbt.NbtUtils.createUUID(player));
                }
                r.put("players", players);
                recent.add(r);
            }
            activity.put("recent", recent);
            CompoundTag stats = new CompoundTag();
            entry.stats.forEach((difficulty, s) -> {
                CompoundTag d = new CompoundTag();
                d.putInt("runs", s.runs());
                d.putInt("wins", s.wins());
                d.putLong("best", s.bestMillis());
                d.putInt("wave", s.bestWave());
                ListTag clears = new ListTag();
                s.clears().forEach(clear -> clears.add(LongTag.valueOf(clear)));
                d.put("clears", clears);
                stats.put(difficulty, d);
            });
            activity.put("stats", stats);
            all.put(id, activity);
        });
        tag.put("activities", all);
        return tag;
    }

    /** Package-private for the tests. */
    static ActivityHistory load(CompoundTag tag, HolderLookup.Provider registries) {
        ActivityHistory data = new ActivityHistory();
        CompoundTag all = tag.getCompound("activities");
        for (String id : all.getAllKeys()) {
            CompoundTag activity = all.getCompound(id);
            Entry entry = new Entry();
            ListTag recent = activity.getList("recent", Tag.TAG_COMPOUND);
            for (int i = 0; i < recent.size(); i++) {
                CompoundTag r = recent.getCompound(i);
                List<UUID> players = new ArrayList<>();
                for (Tag player : r.getList("players", Tag.TAG_INT_ARRAY)) {
                    players.add(net.minecraft.nbt.NbtUtils.loadUUID(player));
                }
                RunStats.push(entry.recent, new Run(r.getLong("at"),
                        r.contains("difficulty", Tag.TAG_STRING) ? r.getString("difficulty") : null,
                        r.getBoolean("won"), r.getBoolean("forced"), r.getLong("millis"), r.getInt("wave"), players), RECENT);
            }
            CompoundTag stats = activity.getCompound("stats");
            for (String difficulty : stats.getAllKeys()) {
                CompoundTag d = stats.getCompound(difficulty);
                List<Long> clears = new ArrayList<>();
                ListTag stored = d.getList("clears", Tag.TAG_LONG);
                for (Tag clear : stored) {
                    clears.add(((LongTag) clear).getAsLong());
                }
                entry.stats.put(difficulty, new RunStats(d.getInt("runs"), d.getInt("wins"), d.getLong("best"),
                        d.getInt("wave"), clears));
            }
            data.entries.put(id, entry);
        }
        return data;
    }
}
