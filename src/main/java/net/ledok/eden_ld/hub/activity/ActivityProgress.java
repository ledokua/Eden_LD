package net.ledok.eden_ld.hub.activity;

import net.ledok.eden_ld.hub.HubData;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Per-player activity data in {@link HubData}, under {@code activities}: {@code stats}
 * ({@code id → difficulty (or "_") → {clears, best}} plus {@code id → wave}), and {@code seen} —
 * the activities whose unlock the player has seen (the rest of the open ones are "new"). Server thread.
 */
public final class ActivityProgress {

    private static final String TAG = "activities";
    private static final String STATS = "stats";
    private static final String SEEN = "seen";
    private static final String NO_DIFFICULTY = "_";

    /** Clears and best time (ms, -1 never timed) of one difficulty. */
    public record Stats(int clears, long bestMillis) {
        public static final Stats NONE = new Stats(0, -1);
    }

    private ActivityProgress() {
    }

    private static CompoundTag read(MinecraftServer server, UUID player) {
        return HubData.get(server).peek(player).getCompound(TAG);
    }

    private static CompoundTag write(MinecraftServer server, UUID player) {
        CompoundTag root = HubData.get(server).player(player);
        if (!root.contains(TAG, Tag.TAG_COMPOUND)) {
            root.put(TAG, new CompoundTag());
        }
        return root.getCompound(TAG);
    }

    private static String key(@Nullable String difficulty) {
        return difficulty == null || difficulty.isBlank() ? NO_DIFFICULTY : difficulty;
    }

    public static Stats stats(MinecraftServer server, UUID player, ResourceLocation id, @Nullable String difficulty) {
        CompoundTag entry = read(server, player).getCompound(STATS).getCompound(id.toString()).getCompound(key(difficulty));
        return entry.isEmpty() ? Stats.NONE
                : new Stats(entry.getInt("clears"), entry.contains("best") ? entry.getLong("best") : -1);
    }

    /** All clears of the activity, over every difficulty. */
    public static int totalClears(MinecraftServer server, UUID player, ResourceLocation id) {
        CompoundTag activity = read(server, player).getCompound(STATS).getCompound(id.toString());
        int total = 0;
        for (String difficulty : activity.getAllKeys()) {
            if (activity.get(difficulty) instanceof CompoundTag entry) {
                total += entry.getInt("clears");
            }
        }
        return total;
    }

    public static int bestWave(MinecraftServer server, UUID player, ResourceLocation id) {
        return read(server, player).getCompound(STATS).getCompound(id.toString()).getInt("wave");
    }

    /** A finished run: a win counts a clear (and maybe a best time); arenas keep their best wave either way. */
    public static void record(MinecraftServer server, UUID player, ResourceLocation id, @Nullable String difficulty,
                              boolean won, long durationMillis, int wave) {
        CompoundTag root = write(server, player);
        CompoundTag stats = root.getCompound(STATS);
        CompoundTag activity = stats.getCompound(id.toString());
        if (won) {
            CompoundTag entry = activity.getCompound(key(difficulty));
            entry.putInt("clears", entry.getInt("clears") + 1);
            if (durationMillis > 0 && (!entry.contains("best") || durationMillis < entry.getLong("best"))) {
                entry.putLong("best", durationMillis);
            }
            activity.put(key(difficulty), entry);
        }
        if (wave > activity.getInt("wave")) {
            activity.putInt("wave", wave);
        }
        stats.put(id.toString(), activity);
        root.put(STATS, stats);
        HubData.get(server).setDirty();
    }

    /** Forgets the player's stats for one activity, or all (null). */
    public static void reset(MinecraftServer server, UUID player, @Nullable ResourceLocation id) {
        CompoundTag root = write(server, player);
        if (id == null) {
            root.remove(STATS);
        } else {
            root.getCompound(STATS).remove(id.toString());
        }
        HubData.get(server).setDirty();
    }

    public static Set<String> seen(MinecraftServer server, UUID player) {
        ListTag list = read(server, player).getList(SEEN, Tag.TAG_STRING);
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < list.size(); i++) {
            seen.add(list.getString(i));
        }
        return seen;
    }

    public static void markSeen(MinecraftServer server, UUID player, Set<String> ids) {
        Set<String> seen = seen(server, player);
        if (seen.containsAll(ids)) {
            return;
        }
        seen.addAll(ids);
        ListTag list = new ListTag();
        seen.stream().sorted().forEach(id -> list.add(StringTag.valueOf(id)));
        write(server, player).put(SEEN, list);
        HubData.get(server).setDirty();
    }
}
