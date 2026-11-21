package net.ledok.eden_ld.minestar;

import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.context.ContextChain;
import net.ledok.eden_ld.EdenLD;
import net.ledok.eden_ld.hub.HubData;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import ua.com.minestar.model.ShopProduct;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import static ua.com.minestar.Minestar.minestar;

/**
 * Delivery of pending platform shop purchases: the Mail tab's purchase entries (one, or Claim all) and
 * {@code /shop-receive}. API calls stay on Minestar's executor; product commands run on the server thread.
 * <p>
 * Never twice (review findings 1 and 9, 1.3.130): one claim per player at a time, each with its own token, so a late
 * answer from a claim that timed out is dropped instead of delivering an old list. A delivered product is stored in
 * the player's hub data (saved at once) before its confirmation is sent, and stays there until a later platform list
 * no longer has it: while it does, a claim only retries the confirmation, never the commands, and the hub doesn't show
 * it as waiting.
 */
final class ShopClaim {

    /** Players with a claim in flight; from the platform lookup until it is confirmed or given up. */
    private static final Map<UUID, Claim> CLAIMING = new ConcurrentHashMap<>();
    private static final AtomicLong TOKENS = new AtomicLong();
    /** A claim the platform never answered stops blocking the next one after this long. */
    private static final long CLAIM_TIMEOUT_MS = 60_000;
    /** Per-player hub data: purchases delivered whose confirmation may not have reached the platform yet. */
    private static final String UNCONFIRMED = "shop_unconfirmed";

    private ShopClaim() {
    }

    /** One claim: its token (unique) and when it started (a claim the platform never answers stops blocking). */
    private record Claim(long token, long started) {
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
                    List<net.ledok.eden_ld.hub.HubPayloads.ShopPurchase> purchases;
                    try {
                        purchases = products.stream()
                                .map(product -> new net.ledok.eden_ld.hub.HubPayloads.ShopPurchase(product.id(),
                                        product.name() == null ? "" : product.name(), product.quantity()))
                                .toList();
                    } catch (RuntimeException e) {
                        // An odd answer must not swallow the callback (the hub's Home and Mail wait on it).
                        EdenLD.LOGGER.warn("[Shop] Couldn't read the pending products of {}.",
                                player.getGameProfile().getName(), e);
                        purchases = List.of();
                    }
                    List<net.ledok.eden_ld.hub.HubPayloads.ShopPurchase> answer = purchases;
                    server.execute(() -> {
                        // Delivered already, its confirmation not through yet: not waiting any more.
                        Set<Long> delivered = unconfirmed(server, player.getUUID());
                        retryConfirmations(delivered, answer.stream().map(net.ledok.eden_ld.hub.HubPayloads.ShopPurchase::id)
                                .collect(Collectors.toSet()), player.getGameProfile().getName());
                        onPurchases.accept(delivered.isEmpty() ? answer
                                : answer.stream().filter(purchase -> !delivered.contains(purchase.id())).toList());
                    });
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
                .onSuccess(products -> server.execute(() -> {
                    Set<Long> delivered = unconfirmed(server, player.getUUID());
                    retryConfirmations(delivered, products.stream().map(ShopProduct::id).collect(Collectors.toSet()),
                            player.getGameProfile().getName());
                    long waiting = products.stream().filter(product -> !delivered.contains(product.id())).count();
                    if (waiting > 0) {
                        player.sendSystemMessage(Component.translatable("message.eden_ld.shop.unclaimed_items", waiting)
                                .withStyle(ChatFormatting.GOLD)
                                .append(Component.translatable("message.eden_ld.shop.claim_button")
                                        .withStyle(ChatFormatting.AQUA, ChatFormatting.UNDERLINE)
                                        .withStyle(style -> style.withClickEvent(
                                                new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/hub mail"))))
                                .append(Component.translatable("message.eden_ld.shop.claim_suffix")
                                        .withStyle(ChatFormatting.GOLD)));
                    }
                }))
                .onFailure(cause -> EdenLD.LOGGER.error("[Shop] Failed to check pending products on join for {}.",
                        player.getGameProfile().getName(), cause));
    }

    /**
     * Purchases delivered but still on the platform's list (an earlier confirmation failed): asks again. Safe to
     * repeat, and needs no claim: the hub hides them, so the player has no reason to claim them again.
     */
    private static void retryConfirmations(Set<Long> unconfirmed, Set<Long> listed, String playerName) {
        Set<Long> again = new HashSet<>(unconfirmed);
        again.retainAll(listed);
        if (again.isEmpty()) {
            return;
        }
        String list = again.stream().map(String::valueOf).collect(Collectors.joining(", "));
        try {
            minestar.deleteUserPendingShopProductsByIds(again)
                    .onSuccess(unit -> EdenLD.LOGGER.info("[Shop] Confirmed delivery of products [{}] for {} (retry).",
                            list, playerName))
                    .onFailure(cause -> EdenLD.LOGGER.warn("[Shop] Still can't confirm delivery of [{}] for {}.", list,
                            playerName, cause));
        } catch (RuntimeException e) {
            EdenLD.LOGGER.warn("[Shop] Still can't confirm delivery of [{}] for {}.", list, playerName, e);
        }
    }

    /** Delivers the purchase with this id, or every waiting one ({@code 0}). */
    static void claim(ServerPlayer player, long productId) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }
        UUID uuid = player.getUUID();
        String name = player.getGameProfile().getName();
        Claim claim = new Claim(TOKENS.incrementAndGet(), System.currentTimeMillis());
        long token = claim.token();
        Claim held = CLAIMING.putIfAbsent(uuid, claim);
        if (held != null) {
            if (claim.started() - held.started() < CLAIM_TIMEOUT_MS || !CLAIMING.replace(uuid, held, claim)) {
                player.sendSystemMessage(Component.translatable("message.eden_ld.shop.busy").withStyle(ChatFormatting.YELLOW));
                return;
            }
            // The old claim's late answers carry its token and are dropped.
            EdenLD.LOGGER.warn("[Shop] {}'s previous claim got no answer in {} s; starting a new one.", name,
                    CLAIM_TIMEOUT_MS / 1000);
        }
        player.sendSystemMessage(Component.translatable("message.eden_ld.shop.checking").withStyle(ChatFormatting.GRAY));

        try {
            minestar.getUserPendingShopProductsByProfileUuidAndServerId(uuid)
                    .onSuccess(all -> server.execute(() -> {
                        try {
                            fetched(server, uuid, name, token, productId, all);
                        } catch (RuntimeException e) {
                            release(uuid, token);
                            throw e;
                        }
                    }))
                    .onFailure(cause -> {
                        boolean current = release(uuid, token);
                        EdenLD.LOGGER.error("[Shop] Failed to get pending products for {}.", name, cause);
                        if (current) {
                            server.execute(() -> {
                                ServerPlayer online = server.getPlayerList().getPlayer(uuid);
                                if (online != null) {
                                    online.sendSystemMessage(Component.translatable("message.eden_ld.shop.error")
                                            .withStyle(ChatFormatting.RED));
                                }
                            });
                        }
                    });
        } catch (RuntimeException e) {
            release(uuid, token);
            throw e;
        }
    }

