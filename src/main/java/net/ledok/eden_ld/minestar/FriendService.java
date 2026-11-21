package net.ledok.eden_ld.minestar;

import net.ledok.eden_ld.EdenLD;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;
import ua.com.minestar.model.FriendRequestStatus;
import ua.com.minestar.model.PackSummary;
import ua.com.minestar.model.UserFriend;
import ua.com.minestar.model.UserFriendRequest;
import ua.com.minestar.model.UserProfile;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static ua.com.minestar.Minestar.minestar;

/**
 * Friend reads and mutations against the platform. Every callback lands on the
 * server thread. Profile re-fetches are throttled per player, since the Friends
 * tab asks for one each time it opens; forced ones share a fetch in flight and
 * start at least {@link #FETCH_MIN_GAP_MS} apart (review finding 37).
 */
final class FriendService {

    private static final long REFETCH_THROTTLE_MS = 15_000;
    /** Minestar reports HTTP failures as "... received 409 with message ...". */
    private static final Pattern STATUS = Pattern.compile("received (\\d{3})");

    /**
     * Platform profile fetches for one player start at least this far apart (review finding 37: refused friend actions
     * used to force one or two profile fetches per packet, with no limit).
     */
    private static final long FETCH_MIN_GAP_MS = 2_000;
    /**
     * A sent profile fetch with no answer after this long is given up: its waiters get the cached snapshot. Minestar's
     * HTTP client has no timeout, so a stalled call would otherwise keep every later Friends-tab request for that
     * player joined to it, unanswered until they relog (follow-up to review finding 37).
     */
    private static final long FETCH_WATCHDOG_MS = 5_000;

    private static final Map<UUID, Long> LAST_FETCH = new ConcurrentHashMap<>();
    /**
     * The friend add or remove each player has in flight (one at a time). A map to a token rather than a set, so a
     * late answer to a mutation the watchdog gave up on can't free the slot of a newer one.
     */
    private static final Map<UUID, Mutation> IN_FLIGHT = new ConcurrentHashMap<>();
    /**
     * A friend add or remove with no platform answer after this long is given up: the slot is freed and the player
     * told it failed. Minestar's HTTP client has no timeout, so a stalled call used to answer every later friend
     * action with "wait" until the player relogged (follow-up to review finding 37).
     */
    private static final long MUTATION_WATCHDOG_MS = 10_000;

    /** One friend add or remove; answered once, by the platform or the watchdog. Server thread. */
    private static final class Mutation {
        boolean done;
    }
    /**
     * Review finding 37: at most one profile fetch per player in flight (or waiting for its start); callers meanwhile
     * share its answer. Entries are made and finished on the server thread; {@link #forget} may drop one from the
     * disconnect handler, which only means the next fetch starts fresh.
     */
    private static final Map<UUID, Pending> FETCHING = new ConcurrentHashMap<>();
    /** When each player's last profile fetch started, for {@link #FETCH_MIN_GAP_MS}. */
    private static final Map<UUID, Long> LAST_START = new ConcurrentHashMap<>();

    private static final class Pending {
        /** Answered with this fetch's result. */
        final List<Consumer<FriendSnapshot>> waiting = new ArrayList<>();
        /**
         * Forced callers that arrived after the request went out: the change they want to see may be newer than it,
         * so they get one follow-up fetch (shared by all of them) once this one is done.
         */
        List<Consumer<FriendSnapshot>> followUp;
        boolean sent;
        /** Answered (by the platform or the watchdog); a second answer is ignored. Server thread. */
        boolean done;
    }

    private FriendService() {
    }

    static void forget(UUID uuid) {
        LAST_FETCH.remove(uuid);
        IN_FLIGHT.remove(uuid);
        FETCHING.remove(uuid);
        LAST_START.remove(uuid);
    }

