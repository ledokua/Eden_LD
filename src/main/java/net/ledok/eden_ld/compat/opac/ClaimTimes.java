package net.ledok.eden_ld.compat.opac;

import net.ledok.eden_ld.EdenData;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * When each chunk got its current OPAC claim owner. OPAC doesn't record this, so Eden listens
 * to its claim changes (see {@link OpacHook}); trimming a guild's land uses it to release the
 * newest (or, by config, oldest) chunks first. Chunks claimed before Eden started listening
 * have no time and count as the oldest.
 */
final class ClaimTimes extends SavedData {

    private static final Factory<ClaimTimes> FACTORY = new Factory<>(ClaimTimes::new, ClaimTimes::load, null);

    private record Entry(UUID owner, long time) {
    }

    private final Map<ResourceLocation, Map<Long, Entry>> byDimension = new HashMap<>();
    /** Set while Eden re-owns chunks itself (leadership transfer): the new owner keeps the old time. */
    static boolean keepTimes;

    @Nullable
    static ClaimTimes get(MinecraftServer server) {
        return server.overworld() == null ? null : EdenData.get(server, FACTORY, "claim_times");
    }

    private static long key(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    /** A claim changed: a new owner starts a new time; the same owner (forceload toggle etc.) keeps it. */
    void onChange(ResourceLocation dimension, int x, int z, @Nullable UUID owner) {
        long key = key(x, z);
        if (owner == null) {
            Map<Long, Entry> chunks = byDimension.get(dimension);
            if (chunks != null && chunks.remove(key) != null) {
                setDirty();
            }
            return;
        }
        Map<Long, Entry> chunks = byDimension.computeIfAbsent(dimension, d -> new HashMap<>());
        Entry old = chunks.get(key);
        if (old != null && old.owner().equals(owner)) {
            return;
        }
        // A chunk Eden re-owns itself (leadership move) keeps its time; one with no recorded time stays "unknown = oldest"
        // (0) instead of becoming the newest, so a later trim no longer releases the guild's old base first (review
        // finding 13).
        long time = keepTimes ? (old != null ? old.time() : 0L) : System.currentTimeMillis();
        chunks.put(key, new Entry(owner, time));
        setDirty();
    }

    /**
     * Once per server start (review finding 13): OPAC loads its claims from disk without telling listeners, so chunks
     * claimed before Eden listened (or whose entry was lost) get {@code (owner, 0)} — "unknown = oldest" — and entries
     * for chunks OPAC no longer has claimed are dropped. With every claimed chunk known, a same-owner re-claim (forceload
     * toggle, sub-config change) hits the same-owner early return in {@link #onChange} instead of being stamped "now".
     */
    void seed(java.util.stream.Stream<xaero.pac.common.server.claims.player.api.IServerPlayerClaimInfoAPI> owners) {
        Map<ResourceLocation, java.util.Set<Long>> claimed = new HashMap<>();
        int[] added = {0};
        owners.forEach(info -> {
            UUID owner = info.getPlayerId();
            info.getStream().forEach(dimension -> {
                Map<Long, Entry> chunks = byDimension.computeIfAbsent(dimension.getKey(), d -> new HashMap<>());
                java.util.Set<Long> seen = claimed.computeIfAbsent(dimension.getKey(), d -> new java.util.HashSet<>());
                dimension.getValue().getStream().forEach(posList -> posList.getStream().forEach(pos -> {
                    long key = key(pos.x, pos.z);
                    seen.add(key);
                    Entry old = chunks.get(key);
                    if (old == null || !old.owner().equals(owner)) {
                        chunks.put(key, new Entry(owner, 0L));
                        added[0]++;
                    }
                }));
            });
        });
        int[] dropped = {0};
        byDimension.forEach((dimension, chunks) -> {
            java.util.Set<Long> seen = claimed.getOrDefault(dimension, java.util.Set.of());
            int before = chunks.size();
            chunks.keySet().removeIf(key -> !seen.contains(key));
            dropped[0] += before - chunks.size();
        });
        if (added[0] > 0 || dropped[0] > 0) {
            setDirty();
            net.ledok.eden_ld.EdenLD.LOGGER.info("[Claims] Claim times: {} claimed chunks with no recorded time counted "
                    + "as the oldest; {} stale entries dropped.", added[0], dropped[0]);
        }
    }

    /** When {@code owner} claimed the chunk; 0 (oldest) when unknown. */
    long time(ResourceLocation dimension, int x, int z, UUID owner) {
        Map<Long, Entry> chunks = byDimension.get(dimension);
        Entry entry = chunks == null ? null : chunks.get(key(x, z));
        return entry != null && entry.owner().equals(owner) ? entry.time() : 0;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag dimensions = new ListTag();
        byDimension.forEach((dimension, chunks) -> {
            CompoundTag dim = new CompoundTag();
            dim.putString("id", dimension.toString());
            ListTag list = new ListTag();
            chunks.forEach((key, entry) -> {
                CompoundTag chunk = new CompoundTag();
                chunk.putLong("pos", key);
                chunk.putUUID("owner", entry.owner());
                chunk.putLong("time", entry.time());
                list.add(chunk);
            });
            dim.put("chunks", list);
            dimensions.add(dim);
        });
        tag.put("dimensions", dimensions);
        return tag;
    }

    private static ClaimTimes load(CompoundTag tag, HolderLookup.Provider registries) {
        ClaimTimes times = new ClaimTimes();
        ListTag dimensions = tag.getList("dimensions", Tag.TAG_COMPOUND);
        for (int i = 0; i < dimensions.size(); i++) {
            CompoundTag dim = dimensions.getCompound(i);
            ResourceLocation id = ResourceLocation.tryParse(dim.getString("id"));
            if (id == null) {
                continue;
            }
            Map<Long, Entry> chunks = times.byDimension.computeIfAbsent(id, d -> new HashMap<>());
            ListTag list = dim.getList("chunks", Tag.TAG_COMPOUND);
            for (int j = 0; j < list.size(); j++) {
                CompoundTag chunk = list.getCompound(j);
                chunks.put(chunk.getLong("pos"), new Entry(chunk.getUUID("owner"), chunk.getLong("time")));
            }
        }
        return times;
    }
}