    /** The platform's pending list for a claim, on the server thread. */
    private static void fetched(MinecraftServer server, UUID uuid, String name, long token, long productId,
                                List<ShopProduct> all) {
        Claim current = CLAIMING.get(uuid);
        if (current == null || current.token() != token) {
            // A claim that timed out: a newer one owns the lock, and this list may be older than its delivery.
            EdenLD.LOGGER.warn("[Shop] Dropped a late answer to {}'s timed-out claim.", name);
            return;
        }
        // Review findings 9 and 33: never deliver to a player who left, or while the server stops. Looked up again by
        // uuid, as dying or leaving the End makes a new player object.
        ServerPlayer player = server.getPlayerList().getPlayer(uuid);
        if (!server.isRunning() || player == null) {
            release(uuid, token);
            return;
        }
        Set<Long> listed = all.stream().map(ShopProduct::id).collect(Collectors.toSet());
        Set<Long> unconfirmed = unconfirmed(server, uuid);
        // Only a confirmation takes a product off the platform's list: one no longer listed was confirmed.
        boolean changed = unconfirmed.retainAll(listed);
        Set<Long> retry = new HashSet<>(unconfirmed);
        List<ShopProduct> fresh = new ArrayList<>();
        for (ShopProduct product : all) {
            if (!unconfirmed.contains(product.id()) && (productId == 0 || product.id() == productId)) {
                fresh.add(product);
            }
        }
        if (fresh.isEmpty()) {
            player.sendSystemMessage(Component.translatable("message.eden_ld.shop.no_items").withStyle(ChatFormatting.YELLOW));
        } else {
            player.sendSystemMessage(Component.translatable("message.eden_ld.shop.found_items", fresh.size())
                    .withStyle(ChatFormatting.GREEN));
        }
        Set<Long> delivered = deliver(server, player, fresh);
        if (!delivered.isEmpty() || changed) {
            unconfirmed.addAll(delivered);
            storeUnconfirmed(server, uuid, unconfirmed);
        }
        delivered.addAll(retry);
        confirm(server, uuid, name, token, delivered);
    }

