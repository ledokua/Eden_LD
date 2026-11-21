package net.ledok.eden_ld.compat;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.ledok.eden_ld.EdenLD;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Optional LuckPerms reads; everything answers "nothing" without the mod. */
public final class LuckPermsCompat {

    private static final boolean LOADED = FabricLoader.getInstance().isModLoaded("luckperms");

    // Recalculation fan-out (review finding 42): one LuckPerms subscription for all of Eden. Changed users are
    // collected on LuckPerms' threads and handled once per server task batch, and only users who are online count:
    // loading an offline user (GuildMemberClaims' check, PrimeSync) recalculates them too, and each such event used
    // to refresh every online player in six subscribers.
    private static final Set<UUID> CHANGED = ConcurrentHashMap.newKeySet();
    private static final AtomicBoolean DRAIN_QUEUED = new AtomicBoolean();
    private static final AtomicBoolean LIFECYCLE_HOOKED = new AtomicBoolean();
    private static final List<Runnable> LISTENERS = new CopyOnWriteArrayList<>();
    private static final List<Consumer<ServerPlayer>> PLAYER_LISTENERS = new CopyOnWriteArrayList<>();
    /** The live LuckPerms subscription (an {@code EventSubscription}), closed when the server stops. */
    private static volatile AutoCloseable subscription;

    private LuckPermsCompat() {
    }

    /**
     * An integer meta value from the player's cached LuckPerms data (group inheritance and
     * weights resolved by LuckPerms), e.g. {@code /lp group prime meta set <key> 10}.
     */
    public static OptionalInt metaInt(ServerPlayer player, String key) {
        return LOADED ? Hook.metaInt(player, key) : OptionalInt.empty();
    }

    /**
     * A whole-number meta value of any player, online or not: LuckPerms loads an offline user's data first. Completes
     * off the server thread; empty without LuckPerms, when unset or not a number.
     */
    public static java.util.concurrent.CompletableFuture<OptionalInt> loadMetaInt(java.util.UUID player, String key) {
        return LOADED ? Hook.loadMetaInt(player, key) : java.util.concurrent.CompletableFuture.completedFuture(OptionalInt.empty());
    }

    /**
     * A string meta value (group inheritance resolved), e.g.
     * {@code /lp group prime meta set eden_ld.name_color #69118f}; null when unset.
     */
    @org.jetbrains.annotations.Nullable
    public static String metaString(ServerPlayer player, String key) {
        return metaString(player.getUUID(), key);
    }

    /** As {@link #metaString(ServerPlayer, String)}, for a player LuckPerms has loaded (online, or logging in/out). */
    @org.jetbrains.annotations.Nullable
    public static String metaString(java.util.UUID player, String key) {
        return LOADED ? Hook.metaString(player, key) : null;
    }

    /**
     * Whether the player has a permission node (LuckPerms, inheritance resolved). Without
     * LuckPerms, or when the node is unset, operators (level 2) have it.
     */
    public static boolean hasPermission(ServerPlayer player, String node) {
        if (LOADED) {
            Boolean value = Hook.permission(player.getUUID(), node);
            if (value != null) {
                return value;
            }
        }
        return player.hasPermissions(2);
    }

    /** Whether the player has a permission node; {@code fallback} when LuckPerms is missing or the node is unset. */
    public static boolean hasPermissionOr(ServerPlayer player, String node, boolean fallback) {
        if (LOADED) {
            Boolean value = Hook.permission(player.getUUID(), node);
            if (value != null) {
                return value;
            }
        }
        return fallback;
    }

    /**
     * As {@link #hasPermission(ServerPlayer, String)} for a command source: a player's LuckPerms
     * node when set, else the source's op level 2 (the console and command blocks always pass).
     */
    public static boolean hasPermission(net.minecraft.commands.CommandSourceStack source, String node) {
        ServerPlayer player = source.getPlayer();
        if (LOADED && player != null) {
            Boolean value = Hook.permission(player.getUUID(), node);
            if (value != null) {
                return value;
            }
        }
        return source.hasPermission(2);
    }

    /** A boolean meta value ("true"/"false"); {@code fallback} when unset. */
    public static boolean metaBool(java.util.UUID player, String key, boolean fallback) {
        String value = metaString(player, key);
        return value == null ? fallback : Boolean.parseBoolean(value.trim());
    }

    /**
     * The player's rank for sorting: the highest weight among the groups they inherit
     * ({@code /lp group admin setweight 100}); 0 without LuckPerms or weights.
     */
    public static int groupWeight(ServerPlayer player) {
        return LOADED ? Hook.groupWeight(player.getUUID()) : 0;
    }

    /** Prefix and suffix from the player's cached LuckPerms meta ("" when none / no LuckPerms). */
    public static String[] prefixSuffix(ServerPlayer player) {
        return LOADED ? Hook.prefixSuffix(player) : new String[]{"", ""};
    }

