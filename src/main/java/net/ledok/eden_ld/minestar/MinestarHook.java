package net.ledok.eden_ld.minestar;

import net.fabricmc.loader.api.FabricLoader;
import net.ledok.eden_ld.EdenLD;
import net.minecraft.server.level.ServerPlayer;

import java.util.Optional;
import java.util.UUID;
import java.util.function.IntConsumer;

/**
 * The only Minestar entry point the rest of the mod may touch.
 * It contains no {@code ua.com.minestar} imports, so it is always safe to
 * class-load; everything platform-specific stays behind {@link #LOADED}.
 */
public final class MinestarHook {

    public static final boolean LOADED = FabricLoader.getInstance().isModLoaded("minestar");

    private MinestarHook() {
    }

    public static void initialize() {
        if (!LOADED) {
            EdenLD.LOGGER.info("[Minestar] Mod not present, platform integration disabled.");
            return;
        }
        MinestarIntegration.register();
    }

    public static void claimPurchases(ServerPlayer player) {
        claimPurchase(player, 0);
    }

    /** Delivers one waiting website purchase by id, or every one ({@code 0}). */
    public static void claimPurchase(ServerPlayer player, long productId) {
        // Purchases wait on the website until the run or the tutorial is over.
        if (LOADED && !net.ledok.eden_ld.hub.BusyLocks.refuse(player)) {
            ShopClaim.claim(player, productId);
        }
    }

    /** Cached platform group name, empty without Minestar or before the join fetch lands. */
    public static Optional<String> platformGroup(UUID uuid) {
        return LOADED ? ProfileCache.groupName(uuid) : Optional.empty();
    }

    /** True while the cached profile has an unexpired prime status. */
    public static boolean isPrime(UUID uuid) {
        return LOADED && ProfileCache.prime(uuid);
    }

    /** Uuids of the player's accepted friends; empty without Minestar. */
    public static java.util.Set<UUID> friendUuids(UUID uuid) {
        return LOADED ? ProfileCache.friendUuids(uuid) : java.util.Set.of();
    }

    /** Result of a friend mutation: {@code error} null on success; {@code accepted} when an add formed a friendship. */
    @FunctionalInterface
    public interface FriendCallback {
        void done(@org.jetbrains.annotations.Nullable net.minecraft.network.chat.Component error, boolean accepted);
    }

    /**
     * The player's friends and requests, re-fetched from the platform (throttled unless
     * {@code force}). Answers on the server thread; null without Minestar or when the
     * platform is unreachable and nothing is cached.
     */
    public static void fetchFriends(ServerPlayer player, boolean force,
                                    java.util.function.Consumer<FriendSnapshot> onDone) {
        if (LOADED) {
            FriendService.fetch(player, force, onDone);
        } else {
            onDone.accept(null);
        }
    }

    /** Last cached friends snapshot, no platform call; null without Minestar or before the join fetch. */
    public static FriendSnapshot cachedFriends(UUID uuid) {
        return LOADED ? FriendService.cached(uuid) : null;
    }

    public static void addFriend(ServerPlayer player, UUID target, String targetName, FriendCallback onDone) {
        if (LOADED) {
            FriendService.add(player, target, targetName, onDone);
        }
    }

    public static void removeFriend(ServerPlayer player, UUID target, FriendCallback onDone) {
        if (LOADED) {
            FriendService.remove(player, target, onDone);
        }
    }

    /** The online player's Minestar profile (level, rewards, playtime…); empty without Minestar or before the join fetch. */
    public static Optional<ProfileSnapshot> profile(UUID uuid) {
        if (!LOADED) {
            return DevProfiles.ENABLED ? Optional.of(DevProfiles.fake(uuid)) : Optional.empty();
        }
        return Optional.ofNullable(ProfileService.cached(uuid));
    }

    /**
     * Any player's Minestar profile — online players' from the cache, others' fetched (kept a
     * few minutes). Answers once on the server thread; null without Minestar or when the
     * platform doesn't know the player.
     */
    public static void fetchProfile(net.minecraft.server.MinecraftServer server, UUID uuid,
                                    java.util.function.Consumer<ProfileSnapshot> onDone) {
        if (LOADED) {
            ProfileService.fetch(server, uuid, onDone);
        } else {
            onDone.accept(DevProfiles.ENABLED ? DevProfiles.fake(uuid) : null);
        }
    }

    /** Runs on the server thread after an online player's profile was (re)fetched: join, Friends tab, periodic refresh. */
    public static void onProfileUpdated(java.util.function.BiConsumer<net.minecraft.server.MinecraftServer, UUID> listener) {
        if (LOADED) {
            ProfileService.listen(listener);
        }
    }

    /** Pending shop purchase count; always answers exactly once, on the server thread. */
    public static void fetchPendingShopCount(ServerPlayer player, IntConsumer onCount) {
        fetchPendingPurchases(player, purchases -> onCount.accept(purchases.size()));
    }

    /** The website purchases waiting for the player (none without Minestar); answers once, on the server thread. */
    public static void fetchPendingPurchases(ServerPlayer player,
                                             java.util.function.Consumer<java.util.List<net.ledok.eden_ld.hub.HubPayloads.ShopPurchase>> onPurchases) {
        if (LOADED) {
            ShopClaim.pending(player, onPurchases);
        } else {
            onPurchases.accept(java.util.List.of());
        }
    }
}