    /**
     * Runs each product's commands, and returns the ids to confirm. A command fails when it can't be parsed or reports
     * a failure (vanilla tells only the source, never by an exception); one that reports nothing (a function without
     * {@code return}, some modded commands) counts as run. A product confirms once any of its commands ran: running
     * it again would repeat what already did. Failures are logged for staff and the player is told; a product where
     * nothing ran stays pending on the website.
     */
    private static Set<Long> deliver(MinecraftServer server, ServerPlayer player, List<ShopProduct> products) {
        String playerName = player.getGameProfile().getName();
        Set<Long> delivered = new HashSet<>();
        for (ShopProduct product : products) {
            int ran = 0;
            int failed = 0;
            for (int i = 0; i < product.quantity(); i++) {
                // Since minestar 1.3.0, {player} and {player_uuid} arrive already substituted.
                for (String command : product.commands()) {
                    if (run(server, command)) {
                        ran++;
                        EdenLD.LOGGER.info("[Shop] Executed command for product {} ({}/{}): {}",
                                product.id(), i + 1, product.quantity(), command);
                    } else {
                        failed++;
                        EdenLD.LOGGER.error("[Shop] Command FAILED for product {} ({}/{}) of {}: {}",
                                product.id(), i + 1, product.quantity(), playerName, command);
                    }
                }
            }
            if (ran > 0) {
                delivered.add(product.id());
            }
            if (failed > 0) {
                EdenLD.LOGGER.error("[Shop] Product {} \"{}\" for {}: {} command(s) failed, {} ran; {}. Staff: check it "
                                + "and deliver the rest by hand.", product.id(), product.name(), playerName, failed, ran,
                        ran > 0 ? "confirmed (it won't run again)" : "left waiting on the website");
                player.sendSystemMessage(Component.translatable(ran > 0 ? "message.eden_ld.shop.partial"
                        : "message.eden_ld.shop.failed", product.name()).withStyle(ChatFormatting.RED));
            }
        }
        return delivered;
    }

    /** Runs one product command as the server; false when it doesn't parse, throws or reports a failure. */
    private static boolean run(MinecraftServer server, String command) {
        String line = command.startsWith("/") ? command.substring(1) : command;
        int[] result = {-1};
        try {
            CommandSourceStack source = server.createCommandSourceStack()
                    .withCallback((success, value) -> result[0] = success ? Math.max(result[0], 1) : Math.max(result[0], 0));
            ParseResults<CommandSourceStack> parse = server.getCommands().getDispatcher().parse(line, source);
            if (Commands.getParseException(parse) != null
                    || ContextChain.tryFlatten(parse.getContext().build(line)).isEmpty()) {
                return false;
            }
            server.getCommands().performCommand(parse, line);
        } catch (Exception e) {
            EdenLD.LOGGER.error("[Shop] A product command threw: {}", command, e);
            return false;
        }
        return result[0] != 0;
    }

    /** Asks the platform to drop {@code ids} (already stored as delivered), then frees the player's claim. */
    private static void confirm(MinecraftServer server, UUID uuid, String playerName, long token, Set<Long> ids) {
        if (ids.isEmpty()) {
            release(uuid, token);
            return;
        }
        String list = ids.stream().map(String::valueOf).collect(Collectors.joining(", "));
        try {
            minestar.deleteUserPendingShopProductsByIds(ids)
                    .onSuccess(unit -> {
                        EdenLD.LOGGER.info("[Shop] Confirmed delivery of products [{}] for {}.", list, playerName);
                        release(uuid, token);
                    })
                    .onFailure(cause -> {
                        release(uuid, token);
                        EdenLD.LOGGER.error("[Shop] Failed to confirm delivery of [{}] for {}; the next claim retries "
                                + "the confirmation only.", list, playerName, cause);
                    });
        } catch (RuntimeException e) {
            release(uuid, token);
            throw e;
        }
    }

    /** Frees the player's claim if it is still this one's; true when it was. */
    private static boolean release(UUID uuid, long token) {
        Claim current = CLAIMING.get(uuid);
        return current != null && current.token() == token && CLAIMING.remove(uuid, current);
    }

    private static Set<Long> unconfirmed(MinecraftServer server, UUID uuid) {
        Set<Long> ids = new HashSet<>();
        for (long id : HubData.get(server).peek(uuid).getLongArray(UNCONFIRMED)) {
            ids.add(id);
        }
        return ids;
    }

    /** Stores the delivered ids and saves the hub data now: a crash must not forget a delivery that already happened. */
    private static void storeUnconfirmed(MinecraftServer server, UUID uuid, Set<Long> ids) {
        HubData data = HubData.get(server);
        CompoundTag tag = data.player(uuid);
        if (ids.isEmpty()) {
            tag.remove(UNCONFIRMED);
        } else {
            tag.putLongArray(UNCONFIRMED, ids.stream().mapToLong(Long::longValue).toArray());
        }
        HubData.saveNow(server);
    }
}