    /**
     * Runs {@code onChange} on the server thread after an online player's data was recalculated — group, prefix, or
     * meta edits — once for a whole burst of recalculations; users who aren't online are ignored (review finding 42).
     * Call once the server started (the API is up then); listeners are dropped when the server stops.
     */
    public static void onUserDataChange(Runnable onChange) {
        if (LOADED) {
            LISTENERS.add(onChange);
            subscribe();
        }
    }

    /**
     * As {@link #onUserDataChange(Runnable)}, but per player: {@code onChange} runs on the server thread for each
     * online player whose data was recalculated, once per burst (review finding 42).
     */
    public static void onPlayerDataChange(Consumer<ServerPlayer> onChange) {
        if (LOADED) {
            PLAYER_LISTENERS.add(onChange);
            subscribe();
        }
    }

    private static void subscribe() {
        if (LIFECYCLE_HOOKED.compareAndSet(false, true)) {
            // Singleplayer: the next world gets a fresh LuckPerms and fresh listeners (they register on SERVER_STARTED).
            ServerLifecycleEvents.SERVER_STOPPED.register(stopped -> {
                AutoCloseable live = subscription;
                subscription = null;
                if (live != null) {
                    try {
                        live.close();
                    } catch (Exception ignored) {
                    }
                }
                LISTENERS.clear();
                PLAYER_LISTENERS.clear();
                CHANGED.clear();
                DRAIN_QUEUED.set(false);
            });
        }
        if (subscription == null) {
            synchronized (LuckPermsCompat.class) {
                if (subscription == null) {
                    subscription = Hook.subscribe(LuckPermsCompat::recalculated);
                }
            }
        }
    }

    /** LuckPerms' thread: remember the user and queue one drain on the server thread. */
    private static void recalculated(UUID user) {
        CHANGED.add(user);
        MinecraftServer server = EdenLD.server();
        // Once stopping, server.execute would run the drain right here on LuckPerms' thread (review finding 33).
        if (server == null || !server.isRunning() || server.isStopped()) {
            return;
        }
        if (DRAIN_QUEUED.compareAndSet(false, true)) {
            net.ledok.eden_ld.util.OnServerThread.run(server, () -> drain(server));
        }
    }

    /** Server thread: the listeners, once, when any changed user is online (review finding 42). */
    private static void drain(MinecraftServer server) {
        DRAIN_QUEUED.set(false);
        List<ServerPlayer> online = new ArrayList<>();
        for (Iterator<UUID> it = CHANGED.iterator(); it.hasNext(); ) {
            ServerPlayer player = server.getPlayerList().getPlayer(it.next());
            it.remove();
            if (player != null) {
                online.add(player);
            }
        }
        if (online.isEmpty()) {
            return;
        }
        for (Runnable listener : LISTENERS) {
            try {
                listener.run();
            } catch (RuntimeException e) {
                EdenLD.LOGGER.warn("LuckPerms change listener failed", e);
            }
        }
        for (Consumer<ServerPlayer> listener : PLAYER_LISTENERS) {
            for (ServerPlayer player : online) {
                try {
                    listener.accept(player);
                } catch (RuntimeException e) {
                    EdenLD.LOGGER.warn("LuckPerms change listener failed", e);
                }
            }
        }
    }

    /**
     * Whether the player is in the group, directly or through another group (LuckPerms loaded
     * them — online players); false without LuckPerms.
     */
    public static boolean inGroup(java.util.UUID player, String group) {
        return LOADED && Hook.inGroup(player, group);
    }

    /**
     * Whether LuckPerms has the player loaded (online players, and offline ones something loaded lately), so that
     * {@link #inGroup} answers for them; false without LuckPerms. Local profiles keep group and Prime from the last
     * time this was true (review finding 89).
     */
    public static boolean userLoaded(java.util.UUID player) {
        return LOADED && Hook.userLoaded(player);
    }

    /** LuckPerms group names, highest weight first; empty without LuckPerms. */
    public static java.util.List<String> groupNames() {
        return LOADED ? Hook.groupNames() : java.util.List.of();
    }

    /**
     * Players directly in a group (not through another group), online or not — everyone LuckPerms
     * knows, which is everyone who has joined. Empty without LuckPerms.
     */
    public static java.util.concurrent.CompletableFuture<java.util.Set<java.util.UUID>> directMembers(String group) {
        return LOADED ? Hook.directMembers(group) : java.util.concurrent.CompletableFuture.completedFuture(java.util.Set.of());
    }

    /** The only class touching LuckPerms types. */
    private static final class Hook {
        static java.util.List<String> groupNames() {
            try {
                return net.luckperms.api.LuckPermsProvider.get().getGroupManager().getLoadedGroups().stream()
                        .sorted(java.util.Comparator.comparingInt((net.luckperms.api.model.group.Group g) ->
                                -g.getWeight().orElse(0)).thenComparing(net.luckperms.api.model.group.Group::getName))
                        .map(net.luckperms.api.model.group.Group::getName)
                        .toList();
            } catch (IllegalStateException e) {
                return java.util.List.of();
            }
        }

