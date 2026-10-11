package net.ledok.eden_ld.minestar;

import net.ledok.eden_ld.EdenLD;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import ua.com.minestar.model.ShopProduct;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static ua.com.minestar.Minestar.minestar;

/**
 * Delivery of pending platform shop purchases: the Mail tab's purchase entries (one, or Claim all) and
 * {@code /shop-receive}.
 * API calls stay on Minestar's executor; product commands are executed
 * on the server thread, and only successfully delivered products get
 * confirmed back to the platform.
 */
final class ShopClaim {

    private ShopClaim() {
    }

    /** The purchases waiting for the player (Home card, Mail tab); always answers once, on the server thread. */
    static void pending(ServerPlayer player,
                        java.util.function.Consumer<List<net.ledok.eden_ld.hub.HubPayloads.ShopPurchase>> onPurchases) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            onPurchases.accept(List.of());
            return;
        }
        minestar.getUserPendingShopProductsByProfileUuidAndServerId(player.getUUID())
                .onSuccess(products -> {
                    List<net.ledok.eden_ld.hub.HubPayloads.ShopPurchase> purchases = products.stream()
                            .map(product -> new net.ledok.eden_ld.hub.HubPayloads.ShopPurchase(product.id(),
                                    product.name() == null ? "" : product.name(), product.quantity()))
                            .toList();
                    server.execute(() -> onPurchases.accept(purchases));
                })
                .onFailure(cause -> {
                    EdenLD.LOGGER.warn("[Shop] Failed to count pending products for {}.",
                            player.getGameProfile().getName(), cause);
                    server.execute(() -> onPurchases.accept(List.of()));
                });
    }

    static void notifyOnJoin(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }
        minestar.getUserPendingShopProductsByProfileUuidAndServerId(player.getUUID())
                .onSuccess(products -> {
                    if (!products.isEmpty()) {
                        Component message = Component.translatable("message.eden_ld.shop.unclaimed_items", products.size())
                                .withStyle(ChatFormatting.GOLD)
                                .append(Component.translatable("message.eden_ld.shop.claim_button")
                                        .withStyle(ChatFormatting.AQUA, ChatFormatting.UNDERLINE)
                                        .withStyle(style -> style.withClickEvent(
                                                new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/hub mail"))))
                                .append(Component.translatable("message.eden_ld.shop.claim_suffix").withStyle(ChatFormatting.GOLD));
                        server.execute(() -> player.sendSystemMessage(message));
                    }
                })
                .onFailure(cause -> EdenLD.LOGGER.error("[Shop] Failed to check pending products on join for {}.",
                        player.getGameProfile().getName(), cause));
    }

    /** Delivers the purchase with this id, or every waiting one ({@code 0}). */
    static void claim(ServerPlayer player, long productId) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }
        player.sendSystemMessage(Component.translatable("message.eden_ld.shop.checking").withStyle(ChatFormatting.GRAY));

        minestar.getUserPendingShopProductsByProfileUuidAndServerId(player.getUUID())
                .onSuccess(all -> server.execute(() -> {
                    List<ShopProduct> products = productId == 0 ? all
                            : all.stream().filter(product -> product.id() == productId).toList();
                    if (products.isEmpty()) {
                        player.sendSystemMessage(Component.translatable("message.eden_ld.shop.no_items").withStyle(ChatFormatting.YELLOW));
                        return;
                    }
                    player.sendSystemMessage(Component.translatable("message.eden_ld.shop.found_items", products.size()).withStyle(ChatFormatting.GREEN));
                    deliver(server, player, products);
                }))
                .onFailure(cause -> {
                    server.execute(() -> player.sendSystemMessage(
                            Component.translatable("message.eden_ld.shop.error").withStyle(ChatFormatting.RED)));
                    EdenLD.LOGGER.error("[Shop] Failed to get pending products for {}.",
                            player.getGameProfile().getName(), cause);
                });
    }

    private static void deliver(MinecraftServer server, ServerPlayer player, List<ShopProduct> products) {
        String playerName = player.getGameProfile().getName();
        Set<Long> deliveredProductIds = new HashSet<>();

        for (ShopProduct product : products) {
            boolean successful = true;
            outer:
            for (int i = 0; i < product.quantity(); i++) {
                // Since minestar 1.3.0, {player} and {player_uuid} arrive already substituted.
                for (String command : product.commands()) {
                    try {
                        CommandSourceStack source = server.createCommandSourceStack();
                        server.getCommands().performPrefixedCommand(source, command);
                        EdenLD.LOGGER.info("[Shop] Executed command for product {} ({}/{}): {}",
                                product.id(), i + 1, product.quantity(), command);
                    } catch (Exception e) {
                        successful = false;
                        EdenLD.LOGGER.error("[Shop] Failed to execute a command for product {}.", product.id(), e);
                        break outer;
                    }
                }
            }
            if (successful) {
                deliveredProductIds.add(product.id());
            }
        }

        if (!deliveredProductIds.isEmpty()) {
            minestar.deleteUserPendingShopProductsByIds(deliveredProductIds)
                    .onSuccess(unit -> EdenLD.LOGGER.info("[Shop] Confirmed delivery of products [{}] for {}.",
                            deliveredProductIds.stream().map(String::valueOf).collect(Collectors.joining(", ")), playerName))
                    .onFailure(cause -> EdenLD.LOGGER.error("[Shop] Failed to confirm delivery for {}.", playerName, cause));
        }
    }
}
