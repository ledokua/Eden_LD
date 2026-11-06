package net.ledok.eden_ld.world;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Blocks a player's game may have wrong after the server refused (or changed) their click or break: their state and,
 * with a block entity, its data are sent to that player again once the server has answered the client's prediction.
 * The client predicts the action; the server's acknowledgement (sent from the connection's tick) puts back the block
 * states it had before, but a block entity the prediction removed comes back empty, and data a mod changed on the client
 * stays. So the re-send goes out at the end of a later tick than the action, after the acknowledgement: the same block
 * state and data a relog would bring. Only the acting player gets it, and not after they changed dimension.
 */
public final class BlockResync {

    private record Pending(ResourceKey<Level> dimension, int queuedAt, Set<BlockPos> positions) {
    }

    private static final Map<UUID, Pending> PENDING = new HashMap<>();

    private BlockResync() {
    }

    public static void initialize() {
        ServerTickEvents.END_SERVER_TICK.register(BlockResync::flush);
    }

    /** {@code pos} is re-sent to {@code player} after the server's answer to their action. */
    public static void queue(ServerPlayer player, BlockPos pos) {
        ResourceKey<Level> dimension = player.serverLevel().dimension();
        int tick = player.server.getTickCount();
        Pending pending = PENDING.get(player.getUUID());
        if (pending == null || !pending.dimension().equals(dimension)) {
            pending = new Pending(dimension, tick, new LinkedHashSet<>());
            PENDING.put(player.getUUID(), pending);
        } else if (pending.queuedAt() != tick) {
            pending = new Pending(dimension, tick, pending.positions());
            PENDING.put(player.getUUID(), pending);
        }
        pending.positions().add(pos.immutable());
    }

    private static void flush(MinecraftServer server) {
        if (PENDING.isEmpty()) {
            return;
        }
        int now = server.getTickCount();
        for (Iterator<Map.Entry<UUID, Pending>> it = PENDING.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Pending> entry = it.next();
            Pending pending = entry.getValue();
            // Queued this tick: its acknowledgement may not have gone out yet.
            if (pending.queuedAt() >= now) {
                continue;
            }
            it.remove();
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player == null || !player.serverLevel().dimension().equals(pending.dimension())) {
                continue;
            }
            ServerLevel level = player.serverLevel();
            for (BlockPos pos : pending.positions()) {
                if (!level.isLoaded(pos)) {
                    continue;
                }
                player.connection.send(new ClientboundBlockUpdatePacket(level, pos));
                BlockEntity blockEntity = level.getBlockEntity(pos);
                if (blockEntity != null) {
                    Packet<ClientGamePacketListener> data = blockEntity.getUpdatePacket();
                    // No update packet: send what a chunk load would (an empty tag changes nothing on the client).
                    player.connection.send(data != null ? data : ClientboundBlockEntityDataPacket.create(blockEntity));
                }
            }
        }
    }
}
