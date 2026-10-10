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
        long time = keepTimes && old != null ? old.time() : System.currentTimeMillis();
        chunks.put(key, new Entry(owner, time));
        setDirty();
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
