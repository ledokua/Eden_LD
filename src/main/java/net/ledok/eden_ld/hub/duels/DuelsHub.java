package net.ledok.eden_ld.hub.duels;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.ledok.eden_ld.api.hub.EdenPartyApi;
import net.ledok.eden_ld.compat.duels.DuelsHook;
import net.ledok.eden_ld.hub.HubPayloads;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server side of the hub's Ranked PvP page (Duels_LD's ranked duels; the wager Duels pages are {@link WagersHub}):
 * the page's data, its queue buttons, and live pushes to whoever has it open — on queue changes, match starts and
 * ends, party changes, and every few seconds while they look (queue sizes and the search range move on their own).
 */
public final class DuelsHub {

    private static final int REFRESH_TICKS = 100;
    private static final Set<UUID> VIEWERS = ConcurrentHashMap.newKeySet();
    private static volatile boolean dirty;
    /** Players whose own queue state changed this tick (their Home card follows). */
    private static final Set<UUID> STATUS_CHANGED = ConcurrentHashMap.newKeySet();

    private DuelsHub() {
    }

    public static void initialize() {
        if (!DuelsHook.LOADED) {
            return;
        }
        DuelsHook.initialize(DuelsHub::changed, STATUS_CHANGED::add, DuelsHub::matchStarted);
        EdenPartyApi.addMembershipListener(new EdenPartyApi.MembershipListener() {
            @Override
            public void onJoin(UUID partyId, UUID player) {
                changed();
            }

            @Override
            public void onLeave(UUID partyId, UUID player) {
                changed();
            }
        });
        ServerPlayNetworking.registerGlobalReceiver(HubPayloads.DuelsView.TYPE, (payload, context) -> {
            ServerPlayer player = context.player();
            if (payload.open()) {
                VIEWERS.add(player.getUUID());
                send(player);
            } else {
                VIEWERS.remove(player.getUUID());
            }
        });
        ServerPlayNetworking.registerGlobalReceiver(HubPayloads.DuelsQueue.TYPE,
                (payload, context) -> queue(context.player(), payload.mode(), payload.join()));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> VIEWERS.remove(handler.getPlayer().getUUID()));
        // A fresh client knows nothing about the queue: tell it.
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> server.execute(() -> sendStatus(handler.getPlayer())));
        // Pushes are batched to the end of the tick: one event often touches several players.
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (!STATUS_CHANGED.isEmpty()) {
                for (UUID uuid : List.copyOf(STATUS_CHANGED)) {
                    STATUS_CHANGED.remove(uuid);
                    ServerPlayer player = server.getPlayerList().getPlayer(uuid);
                    if (player != null) {
                        sendStatus(player);
                    }
                }
            }
            if (VIEWERS.isEmpty()) {
                dirty = false;
                return;
            }
            if (dirty || server.getTickCount() % REFRESH_TICKS == 0) {
                dirty = false;
                pushViewers(server);
            }
        });
    }

    /**
     * Something duel-related changed: every open tab is pushed at the end of this tick, and who is dueling goes out
     * with the PvP list (client-side targeting follows the duel's teams).
     */
    private static void changed() {
        dirty = true;
        net.ledok.eden_ld.combat.PlayerPvp.playersChanged();
    }

    private static void pushViewers(MinecraftServer server) {
        for (UUID uuid : VIEWERS) {
            ServerPlayer player = server.getPlayerList().getPlayer(uuid);
            if (player == null) {
                VIEWERS.remove(uuid);
            } else {
                send(player);
            }
        }
    }

    private static void sendStatus(ServerPlayer player) {
        HubPayloads.DuelsStatus status = DuelsHook.status(player.getUUID());
        if (status != null && !player.hasDisconnected() && ServerPlayNetworking.canSend(player, HubPayloads.DuelsStatus.TYPE)) {
            ServerPlayNetworking.send(player, status);
        }
    }

    /** A match began: its players get the "match starting" alert (sound and a notice on screen). */
    private static void matchStarted(int mode, List<UUID> players) {
        net.minecraft.server.MinecraftServer server = net.ledok.eden_ld.EdenLD.server();
        if (server == null) {
            return;
        }
        for (UUID uuid : players) {
            ServerPlayer player = server.getPlayerList().getPlayer(uuid);
            if (player != null && ServerPlayNetworking.canSend(player, HubPayloads.DuelsAlert.TYPE)) {
                ServerPlayNetworking.send(player, new HubPayloads.DuelsAlert(mode));
            }
            STATUS_CHANGED.add(uuid);
        }
    }

    private static void send(ServerPlayer player) {
        HubPayloads.DuelsData data = DuelsHook.data(player);
        if (data != null && !player.hasDisconnected()) {
            ServerPlayNetworking.send(player, data);
        }
    }

    private static void queue(ServerPlayer player, int mode, boolean join) {
        if (join) {
            String refused = DuelsHook.join(player, mode == 1 ? 1 : 0);
            if (refused != null) {
                ServerPlayNetworking.send(player, new HubPayloads.HubNotice(
                        Component.translatable("message.eden_ld.duels.queue." + refused), false));
            }
        } else {
            DuelsHook.leave(player, mode);
        }
        send(player);
    }
}
