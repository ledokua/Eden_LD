package net.ledok.eden_ld.compat.duels;

import net.fabricmc.loader.api.FabricLoader;
import net.ledok.eden_ld.EdenLD;
import net.ledok.eden_ld.hub.HubPayloads;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

/**
 * The only Duels_LD entry point the rest of the mod touches. No {@code net.ledok.duels_ld}
 * imports, so it is always safe to class-load; everything mod-specific lives in
 * {@link DuelsIntegration} behind {@link #LOADED}.
 */
public final class DuelsHook {

    public static final boolean LOADED = FabricLoader.getInstance().isModLoaded("duels_ld");

    private DuelsHook() {
    }

    /**
     * {@code onChange} runs (server thread) on any queue change, match start or match end;
     * {@code onQueue} with the player whose queue changed; {@code onMatchStart} with the mode
     * (0 = 1v1, 1 = 2v2) and the players.
     */
    public static void initialize(Runnable onChange, java.util.function.Consumer<java.util.UUID> onQueue,
                                  java.util.function.BiConsumer<Integer, java.util.List<java.util.UUID>> onMatchStart) {
        if (!LOADED) {
            return;
        }
        DuelsIntegration.register(onChange, onQueue, onMatchStart);
        EdenLD.LOGGER.info("[Duels] Hub integration initialized.");
    }

    /** The player is in a running Duels match (started and not yet ended); false without the mod. */
    public static boolean inMatch(java.util.UUID player) {
        return LOADED && DuelsIntegration.inMatch(player);
    }

    /**
     * The running Duels match the player is in, as a number that two players share only in the same match; 0 when
     * none (or without the mod).
     */
    public static int match(java.util.UUID player) {
        return LOADED ? DuelsIntegration.match(player) : 0;
    }

    /** The Duels tab for the player; null without the mod. */
    @Nullable
    public static HubPayloads.DuelsData data(ServerPlayer player) {
        return LOADED ? DuelsIntegration.data(player) : null;
    }

    /** The player's queue state (Home card); null without the mod. */
    @Nullable
    public static HubPayloads.DuelsStatus status(java.util.UUID player) {
        return LOADED ? DuelsIntegration.status(player) : null;
    }

    /**
     * Joins a queue (0 = 1v1, 1 = 2v2). Null when queued (or already queued), else why not, as a
     * lang key suffix under {@code message.eden_ld.duels.queue.} (e.g. {@code not_party_leader}).
     */
    @Nullable
    public static String join(ServerPlayer player, int mode) {
        return LOADED ? DuelsIntegration.join(player, mode) : "disabled";
    }

    /** Leaves one queue, or every queue when {@code mode} is -1. */
    public static void leave(ServerPlayer player, int mode) {
        if (LOADED) {
            DuelsIntegration.leave(player, mode);
        }
    }

    // ---------------------------------------------------------------- wager duels (Duels_LD 1.6.0+)

    /** The running Duels_LD has the wager API (checked once by reflection); false without the mod or with an older one. */
    public static boolean wagers() {
        return LOADED && DuelsIntegration.wagers();
    }

    /**
     * Listens to wager changes: {@code onPlayer} with a player whose wagers changed (either side of an offer, the
     * fight, deliveries), {@code onListings} when a public announcement appeared, went or changed state.
     */
    public static void initializeWagers(java.util.function.Consumer<java.util.UUID> onPlayer, Runnable onListings) {
        if (wagers()) {
            DuelsWagers.register(onPlayer, onListings);
        }
    }

    /** The wager pages' data for the player: the header and the {@code parts} asked for; null without the API. */
    @Nullable
    public static HubPayloads.WagerData wagerData(ServerPlayer player, int parts) {
        return wagers() ? DuelsWagers.data(player, parts) : null;
    }

    /** Runs a wager page's action; the toast to show (Duels' own words), or null for none. */
    @Nullable
    public static HubPayloads.HubNotice wagerAction(ServerPlayer player, HubPayloads.WagerAction action) {
        return wagers() ? DuelsWagers.act(player, action) : null;
    }
}
