package net.ledok.eden_ld.minestar;

import net.ledok.eden_ld.EdenLD;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;
import ua.com.minestar.model.UserProfile;

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
    private static final List<BiConsumer<MinecraftServer, UUID>> LISTENERS = new CopyOnWriteArrayList<>();
    private static long ticks;

    private record Timed(ProfileSnapshot snapshot, long at) {
    }

    private ProfileService() {
    }

    static void listen(BiConsumer<MinecraftServer, UUID> listener) {
        LISTENERS.add(listener);
    }

    /** A fresh profile of an online player (any thread): cached, then the listeners run on the server thread. */
    static void store(MinecraftServer server, UserProfile profile) {
        ProfileCache.put(profile);
        OTHERS.remove(profile.uuid());
        UUID uuid = profile.uuid();
        server.execute(() -> LISTENERS.forEach(listener -> listener.accept(server, uuid)));
    }

    @Nullable
    static ProfileSnapshot cached(UUID uuid) {
        return ProfileCache.get(uuid).map(ProfileCache::snapshot).orElse(null);
    }

    /**
     * Any player's profile: an online player's from the cache, anyone else's fetched (and kept
     * {@value #OTHERS_TTL_MS} ms). Answers once, on the server thread; null when the platform
     * doesn't know the player or can't be reached.
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
        minestar.getUserProfileByProfileUuid(uuid)
                .onSuccess(profile -> {
                    ProfileSnapshot snapshot = ProfileCache.snapshot(profile);
                    OTHERS.put(uuid, new Timed(snapshot, System.currentTimeMillis()));
                    server.execute(() -> onDone.accept(snapshot));
                })
                .onFailure(cause -> {
                    EdenLD.LOGGER.debug("[Minestar] No profile for {}: {}", uuid, cause.toString());
                    server.execute(() -> onDone.accept(null));
                });
    }

    /** Server tick: every {@code profile.refresh_minutes}, online players' profiles are fetched again. */
    static void tick(MinecraftServer server) {
        int minutes = EdenLD.CONFIG.profile.refresh_minutes;
        if (minutes <= 0 || ++ticks % (minutes * 1200L) != 0) {
            return;
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            minestar.getUserProfileByProfileUuid(player.getUUID())
                    .onSuccess(profile -> store(server, profile))
                    .onFailure(cause -> EdenLD.LOGGER.debug("[Minestar] Profile refresh of {} failed: {}",
                            player.getGameProfile().getName(), cause.toString()));
        }
        OTHERS.values().removeIf(timed -> System.currentTimeMillis() - timed.at() > OTHERS_TTL_MS);
    }
}
