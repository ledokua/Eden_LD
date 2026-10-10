package net.ledok.eden_ld.client.chat;

import net.ledok.eden_ld.hub.HubPayloads;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.world.level.GameType;

import java.util.Comparator;
import java.util.Map;
import java.util.UUID;

/**
 * The server's player ranks for the tab list ({@link HubPayloads.TabOrder}): spectators stay
 * last as in vanilla, then higher ranks first, then vanilla's order (team, name).
 */
public final class ClientTabList {

    private static volatile Map<UUID, Integer> weights = Map.of();

    private ClientTabList() {
    }

    public static void onOrder(HubPayloads.TabOrder order) {
        weights = Map.copyOf(order.weights());
    }

    public static void clear() {
        weights = Map.of();
    }

    public static Comparator<PlayerInfo> comparator(Comparator<PlayerInfo> vanilla) {
        Map<UUID, Integer> ranks = weights;
        if (ranks.isEmpty()) {
            return vanilla;
        }
        return Comparator.<PlayerInfo>comparingInt(info -> info.getGameMode() == GameType.SPECTATOR ? 1 : 0)
                .thenComparingInt(info -> -ranks.getOrDefault(info.getProfile().getId(), 0))
                .thenComparing(vanilla);
    }
}
