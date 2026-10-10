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
                        ProfileService.store(server, profile);
                        if (server.isDedicatedServer() && EdenLD.CONFIG.minestar.prime_role_sync_enabled) {
                            PrimeSync.sync(player, profile);
                        }
                        // Requests from players they ignore wait in the panel without a reminder.
                        int requests = (int) profile.incomingRequests().stream().filter(request ->
                                !net.ledok.eden_ld.hub.IgnoreList.ignores(server, player.getUUID(), request.uuid())).count();
                        if (requests > 0) {
                            server.execute(() -> notifyFriendRequests(player, requests));
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

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            ProfileCache.evict(handler.getPlayer().getUUID());
            FriendService.forget(handler.getPlayer().getUUID());
        });

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
