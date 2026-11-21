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
 * class-load; everything platform-specific stays behind {@link #platform()}.
 * <p>
 * Local mode (1.3.127): with {@code minestar.json} {@code mode} {@code local}, or {@code auto} without the Minestar mod,
 * friends and profiles come from {@link LocalSocial} instead (kept on this server), so the hub works without the
 * platform; the website shop and prime syncing are platform-only.
 */
public final class MinestarHook {

    public static final boolean LOADED = FabricLoader.getInstance().isModLoaded("minestar");

    private MinestarHook() {
    }

    /** The Minestar platform is used: the mod is installed and {@code mode} isn't {@code local}. */
    public static boolean platform() {
        return LOADED && !"local".equalsIgnoreCase(mode());
    }

    /** Friends and profiles come from this server ({@link LocalSocial}): {@code local}, or {@code auto} without the mod. */
    public static boolean local() {
        String mode = mode();
        return "local".equalsIgnoreCase(mode) || !LOADED && !"platform".equalsIgnoreCase(mode);
    }

    /** Friends (and their requests) are available, from the platform or locally. */
    public static boolean social() {
        return platform() || local();
    }

    private static String mode() {
        String mode = EdenLD.CONFIG.minestar.mode;
        return mode == null ? "auto" : mode;
    }

    public static void initialize() {
        if (platform()) {
            MinestarIntegration.register();
            return;
        }
        if (local()) {
            LocalSocial.initialize();
            return;
        }
        EdenLD.LOGGER.info("[Minestar] Mod not present and mode \"platform\": friends and profiles are off.");
    }

    @org.jetbrains.annotations.Nullable
    private static net.minecraft.server.MinecraftServer server() {
        return EdenLD.server();
    }

    public static void claimPurchases(ServerPlayer player) {
        claimPurchase(player, 0);
    }

    /** Delivers one waiting website purchase by id, or every one ({@code 0}). */
    public static void claimPurchase(ServerPlayer player, long productId) {
        // Purchases wait on the website until the run or the tutorial is over.
        if (platform() && !net.ledok.eden_ld.hub.BusyLocks.refuse(player)) {
            ShopClaim.claim(player, productId);
        }
    }

    /** Cached platform group name, empty without Minestar or before the join fetch lands. */
    public static Optional<String> platformGroup(UUID uuid) {
        if (platform()) {
            return ProfileCache.groupName(uuid);
        }
        if (local()) {
            String group = LocalSocial.groupOf(uuid);
            return group.isEmpty() ? Optional.empty() : Optional.of(group);
        }
        return Optional.empty();
    }

    /** True while the cached profile has an unexpired prime status. */
    public static boolean isPrime(UUID uuid) {
        return platform() ? ProfileCache.prime(uuid) : local() && LocalSocial.isPrime(uuid);
    }

    /** Uuids of the player's accepted friends; empty without Minestar. */
    public static java.util.Set<UUID> friendUuids(UUID uuid) {
        if (platform()) {
            return ProfileCache.friendUuids(uuid);
        }
        net.minecraft.server.MinecraftServer server = server();
        return local() && server != null ? LocalSocial.friendUuids(server, uuid) : java.util.Set.of();
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
        if (platform()) {
            FriendService.fetch(player, force, onDone);
        } else if (local() && player.getServer() != null) {
            onDone.accept(LocalSocial.snapshot(player.getServer(), player.getUUID()));
        } else {
            onDone.accept(null);
        }
    }

    /** Last cached friends snapshot, no platform call; null without Minestar or before the join fetch. */
    public static FriendSnapshot cachedFriends(UUID uuid) {
        if (platform()) {
            return FriendService.cached(uuid);
        }
        net.minecraft.server.MinecraftServer server = server();
        return local() && server != null ? LocalSocial.snapshot(server, uuid) : null;
    }

    public static void addFriend(ServerPlayer player, UUID target, String targetName, FriendCallback onDone) {
        if (platform()) {
            FriendService.add(player, target, targetName, onDone);
        } else if (local()) {
            LocalSocial.add(player, target, targetName, onDone);
        }
    }

    public static void removeFriend(ServerPlayer player, UUID target, FriendCallback onDone) {
        if (platform()) {
            FriendService.remove(player, target, onDone);
        } else if (local()) {
            LocalSocial.remove(player, target, onDone);
        }
    }

    /** The online player's Minestar profile (level, rewards, playtime…); empty without Minestar or before the join fetch. */
    public static Optional<ProfileSnapshot> profile(UUID uuid) {
        if (platform()) {
            return Optional.ofNullable(ProfileService.cached(uuid));
        }
        if (DevProfiles.ENABLED) {
            return Optional.of(DevProfiles.fake(uuid));
        }
        net.minecraft.server.MinecraftServer server = server();
        return local() && server != null ? Optional.ofNullable(LocalSocial.profile(server, uuid)) : Optional.empty();
    }

    /**
     * Any player's Minestar profile — online players' from the cache, others' fetched (kept a
     * few minutes). Answers once on the server thread; null without Minestar or when the
     * platform doesn't know the player.
     */
    public static void fetchProfile(net.minecraft.server.MinecraftServer server, UUID uuid,
                                    java.util.function.Consumer<ProfileSnapshot> onDone) {
        if (platform()) {
            ProfileService.fetch(server, uuid, onDone);
        } else if (DevProfiles.ENABLED) {
            onDone.accept(DevProfiles.fake(uuid));
        } else {
            onDone.accept(local() ? LocalSocial.profile(server, uuid) : null);
        }
    }

    /** Runs on the server thread after an online player's profile was (re)fetched: join, Friends tab, periodic refresh. */
    public static void onProfileUpdated(java.util.function.BiConsumer<net.minecraft.server.MinecraftServer, UUID> listener) {
        if (platform()) {
            ProfileService.listen(listener);
        } else if (local()) {
            LocalSocial.listen(listener);
        }
    }

    /** Pending shop purchase count; always answers exactly once, on the server thread. */
    public static void fetchPendingShopCount(ServerPlayer player, IntConsumer onCount) {
        fetchPendingPurchases(player, purchases -> onCount.accept(purchases.size()));
    }

    /** The website purchases waiting for the player (none without Minestar); answers once, on the server thread. */
    public static void fetchPendingPurchases(ServerPlayer player,
                                             java.util.function.Consumer<java.util.List<net.ledok.eden_ld.hub.HubPayloads.ShopPurchase>> onPurchases) {
        if (platform()) {
            // Answered once: by the platform, or (1.3.102) after 3 s without an answer with nothing waiting, so an
            // unreachable website never keeps the hub's Home and Mail from coming (both wait on this).
            net.minecraft.server.MinecraftServer server = player.getServer();
            java.util.concurrent.atomic.AtomicBoolean answered = new java.util.concurrent.atomic.AtomicBoolean();
            ShopClaim.pending(player, purchases -> {
                if (answered.compareAndSet(false, true)) {
                    onPurchases.accept(purchases);
                }
            });
            if (server != null) {
                java.util.concurrent.CompletableFuture.delayedExecutor(3, java.util.concurrent.TimeUnit.SECONDS)
                        .execute(() -> net.ledok.eden_ld.util.OnServerThread.run(server, () -> {
                            if (answered.compareAndSet(false, true)) {
                                EdenLD.LOGGER.warn("[Shop] The platform gave no answer about {}'s website purchases "
                                        + "in 3 s; going on without them.", player.getGameProfile().getName());
                                onPurchases.accept(java.util.List.of());
                            }
                        }));
            }
        } else {
            onPurchases.accept(java.util.List.of());
        }
    }
}
