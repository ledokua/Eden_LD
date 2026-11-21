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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What Eden last did to OPAC, kept as world data (review finding 12): saved in the same pass as guilds.dat and hub.dat,
 * so it always matches Eden's own records, while OPAC saves its player configs and claims on its own timer.
 * <ul>
 * <li><b>expected</b>: the raw "bonus chunk claims" value of each player right after Eden last changed it, or as last
 * seen (outside changes — an admin's /openpac-player-config, the NPCs_LD bonus_claims action — are adopted once a
 * minute and at a clean stop). At start a different raw value means OPAC and Eden were saved at different moments, and
 * the raw value is put back to this one.</li>
 * <li><b>owed</b>: guild bonus OPAC refused to take back when a guild ended; retried until it does.</li>
 * <li><b>moves</b>: chunks a leadership move re-owned, kept until OPAC has surely saved them; at start, chunks OPAC still
 * has under the former owner move again.</li>
 * </ul>
 */
final class OpacBonusLedger extends SavedData {

    private static final Factory<OpacBonusLedger> FACTORY = new Factory<>(OpacBonusLedger::new, OpacBonusLedger::load, null);

    record MovedChunk(ResourceLocation dimension, int x, int z) {
    }

    record Move(UUID guild, UUID from, long at, List<MovedChunk> chunks) {
    }

    private final Map<UUID, Integer> expected = new HashMap<>();
    private final Map<UUID, Integer> owed = new HashMap<>();
    private final List<Move> moves = new ArrayList<>();

    @Nullable
    static OpacBonusLedger get(MinecraftServer server) {
        return server.overworld() == null ? null : EdenData.get(server, FACTORY, "opac_bonus");
    }

    /** The raw value Eden last left (or adopted) on {@code player}; null when Eden never changed it. */
    @Nullable
    Integer expected(UUID player) {
        return expected.get(player);
    }

    void set(UUID player, int raw) {
        Integer old = expected.put(player, raw);
        if (old == null || old != raw) {
            setDirty();
        }
    }

    /** A copy of every recorded player and value. */
    Map<UUID, Integer> all() {
        return Map.copyOf(expected);
    }

    void owe(UUID player, int amount) {
        owed.merge(player, amount, Integer::sum);
        setDirty();
    }

    Map<UUID, Integer> owed() {
        return Map.copyOf(owed);
    }

    void paid(UUID player) {
        if (owed.remove(player) != null) {
            setDirty();
        }
    }

    void addMove(Move move) {
        moves.add(move);
        setDirty();
    }

    List<Move> moves() {
        return List.copyOf(moves);
    }

    /** Every move counts from {@code now}: re-done at a start, they must last until OPAC has saved them again. */
    void restampMoves(long now) {
        if (moves.isEmpty()) {
            return;
        }
        List<Move> restamped = moves.stream().map(move -> new Move(move.guild(), move.from(), now, move.chunks())).toList();
        moves.clear();
        moves.addAll(restamped);
        setDirty();
    }

    /** Drops moves made before {@code before} (millis); null drops them all. */
    void dropMoves(@Nullable Long before) {
        if (moves.removeIf(move -> before == null || move.at() < before)) {
            setDirty();
        }
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        expected.forEach((player, raw) -> {
            CompoundTag entry = new CompoundTag();
            entry.putUUID("player", player);
            entry.putInt("raw", raw);
            list.add(entry);
        });
        tag.put("players", list);
        ListTag debts = new ListTag();
        owed.forEach((player, amount) -> {
            CompoundTag entry = new CompoundTag();
            entry.putUUID("player", player);
            entry.putInt("amount", amount);
            debts.add(entry);
        });
        tag.put("owed", debts);
        ListTag moveList = new ListTag();
        for (Move move : moves) {
            CompoundTag entry = new CompoundTag();
            entry.putUUID("guild", move.guild());
            entry.putUUID("from", move.from());
            entry.putLong("at", move.at());
            ListTag chunks = new ListTag();
            for (MovedChunk chunk : move.chunks()) {
                CompoundTag pos = new CompoundTag();
                pos.putString("dim", chunk.dimension().toString());
                pos.putInt("x", chunk.x());
                pos.putInt("z", chunk.z());
                chunks.add(pos);
            }
            entry.put("chunks", chunks);
            moveList.add(entry);
        }
        tag.put("moves", moveList);
        return tag;
    }

    private static OpacBonusLedger load(CompoundTag tag, HolderLookup.Provider registries) {
        OpacBonusLedger ledger = new OpacBonusLedger();
        ListTag list = tag.getList("players", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            if (entry.hasUUID("player")) {
                ledger.expected.put(entry.getUUID("player"), entry.getInt("raw"));
            }
        }
        ListTag debts = tag.getList("owed", Tag.TAG_COMPOUND);
        for (int i = 0; i < debts.size(); i++) {
            CompoundTag entry = debts.getCompound(i);
            if (entry.hasUUID("player")) {
                ledger.owed.put(entry.getUUID("player"), entry.getInt("amount"));
            }
        }
        ListTag moveList = tag.getList("moves", Tag.TAG_COMPOUND);
        for (int i = 0; i < moveList.size(); i++) {
            CompoundTag entry = moveList.getCompound(i);
            if (!entry.hasUUID("guild") || !entry.hasUUID("from")) {
                continue;
            }
            List<MovedChunk> chunks = new ArrayList<>();
            ListTag positions = entry.getList("chunks", Tag.TAG_COMPOUND);
            for (int j = 0; j < positions.size(); j++) {
                CompoundTag pos = positions.getCompound(j);
                ResourceLocation dimension = ResourceLocation.tryParse(pos.getString("dim"));
                if (dimension != null) {
                    chunks.add(new MovedChunk(dimension, pos.getInt("x"), pos.getInt("z")));
                }
            }
            ledger.moves.add(new Move(entry.getUUID("guild"), entry.getUUID("from"), entry.getLong("at"), chunks));
        }
        return ledger;
    }
}
