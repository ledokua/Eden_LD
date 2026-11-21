package net.ledok.eden_ld.minestar;

import net.ledok.eden_ld.EdenLD;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;
import ua.com.minestar.model.UserProfile;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static ua.com.minestar.Minestar.minestar;

/**
 * Profiles for the hub's profile pages and levels. Online players' profiles live in
 * {@link ProfileCache} (fetched on join, re-fetched every {@code profile.refresh_minutes} while they
 * play so levels stay current); anyone else's (an offline friend's page) is fetched on demand and
 * kept a few minutes. Every fresh online profile is announced to the listeners on the server
 * thread (level perks).
 */
final class ProfileService {

    private static final long OTHERS_TTL_MS = 5 * 60_000L;
    private static final Map<UUID, Timed> OTHERS = new ConcurrentHashMap<>();
    /**
     * Review finding 36: a fetch already running for a player collects later askers instead of starting another HTTP
     * call, and a player the platform didn't answer for is not asked again for {@value #FAILED_TTL_MS} ms.
     */
    private static final long FAILED_TTL_MS = 45_000L;
    /** A call older than this is treated as lost (the platform client has no timeout): the next asker starts one. */
    private static final long IN_FLIGHT_STALE_MS = 30_000L;
    private static final Map<UUID, InFlight> WAITING = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> FAILED = new ConcurrentHashMap<>();
    private static final List<BiConsumer<MinecraftServer, UUID>> LISTENERS = new CopyOnWriteArrayList<>();
    private static long ticks;

    private record Timed(ProfileSnapshot snapshot, long at) {
    }

    /** One running fetch and who waits for it (changed only inside {@code WAITING.compute}). */
    private static final class InFlight {
        final long started = System.currentTimeMillis();
        final List<Consumer<ProfileSnapshot>> waiters = new ArrayList<>();
    }

    private ProfileService() {
    }

    static void listen(BiConsumer<MinecraftServer, UUID> listener) {
        LISTENERS.add(listener);
    }

    /**
     * A fresh profile of {@code player} (any thread): on the server thread it goes into {@link ProfileCache} and the
     * listeners run, while the player is still online. A fetch that answers after they left keeps the snapshot in the
     * offline cache instead (review finding 50): put back into the "online" cache after the disconnect evicted it, the
     * entry stayed with no TTL and offline viewers saw it until a restart.
     */
    static void store(MinecraftServer server, ServerPlayer player, UserProfile profile) {
        UUID uuid = profile.uuid();
        net.ledok.eden_ld.util.OnServerThread.run(server, () -> {
            FAILED.remove(uuid);
            if (gone(player)) {
                OTHERS.put(uuid, new Timed(ProfileCache.snapshot(profile), System.currentTimeMillis()));
                return;
            }
            ProfileCache.put(profile);
            OTHERS.remove(uuid);
            LISTENERS.forEach(listener -> listener.accept(server, uuid));
        });
    }

    /**
     * Left, or leaving: the disconnect handler may already have run while the player isn't removed yet (review
     * findings 50 and 91), so a closed connection counts as gone too.
     */
    static boolean gone(ServerPlayer player) {
        return player.hasDisconnected() || !player.connection.isAcceptingMessages();
    }

    @Nullable
    static ProfileSnapshot cached(UUID uuid) {
        return ProfileCache.get(uuid).map(ProfileCache::snapshot).orElse(null);
    }

    /**
     * Any player's profile: an online player's from the cache, anyone else's fetched (and kept
     * {@value #OTHERS_TTL_MS} ms). Answers once, on the server thread; null when the platform
     * doesn't know the player or can't be reached (remembered {@value #FAILED_TTL_MS} ms). One HTTP call per player
     * at a time, however many ask (review finding 36).
     */
    static void fetch(MinecraftServer server, UUID uuid, Consumer<ProfileSnapshot> onDone) {
        ProfileSnapshot online = cached(uuid);
        if (online != null) {
            onDone.accept(online);
            return;
        }
        Timed other = OTHERS.get(uuid);
        if (other != null && System.currentTimeMillis() - other.at() < OTHERS_TTL_MS) {
            onDone.accept(other.snapshot());
            return;
        }
        Long failedAt = FAILED.get(uuid);
        if (failedAt != null && System.currentTimeMillis() - failedAt < FAILED_TTL_MS) {
            onDone.accept(null);
            return;
        }
        boolean[] first = {false};
        WAITING.compute(uuid, (key, flight) -> {
            if (flight == null || System.currentTimeMillis() - flight.started > IN_FLIGHT_STALE_MS) {
                InFlight fresh = new InFlight();
                if (flight != null) {
                    fresh.waiters.addAll(flight.waiters);
                }
                fresh.waiters.add(onDone);
                first[0] = true;
                return fresh;
            }
            flight.waiters.add(onDone);
            return flight;
        });
        if (!first[0]) {
            return;
        }
        try {
            minestar.getUserProfileByProfileUuid(uuid)
                    .onSuccess(profile -> {
                        ProfileSnapshot snapshot = ProfileCache.snapshot(profile);
                        OTHERS.put(uuid, new Timed(snapshot, System.currentTimeMillis()));
                        FAILED.remove(uuid);
                        answer(server, uuid, snapshot);
                    })
                    .onFailure(cause -> {
                        EdenLD.LOGGER.debug("[Minestar] No profile for {}: {}", uuid, cause.toString());
                        FAILED.put(uuid, System.currentTimeMillis());
                        answer(server, uuid, null);
                    });
        } catch (RuntimeException e) {
            // Never leave askers waiting on a call that didn't start.
            EdenLD.LOGGER.debug("[Minestar] Profile fetch for {} didn't start: {}", uuid, e.toString());
            FAILED.put(uuid, System.currentTimeMillis());
            answer(server, uuid, null);
        }
    }

    /**
     * Every asker of an in-flight fetch, on the server thread (none once it stopped). Through OnServerThread (review
     * finding 33): {@code execute} ran them on the platform's thread once the server had started stopping.
     */
    private static void answer(MinecraftServer server, UUID uuid, @Nullable ProfileSnapshot snapshot) {
        InFlight flight = WAITING.remove(uuid);
        if (flight == null || server.isStopped()) {
            return;
        }
        net.ledok.eden_ld.util.OnServerThread.run(server, () -> flight.waiters.forEach(waiter -> waiter.accept(snapshot)));
    }

    /** Server tick: every {@code profile.refresh_minutes}, online players' profiles are fetched again. */
    static void tick(MinecraftServer server) {
        int minutes = EdenLD.CONFIG.profile.refresh_minutes;
        if (minutes <= 0 || ++ticks % (minutes * 1200L) != 0) {
            return;
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            minestar.getUserProfileByProfileUuid(player.getUUID())
                    .onSuccess(profile -> store(server, player, profile))
                    .onFailure(cause -> EdenLD.LOGGER.debug("[Minestar] Profile refresh of {} failed: {}",
                            player.getGameProfile().getName(), cause.toString()));
        }
        OTHERS.values().removeIf(timed -> System.currentTimeMillis() - timed.at() > OTHERS_TTL_MS);
        FAILED.values().removeIf(at -> System.currentTimeMillis() - at > FAILED_TTL_MS);
    }
}