    /** Calls back with the player's friends, or null when the platform couldn't be reached. */
    static void fetch(ServerPlayer player, boolean force, Consumer<FriendSnapshot> onDone) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }
        UUID uuid = player.getUUID();
        Long last = LAST_FETCH.get(uuid);
        UserProfile cached = ProfileCache.get(uuid).orElse(null);
        if (!force && cached != null && last != null
                && System.currentTimeMillis() - last < REFETCH_THROTTLE_MS) {
            onDone.accept(snapshot(cached));
            return;
        }
        Pending pending = FETCHING.get(uuid);
        if (pending != null) {
            if (!force || !pending.sent) {
                // Not sent yet, or not forced: this fetch's answer is new enough.
                pending.waiting.add(onDone);
            } else {
                if (pending.followUp == null) {
                    pending.followUp = new ArrayList<>();
                }
                pending.followUp.add(onDone);
            }
            return;
        }
        pending = new Pending();
        pending.waiting.add(onDone);
        FETCHING.put(uuid, pending);
        start(server, player, pending);
    }

    /** Sends the fetch, or schedules it for when {@link #FETCH_MIN_GAP_MS} since the last one has passed. */
    private static void start(MinecraftServer server, ServerPlayer player, Pending pending) {
        UUID uuid = player.getUUID();
        long wait = FETCH_MIN_GAP_MS - (System.currentTimeMillis() - LAST_START.getOrDefault(uuid, 0L));
        if (wait > 0) {
            java.util.concurrent.CompletableFuture.delayedExecutor(wait, java.util.concurrent.TimeUnit.MILLISECONDS)
                    .execute(() -> net.ledok.eden_ld.util.OnServerThread.run(server, () -> send(server, player, pending)));
            return;
        }
        send(server, player, pending);
    }

    private static void send(MinecraftServer server, ServerPlayer player, Pending pending) {
        UUID uuid = player.getUUID();
        pending.sent = true;
        UserProfile cached = ProfileCache.get(uuid).orElse(null);
        if (player.hasDisconnected()) {
            // Left while the fetch waited for its gap: no platform call for someone gone.
            finish(server, player, pending, cached == null ? null : snapshot(cached));
            return;
        }
        LAST_START.put(uuid, System.currentTimeMillis());
        minestar.getUserProfileByProfileUuid(uuid)
                .onSuccess(profile -> {
                    ProfileService.store(server, player, profile);
                    FriendSnapshot snapshot = snapshot(profile);
                    net.ledok.eden_ld.util.OnServerThread.run(server, () -> {
                        // Same hop as the cache put, and not for someone gone: forget() already cleared it on leave
                        // (review finding 50).
                        if (!ProfileService.gone(player)) {
                            LAST_FETCH.put(uuid, System.currentTimeMillis());
                        }
                        finish(server, player, pending, snapshot);
                    });
                })
                .onFailure(cause -> {
                    EdenLD.LOGGER.warn("[Friends] Failed to refresh the profile of {}.",
                            player.getGameProfile().getName(), cause);
                    // A stale snapshot beats an empty tab.
                    FriendSnapshot fallback = cached == null ? null : snapshot(cached);
                    net.ledok.eden_ld.util.OnServerThread.run(server, () -> finish(server, player, pending, fallback));
                });
        // The watchdog, as in MinestarHook.fetchPendingPurchases: answer from the cache if the platform stalls; the
        // late answer then finds the fetch done and is dropped, and the next fetch starts fresh.
        java.util.concurrent.CompletableFuture.delayedExecutor(FETCH_WATCHDOG_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
                .execute(() -> net.ledok.eden_ld.util.OnServerThread.run(server, () -> {
                    if (pending.done) {
                        return;
                    }
                    EdenLD.LOGGER.warn("[Friends] The platform gave no answer about {}'s profile in {} s; using the "
                            + "cached friends.", player.getGameProfile().getName(), FETCH_WATCHDOG_MS / 1000);
                    UserProfile stale = ProfileCache.get(uuid).orElse(null);
                    finish(server, player, pending, stale == null ? null : snapshot(stale));
                }));
    }

    /** Server thread: answers everyone who shared the fetch, then starts the follow-up forced callers asked for. */
    private static void finish(MinecraftServer server, ServerPlayer player, Pending pending,
                               @Nullable FriendSnapshot snapshot) {
        if (pending.done) {
            // The watchdog answered already; this is the stalled call coming back late.
            return;
        }
        pending.done = true;
        UUID uuid = player.getUUID();
        FETCHING.remove(uuid, pending);
        for (Consumer<FriendSnapshot> waiter : pending.waiting) {
            waiter.accept(snapshot);
        }
        List<Consumer<FriendSnapshot>> followUp = pending.followUp;
        if (followUp == null) {
            return;
        }
        if (player.hasDisconnected()) {
            // Their callbacks only send to online players; nothing to fetch for.
            return;
        }
        Pending next = FETCHING.get(uuid);
        if (next != null && !next.sent) {
            // A waiter already started a fetch that isn't out yet: it is new enough for these too.
            next.waiting.addAll(followUp);
            return;
        }
        if (next != null) {
            if (next.followUp == null) {
                next.followUp = new ArrayList<>();
            }
            next.followUp.addAll(followUp);
            return;
        }
        next = new Pending();
        next.waiting.addAll(followUp);
        FETCHING.put(uuid, next);
        start(server, player, next);
    }

    static @Nullable FriendSnapshot cached(UUID uuid) {
        return ProfileCache.get(uuid).map(FriendService::snapshot).orElse(null);
    }

    /**
     * Sends a friend request, or accepts one when the target already requested us.
     * Calls back with null + accepted flag on success, or a player-facing error.
     */
    static void add(ServerPlayer player, UUID target, String targetName, MinestarHook.FriendCallback onDone) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }
        Mutation mutation = begin(server, player, onDone, "addUserFriend");
        if (mutation == null) {
            return;
        }
        minestar.addUserFriend(player.getUUID(), target)
                .onSuccess(request -> net.ledok.eden_ld.util.OnServerThread.run(server, () -> {
                    if (end(player.getUUID(), mutation)) {
                        onDone.done(null, request.status() == FriendRequestStatus.ACCEPTED);
                    }
                }))
                .onFailure(cause -> net.ledok.eden_ld.util.OnServerThread.run(server, () -> {
                    if (end(player.getUUID(), mutation)) {
                        onDone.done(addError(player.getUUID(), target, targetName, cause), false);
                    }
                }));
    }

    /** Removes a friend, or declines/cancels a pending request in either direction. */
    static void remove(ServerPlayer player, UUID target, MinestarHook.FriendCallback onDone) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }
        Mutation mutation = begin(server, player, onDone, "removeUserFriend");
        if (mutation == null) {
            return;
        }
        minestar.removeUserFriend(player.getUUID(), target)
                .onSuccess(unit -> net.ledok.eden_ld.util.OnServerThread.run(server, () -> {
                    if (end(player.getUUID(), mutation)) {
                        onDone.done(null, false);
                    }
                }))
                .onFailure(cause -> net.ledok.eden_ld.util.OnServerThread.run(server, () -> {
                    if (!end(player.getUUID(), mutation)) {
                        return;
                    }
                    int status = status(cause);
                    EdenLD.LOGGER.warn("[Friends] removeUserFriend failed (status {}).", status, cause);
                    onDone.done(status == 429
                            ? Component.translatable("message.eden_ld.friends.rate_limited")
                            : Component.translatable("message.eden_ld.friends.unavailable"), false);
                }));
    }

    /**
     * Takes the player's mutation slot and arms its watchdog; null (after answering "wait") when one is in flight.
     * Server thread.
     */
    @Nullable
    private static Mutation begin(MinecraftServer server, ServerPlayer player, MinestarHook.FriendCallback onDone,
                                  String call) {
        UUID uuid = player.getUUID();
        Mutation mutation = new Mutation();
        if (IN_FLIGHT.putIfAbsent(uuid, mutation) != null) {
            onDone.done(Component.translatable("message.eden_ld.friends.wait"), false);
            return null;
        }
        java.util.concurrent.CompletableFuture.delayedExecutor(MUTATION_WATCHDOG_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
                .execute(() -> net.ledok.eden_ld.util.OnServerThread.run(server, () -> {
                    if (end(uuid, mutation)) {
                        EdenLD.LOGGER.warn("[Friends] {} for {} got no answer in {} s; giving up on it.", call,
                                player.getGameProfile().getName(), MUTATION_WATCHDOG_MS / 1000);
                        onDone.done(Component.translatable("message.eden_ld.friends.unavailable"), false);
                    }
                }));
        return mutation;
    }

    /** Frees the slot if this mutation still holds it; true for the first answer only (later ones are dropped). */
    private static boolean end(UUID uuid, Mutation mutation) {
        if (mutation.done) {
            return false;
        }
        mutation.done = true;
        IN_FLIGHT.remove(uuid, mutation);
        return true;
    }

    private static Component addError(UUID player, UUID target, String targetName, Throwable cause) {
        int status = status(cause);
        return switch (status) {
            case 400 -> Component.translatable("message.eden_ld.friends.invalid", targetName);
            case 404 -> Component.translatable("message.eden_ld.friends.not_found", targetName);
            case 409 -> ProfileCache.friendUuids(player).contains(target)
                    ? Component.translatable("message.eden_ld.friends.already_friends", targetName)
                    : Component.translatable("message.eden_ld.friends.already_pending", targetName);
            case 429 -> Component.translatable("message.eden_ld.friends.rate_limited");
            default -> {
                EdenLD.LOGGER.warn("[Friends] addUserFriend failed (status {}).", status, cause);
                yield Component.translatable("message.eden_ld.friends.unavailable");
            }
        };
    }

    /** HTTP status from a Minestar failure chain, or -1. */
    private static int status(Throwable cause) {
        for (Throwable t = cause; t != null; t = t.getCause()) {
            if (t.getMessage() != null) {
                Matcher matcher = STATUS.matcher(t.getMessage());
                if (matcher.find()) {
                    return Integer.parseInt(matcher.group(1));
                }
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return -1;
    }

    private static FriendSnapshot snapshot(UserProfile profile) {
        List<FriendSnapshot.Friend> friends = new ArrayList<>(profile.friends().size());
        for (UserFriend friend : profile.friends()) {
            List<String> playing = new ArrayList<>(friend.playing().size());
            List<String> playingIds = new ArrayList<>(friend.playing().size());
            for (PackSummary pack : friend.playing()) {
                playing.add(pack.name());
                playingIds.add(pack.id());
            }
            friends.add(new FriendSnapshot.Friend(friend.uuid(), friend.nickname(), friend.online(),
                    friend.lastSeenAt().map(java.time.Instant::toEpochMilli).orElse(-1L),
                    playing, playingIds));
        }
        return new FriendSnapshot(friends, requests(profile.incomingRequests()),
                requests(profile.outgoingRequests()));
    }

    private static List<FriendSnapshot.Request> requests(List<UserFriendRequest> source) {
        List<FriendSnapshot.Request> requests = new ArrayList<>(source.size());
        for (UserFriendRequest request : source) {
            requests.add(new FriendSnapshot.Request(request.uuid(), request.nickname(),
                    request.sentAt().toEpochMilli()));
        }
        return requests;
    }
}
