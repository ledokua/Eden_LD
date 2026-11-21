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
    /**
     * One announcement bigger than this (encoded) is left off the Announcements page (review finding 44): a stake of
     * nine full writable books made a single listing of megabytes, which used to empty the page for everyone.
     */
    private static final int MAX_LISTING_BYTES = 128 * 1024;
    /**
     * A player's {@link WagerAction.Action#VIEW} pushes their page at most this often; a VIEW sooner is answered once
     * the time is up (review finding 44: a modified client sent VIEW every tick, and every push was built anew).
     */
    private static final long VIEW_INTERVAL_MS = 500;
    /** When each player's next VIEW may push ({@link net.minecraft.Util#getMillis}), and who's waiting for it. */
    private static final Map<UUID, Long> NEXT_VIEW = new ConcurrentHashMap<>();
    private static final java.util.Set<UUID> VIEW_LATER = ConcurrentHashMap.newKeySet();
    /**
     * Encoded sizes of the page's parts, measured once and kept until Duels reports any wager change (review finding
     * 44: pages were encoded on the server thread just to measure them, again on every push). Keys: {@code l:<number>}
     * an announcement, {@code m:<player>} a player's My duels, {@code h:<player>:<number>} a history row.
     */
    private static final Map<String, Integer> SIZES = new ConcurrentHashMap<>();
    private static final int MAX_SIZES = 4096;
    /** Whether leaving parts out was logged since the sizes were last cleared (once per change, not per push). */
    private static volatile boolean warned;
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
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> net.ledok.eden_ld.util.OnServerThread.run(server, () -> {
            VIEWERS.remove(handler.getPlayer().getUUID());
            PENDING.remove(handler.getPlayer().getUUID());
            NEXT_VIEW.remove(handler.getPlayer().getUUID());
            VIEW_LATER.remove(handler.getPlayer().getUUID());
        }));
        ServerTickEvents.END_SERVER_TICK.register(WagersHub::tick);
    }

    private static void handle(ServerPlayer player, WagerAction action) {
        UUID uuid = player.getUUID();
        switch (action.action()) {
            case VIEW -> {
                int page = Math.max(ANNOUNCEMENTS, Math.min(HISTORY, action.id()));
                VIEWERS.put(uuid, page);
                long now = net.minecraft.Util.getMillis();
                Long next = NEXT_VIEW.get(uuid);
                if (next == null || now >= next) {
                    NEXT_VIEW.put(uuid, now + VIEW_INTERVAL_MS);
                    VIEW_LATER.remove(uuid);
                    queue(uuid, parts(page));
                } else {
                    // Too soon: the page (the last one asked for) goes when the interval is up (tick).
                    VIEW_LATER.add(uuid);
                }
            }
            case LEAVE -> {
                VIEWERS.remove(uuid);
                VIEW_LATER.remove(uuid);
            }
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
        clearSizes();
        Integer page = VIEWERS.get(player);
        if (page != null) {
            queue(player, parts(page));
        }
    }

    private static void listingsChanged() {
        clearSizes();
        VIEWERS.forEach((player, page) -> {
            if (page == ANNOUNCEMENTS) {
                queue(player, WagerData.LISTINGS);
            }
        });
    }

    /** Any wager changed: the measured sizes may be stale (a stake, an offer, a new history row). */
    private static void clearSizes() {
        SIZES.clear();
        warned = false;
    }

    /**
     * Stacks travel whole (their tooltips are shown), and a packet over the client's limit would kick the player: the
     * page is filled in order within {@link #MAX_BYTES}, leaving out each announcement or history row that doesn't fit
     * (an announcement over {@link #MAX_LISTING_BYTES} never does) and, should even that not do, My duels. Each part's
     * size is measured once per wager change ({@link #SIZES}), not by encoding the whole page again and again on the
     * server thread, and one heavy listing no longer cuts the ones after it (review finding 44).
     */
    private static WagerData fit(MinecraftServer server, UUID viewer, WagerData data) {
        int total = size(server, new WagerData(0, data.enabled(), data.actions(), data.skipLossWarning(), data.wins(),
                data.losses(), data.draws(), List.of(), HubPayloads.WagerMineView.EMPTY, List.of()));
        HubPayloads.WagerMineView mine = data.mine();
        boolean cut = false;
        if (data.has(WagerData.MINE)) {
            int bytes = measured("m:" + viewer, () -> partSize(server, WagerData.MINE, List.of(), data.mine(), List.of()));
            if (total + bytes > MAX_BYTES) {
                mine = HubPayloads.WagerMineView.EMPTY;
                cut = true;
            } else {
                total += bytes;
            }
        }
        List<HubPayloads.WagerListingView> listings = data.listings();
        if (data.has(WagerData.LISTINGS)) {
            listings = new java.util.ArrayList<>();
            for (HubPayloads.WagerListingView listing : data.listings()) {
                int bytes = measured("l:" + listing.number(),
                        () -> partSize(server, WagerData.LISTINGS, List.of(listing), HubPayloads.WagerMineView.EMPTY, List.of()));
                if (bytes > MAX_LISTING_BYTES || total + bytes > MAX_BYTES) {
                    cut = true;
                    continue;
                }
                total += bytes;
                listings.add(listing);
            }
        }
        List<HubPayloads.WagerRecordView> history = data.history();
        if (data.has(WagerData.HISTORY)) {
            history = new java.util.ArrayList<>();
            for (HubPayloads.WagerRecordView row : data.history()) {
                int bytes = measured("h:" + viewer + ":" + row.number(),
                        () -> partSize(server, WagerData.HISTORY, List.of(), HubPayloads.WagerMineView.EMPTY, List.of(row)));
                if (total + bytes > MAX_BYTES) {
                    cut = true;
                    continue;
                }
                total += bytes;
                history.add(row);
            }
        }
        if (!cut) {
            return data;
        }
        if (!warned) {
            warned = true;
            EdenLD.LOGGER.warn("[Duels] The wager pages were too big to send whole: {} of {} announcements, {} of {} history rows{}.",
                    listings.size(), data.listings().size(), history.size(), data.history().size(),
                    mine != data.mine() ? ", My duels left out" : "");
        }
        return new WagerData(data.parts(), data.enabled(), data.actions(), data.skipLossWarning(), data.wins(),
                data.losses(), data.draws(), List.copyOf(listings), mine, List.copyOf(history));
    }

    /** A size from {@link #SIZES}, measured now when it isn't there. */
    private static int measured(String key, java.util.function.IntSupplier measure) {
        Integer known = SIZES.get(key);
        if (known != null) {
            return known;
        }
        int bytes = measure.getAsInt();
        if (SIZES.size() >= MAX_SIZES) {
            SIZES.clear();
        }
        SIZES.put(key, bytes);
        return bytes;
    }

    /** What one part's content adds to a page: the page with only it, less the same page with it empty. */
    private static int partSize(MinecraftServer server, int part, List<HubPayloads.WagerListingView> listings,
                                HubPayloads.WagerMineView mine, List<HubPayloads.WagerRecordView> history) {
        WagerData with = new WagerData(part, false, 0, false, 0, 0, 0, listings, mine, history);
        WagerData without = new WagerData(part, false, 0, false, 0, 0, 0, List.of(), HubPayloads.WagerMineView.EMPTY, List.of());
        return Math.max(0, size(server, with) - size(server, without));
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

    private static void tick(MinecraftServer server) {
        if (!VIEW_LATER.isEmpty()) {
            long now = net.minecraft.Util.getMillis();
            VIEW_LATER.removeIf(uuid -> {
                Long next = NEXT_VIEW.get(uuid);
                if (next != null && now < next) {
                    return false;
                }
                Integer page = VIEWERS.get(uuid);
                if (page != null) {
                    NEXT_VIEW.put(uuid, now + VIEW_INTERVAL_MS);
                    queue(uuid, parts(page));
                }
                return true;
            });
        }
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
                    ServerPlayNetworking.send(player, fit(server, uuid, data));
                }
            } catch (RuntimeException e) {
                // A Duels_LD fault must not break the tick (or the next player's push).
                EdenLD.LOGGER.warn("[Duels] Couldn't build the wager pages for {}", player.getGameProfile().getName(), e);
            }
        }
    }
}
