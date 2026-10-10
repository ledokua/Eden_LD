package net.ledok.eden_ld.compat;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.level.ServerPlayer;

import java.util.OptionalInt;

/** Optional LuckPerms reads; everything answers "nothing" without the mod. */
public final class LuckPermsCompat {

    private static final boolean LOADED = FabricLoader.getInstance().isModLoaded("luckperms");

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
     * Runs {@code onChange} (on LuckPerms' thread) whenever a user's data is recalculated —
     * group, prefix, or meta edits. Call once the server started (the API is up then).
     */
    public static void onUserDataChange(Runnable onChange) {
        if (LOADED) {
            Hook.subscribe(onChange);
        }
    }

    /**
     * Whether the player is in the group, directly or through another group (LuckPerms loaded
     * them — online players); false without LuckPerms.
     */
    public static boolean inGroup(java.util.UUID player, String group) {
        return LOADED && Hook.inGroup(player, group);
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

        static void subscribe(Runnable onChange) {
            try {
                net.luckperms.api.LuckPermsProvider.get().getEventBus().subscribe(
                        net.luckperms.api.event.user.UserDataRecalculateEvent.class, event -> onChange.run());
            } catch (IllegalStateException e) {
                // LuckPerms not enabled; names stay plain.
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
