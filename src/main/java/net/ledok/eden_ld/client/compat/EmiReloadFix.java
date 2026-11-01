package net.ledok.eden_ld.client.compat;

import dev.emi.emi.runtime.EmiReloadManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.ledok.eden_ld.EdenLD;
import net.ledok.eden_ld.mixin.EmiReloadManagerAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * EMI (1.1.24) could hang on "Reloading..." after a rejoin without restarting the game. EMI rebuilds
 * its index once both halves of the server's data have come — tags and recipes — and remembers which
 * half it has seen in a bit mask. It clears the mask on {@code Minecraft.disconnect(Screen)}, but a
 * dropped connection, a server stop and the server list's Join all go through
 * {@code disconnect(Screen, boolean)}; and FallenEden sends a few lone recipe syncs after the pair on
 * every join. So a "recipes" bit outlived the connection, the next join's tags (sent before the world
 * exists, in the configuration phase) completed the stale pair, the reload stopped at "World is null",
 * and EMI waited for tags that had already come.
 *
 * <p>Eden clears the mask whenever the client leaves a world ({@code MinecraftMixin}); a reload that
 * stopped without a world runs again once the world, the player and the recipes are there (once per
 * stop, {@code EmiReloadWorkerMixin}); and {@code /emireload} reloads EMI by hand. All of it only with
 * EMI installed.
 *
 * <p><b>Data that keeps coming</b> (2026-11-02, players' logs): EMI reloads once it has seen tags and recipes, but on
 * FallenEden more recipe syncs come after that pair — a lone one only sets its bit and waits for tags that never come —
 * and EMI Loot's loot tables keep arriving while EMI bakes (its chest loot then fails). EMI kept the early bake: missing
 * Crafting tabs and loot until {@code /emireload}. So any such data that arrives after a reload started marks EMI stale
 * ({@code EmiReloadManagerMixin}, {@code EmiLootReceiverMixin}); once nothing more has come for {@link #SETTLE_MS},
 * with a world and nothing reloading, EMI reloads from the client's current data — at most {@link #MAX_SETTLES} times
 * per join.
 */
public final class EmiReloadFix {

    private static final boolean LOADED = FabricLoader.getInstance().isModLoaded("emi");
    /** Ticks to wait for the recipes after joining before reloading anyway. */
    private static final int RECIPE_WAIT = 100;

    /** Quiet time after the last late data before the settle reload. */
    private static final long SETTLE_MS = 3000;
    /** Settle reloads per join (each one is a full EMI reload). */
    private static final int MAX_SETTLES = 2;

    /** A reload stopped before the world existed; set on EMI's reload thread. */
    private static volatile boolean retry;
    private static int waited;
    /** When EMI last started a reload, and when data last came after it (ms; set from network threads too). */
    private static volatile long reloadStartedAt;
    private static volatile long lateDataAt;
    private static int settles;

    private EmiReloadFix() {
    }

    public static void initialize() {
        if (!LOADED) {
            return;
        }
        ClientTickEvents.END_CLIENT_TICK.register(EmiReloadFix::tick);
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommandManager.literal("emireload").executes(context -> {
                    retry = false;
                    reload();
                    context.getSource().sendFeedback(Component.translatable("message.eden_ld.emi_reload"));
                    return 1;
                })));
    }

    /** The client left its world: a disconnect, or the server sending it back to configuration. */
    public static void leftWorld() {
        if (LOADED) {
            EmiReloadManagerAccessor.eden_ld$setLoadedResourcesMask(0);
            retry = false;
            waited = 0;
            reloadStartedAt = 0;
            lateDataAt = 0;
            settles = 0;
        }
    }

    /** EMI started a reload (its own, a settle reload, or {@code /emireload}). */
    public static void reloadStarted() {
        reloadStartedAt = System.currentTimeMillis();
    }

    /**
     * A tag or recipe sync reached EMI. One that completed the pair started a reload (and cleared the mask); a lone one
     * after EMI's first reload leaves it waiting with an early bake: noted as late data.
     */
    public static void synced() {
        if (reloadStartedAt != 0 && EmiReloadManagerAccessor.eden_ld$loadedResourcesMask() != 0) {
            lateDataAt = System.currentTimeMillis();
        }
    }

    /** A loot table packet for EMI Loot (any thread). */
    public static void lootArrived() {
        if (reloadStartedAt != 0) {
            lateDataAt = System.currentTimeMillis();
        }
    }

    /** EMI's reload thread gave up because the client had no world (or recipe manager) yet. */
    public static void stoppedWithoutWorld() {
        retry = true;
    }

    private static void tick(Minecraft client) {
        settle(client);
        if (!retry || client.level == null || client.player == null) {
            return;
        }
        // The recipes come just after the world; a reload before them would leave EMI without recipes.
        if (client.level.getRecipeManager().getRecipes().isEmpty() && ++waited < RECIPE_WAIT) {
            return;
        }
        Thread worker = EmiReloadManagerAccessor.eden_ld$thread();
        if (worker != null && worker.isAlive()) {
            return; // the stopped reload is still winding down, or a new one runs: see how it ends
        }
        retry = false;
        waited = 0;
        // Still "reloading" with nothing running: the stopped reload was never redone.
        if (EmiReloadManager.getStatus() == 1) {
            EdenLD.LOGGER.info("EMI's reload stopped before the world existed; reloading it again");
            reload();
        }
    }

    /** Data came after the last reload started and has stopped coming: one more reload, within the per-join limit. */
    private static void settle(Minecraft client) {
        long late = lateDataAt;
        if (late == 0 || late < reloadStartedAt || settles >= MAX_SETTLES
                || System.currentTimeMillis() - late < SETTLE_MS || client.level == null || client.player == null) {
            return;
        }
        Thread worker = EmiReloadManagerAccessor.eden_ld$thread();
        if (worker != null && worker.isAlive()) {
            return; // still baking: decide once it's done
        }
        settles++;
        lateDataAt = 0;
        EdenLD.LOGGER.info("EMI got more recipes, tags or loot after its reload started; reloading it ({} of {} this join)",
                settles, MAX_SETTLES);
        reload();
    }

    /** Reloads EMI from the client's current data; the reload covers both halves EMI waits for. */
    private static void reload() {
        EmiReloadManagerAccessor.eden_ld$setLoadedResourcesMask(0);
        EmiReloadManager.reload();
    }
}
