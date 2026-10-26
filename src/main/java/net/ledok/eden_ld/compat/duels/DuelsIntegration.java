package net.ledok.eden_ld.compat.duels;

import net.ledok.duels_ld.api.DuelsApi;
import net.ledok.eden_ld.api.hub.EdenPartyApi;
import net.ledok.eden_ld.hub.HubPayloads;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Duels_LD behind {@link DuelsHook}: the tab's data from {@link DuelsApi}, and its queue buttons.
 * <p>
 * Wager duels need DuelsApi 1.6.0+. Whether the running Duels_LD has that API is looked up once by reflection
 * ({@link #WAGERS}); everything using its types lives in {@link DuelsWagers}, which is only touched after the check,
 * so with an older Duels_LD the hub's Duels page simply doesn't show.
 */
final class DuelsIntegration {

    private static final int HISTORY = 20;
    private static final int TOP = 20;
    /**
     * Players in a running match → that match's number (from 1): added on match start, removed on match end (DuelsApi
     * has no lookup). Two players fight each other only within the same match (docs/PLAN-pvp-toggle.md).
     */
    private static final Map<UUID, Integer> IN_MATCH = new ConcurrentHashMap<>();
    private static final AtomicInteger MATCHES = new AtomicInteger();
    /** The running Duels_LD has the wager API (1.6.0+). */
    private static final boolean WAGERS = wagerApi();

    private DuelsIntegration() {
    }

    static void register(Runnable onChange, java.util.function.Consumer<UUID> onQueue,
                         java.util.function.BiConsumer<Integer, List<UUID>> onMatchStart) {
        DuelsApi.addListener(new DuelsApi.Listener() {
            @Override
            public void onQueueChanged(UUID player) {
                onQueue.accept(player);
                onChange.run();
            }

            @Override
            public void onMatchStart(DuelsApi.Mode mode, List<UUID> players) {
                int match = MATCHES.incrementAndGet();
                players.forEach(player -> IN_MATCH.put(player, match));
                onMatchStart.accept(mode.ordinal(), players);
                onChange.run();
            }

            @Override
            public void onMatchEnd(DuelsApi.Mode mode, List<UUID> winners, List<UUID> losers, boolean draw) {
                winners.forEach(IN_MATCH::remove);
                losers.forEach(IN_MATCH::remove);
                onChange.run();
            }
        });
    }

    static boolean wagers() {
        return WAGERS;
    }

    /**
     * Every wager method, record accessor and listener hook {@link DuelsWagers} uses, with their return types: a
     * Duels_LD that renamed or reshaped any of them counts as having no wager API.
     */
    private static boolean wagerApi() {
        try {
            Class<?> api = DuelsApi.class;
            String root = "net.ledok.duels_ld.api.";
            Class<?> result = Class.forName(root + "WagerResult");
            Class<?> listing = Class.forName(root + "DuelsApi$WagerListing");
            Class<?> offer = Class.forName(root + "DuelsApi$WagerOffer");
            Class<?> record = Class.forName(root + "DuelsApi$WagerRecord");
            Class<?> stake = Class.forName(root + "DuelsApi$WagerStake");
            Class<?> stats = Class.forName(root + "DuelsApi$WagerStats");
            Class<?> mine = Class.forName(root + "DuelsApi$MyWagers");
            returns(api.getMethod("wagersEnabled"), boolean.class);
            returns(api.getMethod("listings", MinecraftServer.class, UUID.class), List.class);
            returns(api.getMethod("myWagers", MinecraftServer.class, UUID.class), mine);
            returns(api.getMethod("wagerHistory", MinecraftServer.class, UUID.class, int.class), List.class);
            returns(api.getMethod("wagerStats", UUID.class), stats);
            returns(api.getMethod("skipsLossWarning", MinecraftServer.class, UUID.class), boolean.class);
            returns(api.getMethod("cancelListing", ServerPlayer.class), result);
            for (String action : List.of("withdrawOffer", "declineOffer", "acceptOffer", "declineChallenge")) {
                returns(api.getMethod(action, ServerPlayer.class, int.class), result);
            }
            returns(api.getMethod("report", ServerPlayer.class, int.class, String.class), result);
            returns(result.getMethod("ok"), boolean.class);
            returns(result.getMethod("message", Object[].class), net.minecraft.network.chat.Component.class);
            Class<?> listener = DuelsApi.Listener.class;
            listener.getMethod("onWagerChanged", UUID.class);
            listener.getMethod("onListingsChanged");
            for (String accessor : List.of("number", "offers")) {
                returns(listing.getMethod(accessor), int.class);
            }
            for (String accessor : List.of("createdAtMillis", "expiresAtMillis", "pausedRemainingMillis")) {
                returns(listing.getMethod(accessor), long.class);
            }
            listing.getMethod("author");
            listing.getMethod("authorName");
            listing.getMethod("authorStats");
            listing.getMethod("stake");
            listing.getMethod("comment");
            listing.getMethod("state");
            listing.getMethod("target");
            listing.getMethod("targetName");
            listing.getMethod("mine");
            returns(offer.getMethod("id"), int.class);
            returns(offer.getMethod("number"), int.class);
            offer.getMethod("challenger");
            offer.getMethod("challengerName");
            offer.getMethod("stake");
            offer.getMethod("state");
            offer.getMethod("online");
            offer.getMethod("createdAtMillis");
            returns(record.getMethod("number"), int.class);
            returns(record.getMethod("startedAtMillis"), long.class);
            returns(record.getMethod("endedAtMillis"), long.class);
            for (String accessor : List.of("author", "authorName", "challenger", "challengerName", "authorStake",
                    "challengerStake", "winner", "reportedByViewer")) {
                record.getMethod(accessor);
            }
            returns(stake.getMethod("items"), List.class);
            returns(stake.getMethod("coins"), long.class);
            for (String accessor : List.of("wins", "losses", "draws")) {
                returns(stats.getMethod(accessor), int.class);
            }
            for (String accessor : List.of("listing", "offersOnMine", "myOffers", "challengesToMe", "inBank")) {
                mine.getMethod(accessor);
            }
            return true;
        } catch (ReflectiveOperationException | LinkageError e) {
            return false;
        }
    }

    private static void returns(java.lang.reflect.Method method, Class<?> type) throws NoSuchMethodException {
        if (method.getReturnType() != type) {
            throw new NoSuchMethodException(method + " doesn't return " + type.getName());
        }
    }

    static boolean inMatch(UUID player) {
        return IN_MATCH.containsKey(player);
    }

    static int match(UUID player) {
        return IN_MATCH.getOrDefault(player, 0);
    }

    static HubPayloads.DuelsData data(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        UUID uuid = player.getUUID();
        DuelsApi.Stats stats = DuelsApi.stats(server, uuid).orElse(null);
        DuelsApi.QueueState queue = DuelsApi.queueState(uuid);
        boolean inMatch = !queue.queued() && !queue.pending() && IN_MATCH.containsKey(uuid);

        int state = queue.pending() ? HubPayloads.DuelsData.PENDING
                : queue.queued() ? HubPayloads.DuelsData.SEARCHING
                : inMatch ? HubPayloads.DuelsData.IN_MATCH : HubPayloads.DuelsData.IDLE;
        long searching = queue.queued() && queue.joinedAtMillis() > 0
                ? Math.max(0, System.currentTimeMillis() - queue.joinedAtMillis()) : -1;

        EdenPartyApi.PartyInfo party = EdenPartyApi.getParty(server, uuid).orElse(null);
        List<HubPayloads.DuelsMatch> history = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (DuelsApi.MatchRecord match : DuelsApi.history(server, uuid, HISTORY)) {
            history.add(new HubPayloads.DuelsMatch(now - match.endedAtMillis(), match.mode().ordinal(),
                    match.result().ordinal(), match.ratingBefore(), match.ratingAfter(),
                    List.copyOf(match.teammates()), List.copyOf(match.opponents()), match.durationMillis()));
        }
        return new HubPayloads.DuelsData(
                mode(server, uuid, stats, DuelsApi.Mode.ONE_V_ONE, queue.oneVsOne()),
                mode(server, uuid, stats, DuelsApi.Mode.TWO_V_TWO, queue.twoVsTwo()),
                state, searching, queue.searchRange(), queue.viaParty(),
                party == null ? 0 : party.members().size(), party != null && party.leader().equals(uuid),
                stats != null && stats.rewardsEnabled(), stats == null ? 0 : stats.earnedToday(),
                stats == null ? 0 : stats.dailyCap(),
                history, top(server, DuelsApi.Mode.ONE_V_ONE), top(server, DuelsApi.Mode.TWO_V_TWO));
    }

    private static HubPayloads.DuelsMode mode(MinecraftServer server, UUID player, @Nullable DuelsApi.Stats stats,
                                              DuelsApi.Mode mode, boolean searching) {
        DuelsApi.ModeStats modeStats = stats == null ? null : stats.of(mode);
        int rank = DuelsApi.rank(server, mode, player);
        int queued = DuelsApi.queued(mode);
        if (modeStats == null) {
            return new HubPayloads.DuelsMode(0, 0, 0, 0, rank, queued, searching);
        }
        return new HubPayloads.DuelsMode(modeStats.rating(), modeStats.wins(), modeStats.losses(), modeStats.draws(),
                rank, queued, searching);
    }

    private static List<HubPayloads.DuelsLeader> top(MinecraftServer server, DuelsApi.Mode mode) {
        List<HubPayloads.DuelsLeader> out = new ArrayList<>();
        for (DuelsApi.LeaderboardEntry entry : DuelsApi.leaderboard(server, mode, TOP)) {
            out.add(new HubPayloads.DuelsLeader(entry.player(), entry.name(), entry.rating(), entry.played()));
        }
        return out;
    }

    /** The player's queue state, for the Home card. */
    static HubPayloads.DuelsStatus status(UUID player) {
        DuelsApi.QueueState queue = DuelsApi.queueState(player);
        long searching = queue.queued() && queue.joinedAtMillis() > 0
                ? Math.max(0, System.currentTimeMillis() - queue.joinedAtMillis()) : -1;
        return new HubPayloads.DuelsStatus(queue.oneVsOne(), queue.twoVsTwo(), queue.pending(), searching);
    }

    @Nullable
    static String join(ServerPlayer player, int mode) {
        DuelsApi.QueueResult result = DuelsApi.joinQueue(player, mode == 1 ? DuelsApi.Mode.TWO_V_TWO : DuelsApi.Mode.ONE_V_ONE);
        return result == DuelsApi.QueueResult.QUEUED || result == DuelsApi.QueueResult.ALREADY_QUEUED
                ? null : result.name().toLowerCase(Locale.ROOT);
    }

    static void leave(ServerPlayer player, int mode) {
        DuelsApi.leaveQueue(player, mode < 0 ? null : mode == 1 ? DuelsApi.Mode.TWO_V_TWO : DuelsApi.Mode.ONE_V_ONE);
    }
}