        static java.util.concurrent.CompletableFuture<java.util.Set<java.util.UUID>> directMembers(String group) {
            try {
                return net.luckperms.api.LuckPermsProvider.get().getUserManager()
                        .searchAll(net.luckperms.api.node.matcher.NodeMatcher.key(
                                net.luckperms.api.node.types.InheritanceNode.builder(group).build()))
                        .thenApply(found -> java.util.Set.copyOf(found.keySet()));
            } catch (IllegalStateException e) {
                return java.util.concurrent.CompletableFuture.completedFuture(java.util.Set.of());
            }
        }

        static boolean userLoaded(java.util.UUID player) {
            try {
                return net.luckperms.api.LuckPermsProvider.get().getUserManager().isLoaded(player);
            } catch (IllegalStateException e) {
                return false;
            }
        }

        static boolean inGroup(java.util.UUID player, String group) {
            try {
                net.luckperms.api.model.user.User user = net.luckperms.api.LuckPermsProvider.get()
                        .getUserManager().getUser(player);
                return user != null && user.getInheritedGroups(user.getQueryOptions()).stream()
                        .anyMatch(g -> g.getName().equalsIgnoreCase(group));
            } catch (IllegalStateException e) {
                return false;
            }
        }

        static String[] prefixSuffix(ServerPlayer player) {
            try {
                net.luckperms.api.model.user.User user = net.luckperms.api.LuckPermsProvider.get()
                        .getUserManager().getUser(player.getUUID());
                if (user == null) {
                    return new String[]{"", ""};
                }
                var meta = user.getCachedData().getMetaData();
                return new String[]{meta.getPrefix() == null ? "" : meta.getPrefix(),
                        meta.getSuffix() == null ? "" : meta.getSuffix()};
            } catch (IllegalStateException e) {
                return new String[]{"", ""};
            }
        }

        /** The subscription, or null when LuckPerms isn't enabled (names stay plain). */
        @org.jetbrains.annotations.Nullable
        static AutoCloseable subscribe(Consumer<UUID> onChange) {
            try {
                return net.luckperms.api.LuckPermsProvider.get().getEventBus().subscribe(
                        net.luckperms.api.event.user.UserDataRecalculateEvent.class,
                        event -> onChange.accept(event.getUser().getUniqueId()));
            } catch (IllegalStateException e) {
                return null;
            }
        }

        /** True/false when set (directly or inherited), null when undefined. */
        static Boolean permission(java.util.UUID player, String node) {
            try {
                net.luckperms.api.model.user.User user = net.luckperms.api.LuckPermsProvider.get()
                        .getUserManager().getUser(player);
                if (user == null) {
                    return null;
                }
                net.luckperms.api.util.Tristate result = user.getCachedData().getPermissionData().checkPermission(node);
                return result == net.luckperms.api.util.Tristate.UNDEFINED ? null : result.asBoolean();
            } catch (IllegalStateException e) {
                return null;
            }
        }

        static String metaString(java.util.UUID player, String key) {
            try {
                net.luckperms.api.model.user.User user = net.luckperms.api.LuckPermsProvider.get()
                        .getUserManager().getUser(player);
                return user == null ? null : user.getCachedData().getMetaData().getMetaValue(key);
            } catch (IllegalStateException e) {
                return null;
            }
        }

        static int groupWeight(java.util.UUID player) {
            try {
                net.luckperms.api.model.user.User user = net.luckperms.api.LuckPermsProvider.get()
                        .getUserManager().getUser(player);
                if (user == null) {
                    return 0;
                }
                int best = 0;
                for (net.luckperms.api.model.group.Group group : user.getInheritedGroups(user.getQueryOptions())) {
                    best = Math.max(best, group.getWeight().orElse(0));
                }
                return best;
            } catch (IllegalStateException e) {
                return 0;
            }
        }

        static java.util.concurrent.CompletableFuture<OptionalInt> loadMetaInt(java.util.UUID player, String key) {
            try {
                return net.luckperms.api.LuckPermsProvider.get().getUserManager().loadUser(player)
                        .thenApply(user -> user.getCachedData().getMetaData().getMetaValue(key, Integer::parseInt)
                                .map(OptionalInt::of).orElse(OptionalInt.empty()))
                        .exceptionally(error -> OptionalInt.empty());
            } catch (IllegalStateException e) {
                return java.util.concurrent.CompletableFuture.completedFuture(OptionalInt.empty());
            }
        }

        static OptionalInt metaInt(ServerPlayer player, String key) {
            try {
                net.luckperms.api.model.user.User user = net.luckperms.api.LuckPermsProvider.get()
                        .getUserManager().getUser(player.getUUID());
                if (user == null) {
                    return OptionalInt.empty();
                }
                return user.getCachedData().getMetaData().getMetaValue(key, Integer::parseInt)
                        .map(OptionalInt::of).orElse(OptionalInt.empty());
            } catch (IllegalStateException | NumberFormatException e) {
                return OptionalInt.empty();
            }
        }
    }
}
