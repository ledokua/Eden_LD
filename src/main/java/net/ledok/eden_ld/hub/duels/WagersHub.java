package net.ledok.eden_ld.hub.duels;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.ledok.eden_ld.EdenLD;
import net.ledok.eden_ld.compat.duels.DuelsHook;
import net.ledok.eden_ld.hub.HubPayloads;
import net.ledok.eden_ld.hub.HubPayloads.WagerAction;
import net.ledok.eden_ld.hub.HubPayloads.WagerData;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server side of the hub's wager pages (Activities → Duels: Announcements, My duels, History; Duels_LD 1.6.0+). The
 * client says which page it shows ({@link WagerAction.Action#VIEW}); Duels' wager events push that page again — a
 * player's own change to them, a public announcement's change to everyone on Announcements — and My duels refreshes
 * every few seconds while shown (the challengers' online dots). Pushes are batched to the end of the tick, so a player
 * gets at most one {@link WagerData} per tick. A fresh client gets the header on join (whether wager duels are on),
 * which shows the Duels filter.
 * <p>
 * Nothing is registered without the wager API: the client can't send {@link WagerAction}, so it hides the page.
 */
public final class WagersHub {

    /** My duels' refresh while it's shown (who's online changes without a wager event). */
    private static final int REFRESH_TICKS = 100;
    private static final int ANNOUNCEMENTS = 0, MINE = 1, HISTORY = 2;
    /** Well under the client's 1 MiB limit for a custom payload. */
    private static final int MAX_BYTES = 768 * 1024;
    /** Who has a wager page open → which one. */
    private static final Map<UUID, Integer> VIEWERS = new ConcurrentHashMap<>();
    /** Players owed a push → the parts ({@link WagerData#LISTINGS} …; 0 = the header alone), sent at the tick's end. */
    private static final Map<UUID, Integer> PENDING = new ConcurrentHashMap<>();

    private WagersHub() {
    }

    public static void initialize() {
        if (!DuelsHook.wagers()) {
            if (DuelsHook.LOADED) {
                EdenLD.LOGGER.info("[Duels] This Duels_LD has no wager API (1.6.0+): the hub's Duels page stays hidden.");
            }
            return;
        }
        DuelsHook.initializeWagers(WagersHub::playerChanged, WagersHub::listingsChanged);
        ServerPlayNetworking.registerGlobalReceiver(WagerAction.TYPE, (payload, context) -> handle(context.player(), payload));
        // The header alone: the client learns whether wager duels are on (the Duels filter).
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> server.execute(() -> queue(handler.getPlayer().getUUID(), 0)));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            VIEWERS.remove(handler.getPlayer().getUUID());
            PENDING.remove(handler.getPlayer().getUUID());
        });
        ServerTickEvents.END_SERVER_TICK.register(WagersHub::tick);
    }

    private static void handle(ServerPlayer player, WagerAction action) {
        UUID uuid = player.getUUID();
        switch (action.action()) {
            case VIEW -> {
                int page = Math.max(ANNOUNCEMENTS, Math.min(HISTORY, action.id()));
                VIEWERS.put(uuid, page);
                queue(uuid, parts(page));
            }
            case LEAVE -> VIEWERS.remove(uuid);
            default -> {
                HubPayloads.HubNotice notice;
                try {
                    notice = DuelsHook.wagerAction(player, action);
                } catch (RuntimeException e) {
                    EdenLD.LOGGER.warn("[Duels] Wager action {} failed for {}", action.action(), player.getGameProfile().getName(), e);
                    notice = new HubPayloads.HubNotice(net.minecraft.network.chat.Component.translatable("message.eden_ld.wagers.failed"));
                }
                if (notice != null && ServerPlayNetworking.canSend(player, HubPayloads.HubNotice.TYPE)) {
                    ServerPlayNetworking.send(player, notice);
                }
                // The page the player looks at, fresh (Duels' events usually follow too: one push per tick either way).
                Integer page = VIEWERS.get(uuid);
                if (page != null) {
                    queue(uuid, parts(page));
                }
            }
        }
    }

    /** What a page needs besides the header. */
    private static int parts(int page) {
        return switch (page) {
            case MINE -> WagerData.MINE;
            case HISTORY -> WagerData.HISTORY;
            default -> WagerData.LISTINGS;
        };
    }

    private static void queue(UUID player, int parts) {
        PENDING.merge(player, parts, (a, b) -> a | b);
    }

    /** A player's wagers changed: their open page follows (Announcements too — their own announcement is pinned there). */
    private static void playerChanged(UUID player) {
        Integer page = VIEWERS.get(player);
        if (page != null) {
            queue(player, parts(page));
        }
    }

    private static void listingsChanged() {
        VIEWERS.forEach((player, page) -> {
            if (page == ANNOUNCEMENTS) {
                queue(player, WagerData.LISTINGS);
            }
        });
    }

    /**
     * Stacks travel whole (their tooltips are shown), and a packet over the client's limit would kick the player: a
     * page that big (many announcements or a long history of heavy items) loses its tail halves until it fits.
     */
    private static WagerData fit(MinecraftServer server, WagerData data) {
        WagerData out = data;
        for (int i = 0; i < 10 && size(server, out) > MAX_BYTES; i++) {
            out = new WagerData(out.parts(), out.enabled(), out.actions(), out.skipLossWarning(), out.wins(), out.losses(),
                    out.draws(), half(out.listings()), out.mine(), half(out.history()));
        }
        if (out != data) {
            EdenLD.LOGGER.warn("[Duels] The wager pages were too big to send whole: {} of {} announcements, {} of {} history rows.",
                    out.listings().size(), data.listings().size(), out.history().size(), data.history().size());
        }
        return out;
    }

    private static int size(MinecraftServer server, WagerData data) {
        net.minecraft.network.RegistryFriendlyByteBuf buf = new net.minecraft.network.RegistryFriendlyByteBuf(
                io.netty.buffer.Unpooled.buffer(), server.registryAccess());
        try {
            WagerData.CODEC.encode(buf, data);
            return buf.readableBytes();
        } finally {
            buf.release();
        }
    }

    private static <T> List<T> half(List<T> list) {
        return list.size() <= 1 ? List.of() : List.copyOf(list.subList(0, list.size() / 2));
    }

    private static void tick(MinecraftServer server) {
        if (server.getTickCount() % REFRESH_TICKS == 0) {
            VIEWERS.forEach((player, page) -> {
                if (page == MINE) {
                    queue(player, WagerData.MINE);
                }
            });
        }
        if (PENDING.isEmpty()) {
            return;
        }
        for (UUID uuid : List.copyOf(PENDING.keySet())) {
            Integer parts = PENDING.remove(uuid);
            ServerPlayer player = server.getPlayerList().getPlayer(uuid);
            if (parts == null || player == null || player.hasDisconnected()
                    || !ServerPlayNetworking.canSend(player, WagerData.TYPE)) {
                if (player == null) {
                    VIEWERS.remove(uuid);
                }
                continue;
            }
            try {
                WagerData data = DuelsHook.wagerData(player, parts);
                if (data != null) {
                    ServerPlayNetworking.send(player, fit(server, data));
                }
            } catch (RuntimeException e) {
                // A Duels_LD fault must not break the tick (or the next player's push).
                EdenLD.LOGGER.warn("[Duels] Couldn't build the wager pages for {}", player.getGameProfile().getName(), e);
            }
        }
    }
}
