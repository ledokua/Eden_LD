package net.ledok.eden_ld.minestar;

import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.ledok.eden_ld.EdenLD;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import static ua.com.minestar.Minestar.minestar;

/**
 * Wires the Minestar platform into the server lifecycle.
 * One profile fetch per join feeds everything: the profile cache
 * (friends, group, prime — for Hub features), prime role sync,
 * the pending friend request nudge, and the shop claim notification.
 *
 * All callbacks run on Minestar's executor; anything touching game
 * state hops back to the server thread explicitly.
 */
final class MinestarIntegration {

    private MinestarIntegration() {
    }

    static void register() {
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayer player = handler.getPlayer();
            minestar.getUserProfileByProfileUuid(player.getUUID())
                    .onSuccess(profile -> {
                        // Runs on Minestar's executor. onFailure belongs to the fetch, so an exception here was
                        // swallowed silently: logged now (review finding 32).
                        try {
                            ProfileService.store(server, player, profile);
                            // PrimeSync only talks to LuckPerms' async API: fine off the server thread.
                            if (server.isDedicatedServer() && EdenLD.CONFIG.minestar.prime_role_sync_enabled) {
                                PrimeSync.sync(player, profile);
                            }
                            java.util.List<java.util.UUID> requesters = new java.util.ArrayList<>();
                            for (var request : profile.incomingRequests()) {
                                requesters.add(request.uuid());
                            }
                            if (!requesters.isEmpty()) {
                                // The ignore check reads HubData (world data, plain maps): on the server thread only
                                // (review finding 32).
                                net.ledok.eden_ld.util.OnServerThread.run(server, () -> {
                                    if (player.hasDisconnected()) {
                                        return;
                                    }
                                    // Requests from players they ignore wait in the panel without a reminder.
                                    int requests = (int) requesters.stream().filter(requester ->
                                            !net.ledok.eden_ld.hub.IgnoreList.ignores(server, player.getUUID(),
                                                    requester)).count();
                                    if (requests > 0) {
                                        notifyFriendRequests(player, requests);
                                    }
                                });
                            }
                        } catch (RuntimeException e) {
                            EdenLD.LOGGER.error("[Minestar] Handling the join profile of {} failed.",
                                    player.getGameProfile().getName(), e);
                        }
                    })
                    .onFailure(cause -> EdenLD.LOGGER.error("[Minestar] Failed to fetch profile for {}.",
                            player.getGameProfile().getName(), cause));

            if (EdenLD.CONFIG.minestar.shop_notify_on_join) {
                ShopClaim.notifyOnJoin(player);
            }
        });

        // Levels move while players play: their profiles are fetched again now and then.
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(ProfileService::tick);

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> net.ledok.eden_ld.util.OnServerThread.run(server, () -> {
            ProfileCache.evict(handler.getPlayer().getUUID());
            FriendService.forget(handler.getPlayer().getUUID());
        }));

        EdenLD.LOGGER.info("[Minestar] Platform integration initialized.");
    }

    private static void notifyFriendRequests(ServerPlayer player, int count) {
        if (player.hasDisconnected()) {
            return;
        }
        player.sendSystemMessage(Component.translatable("message.eden_ld.friends.pending_on_join", count)
                .withStyle(ChatFormatting.GOLD)
                .append(Component.translatable("message.eden_ld.party.invited_open")
                        .withStyle(ChatFormatting.AQUA, ChatFormatting.UNDERLINE)
                        .withStyle(style -> style.withClickEvent(
                                new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/hub")))));
    }
}
