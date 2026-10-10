package net.ledok.eden_ld.hub;

import net.ledok.eden_ld.EdenData;
import net.ledok.eden_ld.EdenLD;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Persistent per-player hub state (world-level {@link SavedData}). Later phases
 * put their data under a named sub-tag of the player's compound: dungeon unlocks,
 * daily quest progress, and so on. Parties and guilds get their own stores.
 */
public class HubData extends SavedData {

    private static final Factory<HubData> FACTORY = new Factory<>(HubData::new, HubData::load, null);

    private final Map<UUID, CompoundTag> players = new HashMap<>();

    public static HubData get(MinecraftServer server) {
        return EdenData.get(server, FACTORY, "hub");
    }

    /** The player's hub tag; created on first access. Mutations mark the store dirty. */
    public CompoundTag player(UUID uuid) {
        setDirty();
        return players.computeIfAbsent(uuid, id -> new CompoundTag());
    }

    /** Read-only peek that never creates or dirties. */
    public CompoundTag peek(UUID uuid) {
        CompoundTag tag = players.get(uuid);
        return tag == null ? new CompoundTag() : tag;
    }

    private static HubData load(CompoundTag tag, HolderLookup.Provider registries) {
        HubData data = new HubData();
        CompoundTag players = tag.getCompound("players");
        for (String key : players.getAllKeys()) {
            try {
                data.players.put(UUID.fromString(key), players.getCompound(key));
            } catch (IllegalArgumentException e) {
                EdenLD.LOGGER.warn("[Hub] Dropping hub data entry with invalid uuid '{}'.", key);
            }
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        CompoundTag playersTag = new CompoundTag();
        players.forEach((uuid, playerTag) -> playersTag.put(uuid.toString(), playerTag));
        tag.put("players", playersTag);
        return tag;
    }
}
