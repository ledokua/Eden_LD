package net.ledok.eden_ld.compat;

import net.fabricmc.loader.api.FabricLoader;
import net.ledok.eden_ld.EdenLD;
import net.ledok.eden_ld.hub.HubServer;
import net.ledok.eden_ld.util.OnServerThread;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.lang.reflect.Method;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Optional Economy_LD access via reflection (same approach as Arenas_LD's compat):
 * no compile-time dependency, everything degrades to "no economy" when the mod or
 * a method is missing. Callbacks are delivered on the server thread. Every LC it gives or takes resends the
 * player's balance to the hub ({@link HubServer#sendBalance}).
 */
public final class EconomyCompat {

    public static final boolean LOADED = FabricLoader.getInstance().isModLoaded("economy_ld");
    /** How long a balance lookup may take before callers go on without it. */
    public static final int ANSWER_TIMEOUT_SECONDS = 3;

    private static Method getInstance;
    private static Method getBalance;
    private static Method give;
    private static Method take;
    private static boolean resolved;
    private static boolean available;

    private EconomyCompat() {
    }

    /** {@code onResult(hasEconomy, balance)} — always called exactly once, on the server thread. */
    public interface BalanceConsumer {
        void onResult(boolean hasEconomy, long balance);
    }

    public static void fetchBalance(ServerPlayer player, BalanceConsumer onResult) {
        MinecraftServer server = player.getServer();
        if (!LOADED || server == null || !resolve()) {
            onResult.onResult(false, 0L);
            return;
        }
        try {
            Object manager = getInstance.invoke(null);
            @SuppressWarnings("unchecked")
            CompletableFuture<Long> future = (CompletableFuture<Long>) getBalance
                    .invoke(manager, player.getUUID(), player.getGameProfile().getName());
            // Answered once: by the lookup, or (1.3.102) after ANSWER_TIMEOUT_SECONDS without one, so a hanging
            // database never keeps the hub's Home and Mail from coming (both wait on the balance).
            java.util.concurrent.atomic.AtomicBoolean answered = new java.util.concurrent.atomic.AtomicBoolean();
            // Hops through OnServerThread (review finding 33): once the server stops, execute ran this on the DB thread.
            future.whenComplete((balance, cause) -> OnServerThread.run(server, () -> {
                if (!answered.compareAndSet(false, true)) {
                    return;
                }
                if (cause != null || balance == null) {
                    if (cause != null) {
                        EdenLD.LOGGER.warn("[Economy] Balance lookup failed for {}.",
                                player.getGameProfile().getName(), cause);
                    }
                    onResult.onResult(false, 0L);
                } else {
                    onResult.onResult(true, balance);
                }
            }));
            CompletableFuture.delayedExecutor(ANSWER_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
                    .execute(() -> OnServerThread.run(server, () -> {
                        if (answered.compareAndSet(false, true)) {
                            EdenLD.LOGGER.warn("[Economy] Balance lookup for {} gave no answer in {} s; going on without "
                                    + "the balance.", player.getGameProfile().getName(), ANSWER_TIMEOUT_SECONDS);
                            onResult.onResult(false, 0L);
                        }
                    }));
        } catch (Exception e) {
            EdenLD.LOGGER.warn("[Economy] Balance lookup threw; disabling economy compat.", e);
            available = false;
            onResult.onResult(false, 0L);
        }
    }

    /** True when Economy_LD is installed and its API resolved (LC rewards and costs work). */
    public static boolean available() {
        return LOADED && resolve();
    }

    /** Adds LC to the player's balance; fire-and-forget (failures are logged). */
    public static void give(ServerPlayer player, long amount) {
        give(player, amount, given -> {
        });
    }

    /**
     * Adds LC to the player's balance; {@code onResult(true)} once they are on it, {@code false} when the give failed
     * (review finding 15: a guild bank withdrawal debited the bank before a fire-and-forget give, and a failed give
     * lost the LC). Server thread, exactly once, while the server runs; when it stops first (also for an answer queued
     * in the last tick and never run), a failure is only logged (with the player and the amount, for staff) and
     * {@code onResult} isn't called. Nothing to give counts as given.
     */
    public static void give(ServerPlayer player, long amount, java.util.function.Consumer<Boolean> onResult) {
        MinecraftServer server = player.getServer();
        if (amount <= 0) {
            onResult.accept(true);
            return;
        }
        if (server == null || !available()) {
            onResult.accept(false);
            return;
        }
        String name = player.getGameProfile().getName();
        CompletableFuture<?> future;
        try {
            future = (CompletableFuture<?>) give.invoke(getInstance.invoke(null), player.getUUID(), name, amount);
        } catch (Exception e) {
            EdenLD.LOGGER.error("[Economy] Failed to give {} LC to {}: give threw.", amount, name, e);
            onResult.accept(false);
            return;
        }
        future.whenComplete((ignored, cause) -> {
            if (cause != null) {
                EdenLD.LOGGER.error("[Economy] Failed to give {} LC to {}.", amount, name, cause);
            }
            OnServerThread.hop(server, () -> {
                if (cause == null) {
                    HubServer.sendBalance(player);
                }
                onResult.accept(cause == null);
            }, () -> {
                if (cause != null) {
                    EdenLD.LOGGER.error("[Economy] The server is stopping: {} LC that failed to reach {} ({}) can't be "
                            + "put back; staff, give them by hand.", amount, name, player.getUUID());
                }
            });
        });
    }

    /**
     * Gives back LC a take already took, when its callback can't run any more (the server is stopping): on the calling
     * thread, failures logged with the player and amount for staff.
     */
    private static void refund(ServerPlayer player, long amount) {
        String name = player.getGameProfile().getName();
        try {
            ((CompletableFuture<?>) give.invoke(getInstance.invoke(null), player.getUUID(), name, amount))
                    .whenComplete((ignored, cause) -> {
                        if (cause != null) {
                            EdenLD.LOGGER.error("[Economy] Failed to refund {} LC to {} ({}); staff, give them by hand.",
                                    amount, name, player.getUUID(), cause);
                        } else {
                            EdenLD.LOGGER.info("[Economy] Refunded {} LC to {}: the server stopped before the payment "
                                    + "was used.", amount, name);
                        }
                    });
        } catch (Exception e) {
            EdenLD.LOGGER.error("[Economy] Failed to refund {} LC to {} ({}); staff, give them by hand.", amount, name,
                    player.getUUID(), e);
        }
    }

    /**
     * Takes LC; {@code onResult(true)} only when the balance covered it. Server thread, exactly once while the server
     * runs. When the server stops first (the answer comes during the stop, or it was queued in the last tick and never
     * ran), {@code onResult} isn't called and LC already taken are given back: at once, or at SERVER_STOPPED before
     * Economy_LD closes its database (review finding 33 and the review of 1.3.133: execute ran the callback on the DB
     * thread, next to the stopping server thread, and a queued callback the stop dropped kept the LC).
     */
    public static void take(ServerPlayer player, long amount, java.util.function.Consumer<Boolean> onResult) {
        MinecraftServer server = player.getServer();
        if (amount <= 0) {
            onResult.accept(true);
            return;
        }
        if (server == null || !available()) {
            onResult.accept(false);
            return;
        }
        try {
            @SuppressWarnings("unchecked")
            CompletableFuture<Boolean> future = (CompletableFuture<Boolean>) take.invoke(getInstance.invoke(null),
                    player.getUUID(), player.getGameProfile().getName(), amount);
            future.whenComplete((ok, cause) -> OnServerThread.hop(server, () -> {
                if (cause != null) {
                    EdenLD.LOGGER.error("[Economy] Failed to take {} LC from {}.", amount,
                            player.getGameProfile().getName(), cause);
                }
                boolean paid = cause == null && Boolean.TRUE.equals(ok);
                if (paid) {
                    HubServer.sendBalance(player);
                }
                onResult.accept(paid);
            }, () -> {
                if (cause == null && Boolean.TRUE.equals(ok)) {
                    refund(player, amount);
                }
            }));
        } catch (Exception e) {
            EdenLD.LOGGER.error("[Economy] take threw.", e);
            onResult.accept(false);
        }
    }

    private static synchronized boolean resolve() {
        if (resolved) {
            return available;
        }
        resolved = true;
        try {
            Class<?> managerClass = Class.forName("net.ledok.economy_ld.manager.EconomyManager");
            getInstance = managerClass.getMethod("getInstance");
            getBalance = managerClass.getMethod("getBalance", UUID.class, String.class);
            give = managerClass.getMethod("give", UUID.class, String.class, long.class);
            take = managerClass.getMethod("take", UUID.class, String.class, long.class);
            available = true;
        } catch (Exception e) {
            EdenLD.LOGGER.warn("[Economy] Economy_LD present but its API did not resolve; balance hidden.", e);
            available = false;
        }
        return available;
    }
}
