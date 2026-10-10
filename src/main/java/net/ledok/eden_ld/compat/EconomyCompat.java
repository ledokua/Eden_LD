package net.ledok.eden_ld.compat;

import net.fabricmc.loader.api.FabricLoader;
import net.ledok.eden_ld.EdenLD;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.lang.reflect.Method;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Optional Economy_LD access via reflection (same approach as Arenas_LD's compat):
 * no compile-time dependency, everything degrades to "no economy" when the mod or
 * a method is missing. Callbacks are delivered on the server thread.
 */
public final class EconomyCompat {

    public static final boolean LOADED = FabricLoader.getInstance().isModLoaded("economy_ld");

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
            future.whenComplete((balance, cause) -> server.execute(() -> {
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
        if (amount <= 0 || !available()) {
            return;
        }
        try {
            ((CompletableFuture<?>) give.invoke(getInstance.invoke(null), player.getUUID(),
                    player.getGameProfile().getName(), amount))
                    .whenComplete((ignored, cause) -> {
                        if (cause != null) {
                            EdenLD.LOGGER.error("[Economy] Failed to give {} LC to {}.", amount,
                                    player.getGameProfile().getName(), cause);
                        }
                    });
        } catch (Exception e) {
            EdenLD.LOGGER.error("[Economy] give threw.", e);
        }
    }

    /** Takes LC; {@code onResult(true)} only when the balance covered it. Server thread, exactly once. */
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
            future.whenComplete((ok, cause) -> server.execute(() -> {
                if (cause != null) {
                    EdenLD.LOGGER.error("[Economy] Failed to take {} LC from {}.", amount,
                            player.getGameProfile().getName(), cause);
                }
                onResult.accept(cause == null && Boolean.TRUE.equals(ok));
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
