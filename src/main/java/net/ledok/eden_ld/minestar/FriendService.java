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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static ua.com.minestar.Minestar.minestar;

/**
 * Friend reads and mutations against the platform. Every callback lands on the
 * server thread. Profile re-fetches are throttled per player, since the Friends
 * tab asks for one each time it opens.
 */
final class FriendService {

    private static final long REFETCH_THROTTLE_MS = 15_000;
    /** Minestar reports HTTP failures as "... received 409 with message ...". */
    private static final Pattern STATUS = Pattern.compile("received (\\d{3})");

    private static final Map<UUID, Long> LAST_FETCH = new ConcurrentHashMap<>();
    private static final Set<UUID> IN_FLIGHT = ConcurrentHashMap.newKeySet();

    private FriendService() {
    }

    static void forget(UUID uuid) {
        LAST_FETCH.remove(uuid);
        IN_FLIGHT.remove(uuid);
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
        minestar.getUserProfileByProfileUuid(uuid)
                .onSuccess(profile -> {
                    ProfileService.store(server, profile);
                    LAST_FETCH.put(uuid, System.currentTimeMillis());
                    FriendSnapshot snapshot = snapshot(profile);
                    server.execute(() -> onDone.accept(snapshot));
                })
                .onFailure(cause -> {
                    EdenLD.LOGGER.warn("[Friends] Failed to refresh the profile of {}.",
                            player.getGameProfile().getName(), cause);
                    // A stale snapshot beats an empty tab.
                    FriendSnapshot fallback = cached == null ? null : snapshot(cached);
                    server.execute(() -> onDone.accept(fallback));
                });
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
        if (!IN_FLIGHT.add(player.getUUID())) {
            onDone.done(Component.translatable("message.eden_ld.friends.wait"), false);
            return;
        }
        minestar.addUserFriend(player.getUUID(), target)
                .onSuccess(request -> server.execute(() -> {
                    IN_FLIGHT.remove(player.getUUID());
                    onDone.done(null, request.status() == FriendRequestStatus.ACCEPTED);
                }))
                .onFailure(cause -> server.execute(() -> {
                    IN_FLIGHT.remove(player.getUUID());
                    onDone.done(addError(player.getUUID(), target, targetName, cause), false);
                }));
    }

    /** Removes a friend, or declines/cancels a pending request in either direction. */
    static void remove(ServerPlayer player, UUID target, MinestarHook.FriendCallback onDone) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }
        if (!IN_FLIGHT.add(player.getUUID())) {
            onDone.done(Component.translatable("message.eden_ld.friends.wait"), false);
            return;
        }
        minestar.removeUserFriend(player.getUUID(), target)
                .onSuccess(unit -> server.execute(() -> {
                    IN_FLIGHT.remove(player.getUUID());
                    onDone.done(null, false);
                }))
                .onFailure(cause -> server.execute(() -> {
                    IN_FLIGHT.remove(player.getUUID());
                    int status = status(cause);
                    EdenLD.LOGGER.warn("[Friends] removeUserFriend failed (status {}).", status, cause);
                    onDone.done(status == 429
                            ? Component.translatable("message.eden_ld.friends.rate_limited")
                            : Component.translatable("message.eden_ld.friends.unavailable"), false);
                }));
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
