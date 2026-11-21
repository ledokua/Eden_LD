package net.ledok.eden_ld.minestar;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.ledok.eden_ld.EdenLD;
import net.ledok.eden_ld.EdenData;
import net.ledok.eden_ld.compat.LuckPermsCompat;
import net.ledok.eden_ld.hub.activity.ActivityLevels;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;

/**
 * Eden without the Minestar platform (1.3.127, LeDok 2026-11-19: "use this mod almost fully without it"): friends and
 * profiles kept on this server, in {@code data/eden_ld/local_social.dat}. {@link MinestarHook} uses it when
 * {@code minestar.json} {@code mode} is {@code local}, or {@code auto} without the Minestar mod.
 * <ul>
 *   <li><b>Friends:</b> a request the other player accepts (adding someone who already asked you accepts it), declining,
 *   cancelling and removing; "online" means on this server, "last seen" when they last left it.</li>
 *   <li><b>Profile:</b> the level is Eden's activity level ({@code activities.level_category}, LeDok's choice), saved
 *   when a player leaves so offline profiles show it; playtime is this server's; the group is the LuckPerms group shown
 *   in the hub; prime is being in {@code minestar.prime_group}. No platform rewards, no website shop.</li>
 * </ul>
 * Server thread.
 */
public final class LocalSocial extends SavedData {

    private static final Factory<LocalSocial> FACTORY = new Factory<>(LocalSocial::new, LocalSocial::load, null);
    private static final List<BiConsumer<MinecraftServer, UUID>> PROFILE_LISTENERS = new CopyOnWriteArrayList<>();

    /** Accepted friendships, both ways. */
    private final Map<UUID, Set<UUID>> friends = new HashMap<>();
    /** Requests waiting: target → (sender → sent at). */
    private final Map<UUID, Map<UUID, Long>> requests = new HashMap<>();
    /**
     * The same requests by sender: sender → targets. Not saved (rebuilt in {@link #load}); kept so a snapshot reads
     * the player's own requests instead of scanning every request on the server (review finding 23).
     */
    private final Map<UUID, Set<UUID>> outgoing = new HashMap<>();
    /** How many requests are waiting in all (review finding 23). */
    private int requestCount;

    /**
     * Review finding 23: a modified client could send ADD for any number of random uuids, each one a permanent request
     * saved to local_social.dat and scanned by every friends snapshot. A player may have this many requests waiting
     * at once (they can cancel some, or wait for answers).
     */
    private static final int MAX_OUTGOING_REQUESTS = 50;
    /** Review finding 23: past this many requests waiting on the whole server, new ones are refused. */
    private static final int MAX_REQUESTS_TOTAL = 10_000;
    /** Review finding 23: a request nobody answered goes after this long (checked hourly and at load). */
    private static final long REQUEST_EXPIRY_MS = 30L * 24 * 60 * 60 * 1000;
    private static final int EXPIRY_SWEEP_TICKS = 20 * 60 * 60;
    /** When the "too many requests on the server" warning was last logged (once a minute at most). */
    private static long requestsFullLogged;
    /** What we know of each player: name, first and last seen, level, playtime, group and Prime when they last left. */
    private final Map<UUID, Known> known = new HashMap<>();

    private static final class Known {
        String name = "";
        /** -1: unknown (they played here before local mode and their files don't say since when). */
        long firstSeen;
        long lastSeen;
        int level;
        long playtimeSeconds;
        /**
         * Group and Prime as LuckPerms last had them (review finding 89): LuckPerms keeps only online users loaded, so
         * offline profiles showed neither. A Prime that runs out while they're away shows until they come back.
         */
        String group = "";
        boolean prime;
    }

    static LocalSocial get(MinecraftServer server) {
        return EdenData.get(server, FACTORY, "local_social");
    }

    /** Join and leave keep names, last-seen, level and playtime; profiles refresh as the platform's do. */
    static void initialize() {
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> server.execute(() -> {
            ServerPlayer player = handler.getPlayer();
            if (!player.hasDisconnected()) {
                get(server).seen(player, false);
                profileUpdated(server, player.getUUID());
            }
        }));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> net.ledok.eden_ld.util.OnServerThread.run(server,
                () -> get(server).seen(handler.getPlayer(), true)));
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (server.getTickCount() % EXPIRY_SWEEP_TICKS == 0 && get(server).expireRequests()) {
                get(server).setDirty();
            }
            int minutes = EdenLD.CONFIG.profile.refresh_minutes;
            if (minutes > 0 && server.getTickCount() % (minutes * 60 * 20) == 0) {
                for (ServerPlayer online : server.getPlayerList().getPlayers()) {
                    profileUpdated(server, online.getUUID());
                }
            }
        });
        EdenLD.LOGGER.info("[Minestar] Local mode: friends and profiles are kept on this server.");
    }

    private static void profileUpdated(MinecraftServer server, UUID player) {
        for (BiConsumer<MinecraftServer, UUID> listener : PROFILE_LISTENERS) {
            listener.accept(server, player);
        }
    }

    static void listen(BiConsumer<MinecraftServer, UUID> listener) {
        PROFILE_LISTENERS.add(listener);
    }

    private void seen(ServerPlayer player, boolean leaving) {
        long now = System.currentTimeMillis();
        Known entry = known.computeIfAbsent(player.getUUID(), uuid -> new Known());
        entry.name = player.getGameProfile().getName();
        entry.playtimeSeconds = player.getStats().getValue(Stats.CUSTOM.get(Stats.PLAY_TIME)) / 20L;
        if (!leaving) {
            entry.firstSeen = firstSeen(player, entry.firstSeen, entry.playtimeSeconds, now);
        }
        entry.lastSeen = now;
        entry.level = ActivityLevels.level(player);
        if (LuckPermsCompat.userLoaded(player.getUUID())) {
            entry.group = groupOf(player.getUUID());
            entry.prime = isPrime(player.getUUID());
        }
        setDirty();
    }

    /**
     * When the player first played here (review finding 89): "member since" used to be their first join after local
     * mode came (1.3.127), even for long-time players. Their stats and advancements files are written in place, so
     * the older of their creation times is their first join, when the filesystem keeps creation times. A file time
     * younger than their playtime can't be right (no creation time, only the last write): unknown (-1, the line is
     * hidden) unless they are new here. Two file stats per join, on the server thread.
     * <p>
     * Review of 1.3.133: on JDK 21 / Linux {@code creationTime()} without a birth time returns the last write, so a
     * creation time equal to the last-modified time counts as unknown; and a stored date their playtime doesn't fit in
     * (written that way by 1.3.127–1.3.132) is dropped and worked out again.
     */
    private static long firstSeen(ServerPlayer player, long known, long playtimeSeconds, long now) {
        if (!plausible(known, playtimeSeconds, now)) {
            known = -1;
        }
        long oldest = known > 0 ? known : now;
        String file = player.getUUID() + ".json";
        for (net.minecraft.world.level.storage.LevelResource dir : List.of(
                net.minecraft.world.level.storage.LevelResource.PLAYER_STATS_DIR,
                net.minecraft.world.level.storage.LevelResource.PLAYER_ADVANCEMENTS_DIR)) {
            try {
                java.nio.file.attribute.BasicFileAttributes attributes = java.nio.file.Files.readAttributes(
                        player.server.getWorldPath(dir).resolve(file),
                        java.nio.file.attribute.BasicFileAttributes.class);
                long created = attributes.creationTime().toMillis();
                if (created == attributes.lastModifiedTime().toMillis()) {
                    // No birth time on this filesystem: the JDK reports the last write instead.
                    continue;
                }
                if (created > 0 && now - created >= playtimeSeconds * 1000L) {
                    oldest = Math.min(oldest, created);
                }
            } catch (java.io.IOException | UnsupportedOperationException ignored) {
                // No file yet (a new player), or no times: keep what we have.
            }
        }
        if (oldest == now && known == 0 && playtimeSeconds > 60 * 60) {
            // Played here for an hour or more before we could see it, and the files don't say since when.
            return -1;
        }
        return known == -1 && oldest == now ? -1 : oldest;
    }

    /** False for a known first-seen date the playtime doesn't fit in (it can't be right); unknown ones pass. */
    private static boolean plausible(long firstSeen, long playtimeSeconds, long now) {
        return firstSeen <= 0 || playtimeSeconds * 1000L <= now - firstSeen;
    }

    // ---------------------------------------------------------------- friends

    static Set<UUID> friendUuids(MinecraftServer server, UUID player) {
        Set<UUID> set = get(server).friends.get(player);
        return set == null ? Set.of() : java.util.Collections.unmodifiableSet(set);
    }

    /** The player's friends and requests as the platform would give them. */
    static FriendSnapshot snapshot(MinecraftServer server, UUID player) {
        LocalSocial data = get(server);
        List<FriendSnapshot.Friend> list = new ArrayList<>();
        for (UUID friend : data.friends.getOrDefault(player, Set.of())) {
            boolean online = server.getPlayerList().getPlayer(friend) != null;
            Known entry = data.known.get(friend);
            list.add(new FriendSnapshot.Friend(friend, data.name(server, friend), online,
                    online ? System.currentTimeMillis() : entry == null ? -1 : entry.lastSeen, List.of(), List.of()));
        }
        List<FriendSnapshot.Request> incoming = new ArrayList<>();
        data.requests.getOrDefault(player, Map.of()).forEach((from, at) ->
                incoming.add(new FriendSnapshot.Request(from, data.name(server, from), at)));
        List<FriendSnapshot.Request> outgoing = new ArrayList<>();
        for (UUID to : data.outgoing.getOrDefault(player, Set.of())) {
            Long at = data.requests.getOrDefault(to, Map.of()).get(player);
            if (at != null) {
                outgoing.add(new FriendSnapshot.Request(to, data.name(server, to), at));
            }
        }
        return new FriendSnapshot(list, incoming, outgoing);
    }

    /** Sends a request, or accepts the target's; null when done (with whether it made a friendship), else why not. */
    static void add(ServerPlayer player, UUID target, String targetName, MinestarHook.FriendCallback onDone) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }
        LocalSocial data = get(server);
        UUID self = player.getUUID();
        if (data.friends.getOrDefault(self, Set.of()).contains(target)) {
            onDone.done(Component.translatable("message.eden_ld.friends.already_friends", targetName), false);
            return;
        }
        if (data.requests.getOrDefault(self, Map.of()).containsKey(target)) {
            // They asked first: this accepts. The limit is checked before the request is used up, so a full list
            // leaves it waiting.
            int limit = EdenLD.CONFIG.minestar.local_friend_limit;
            if (limit > 0 && (data.friends.getOrDefault(self, Set.of()).size() >= limit
                    || data.friends.getOrDefault(target, Set.of()).size() >= limit)) {
                onDone.done(Component.translatable("message.eden_ld.friends.limit", limit), false);
                return;
            }
            data.removeRequest(self, target);
            data.friends.computeIfAbsent(self, k -> new LinkedHashSet<>()).add(target);
            data.friends.computeIfAbsent(target, k -> new LinkedHashSet<>()).add(self);
            data.setDirty();
            onDone.done(null, true);
            return;
        }
        if (data.requests.getOrDefault(target, Map.of()).containsKey(self)) {
            onDone.done(Component.translatable("message.eden_ld.friends.already_pending", targetName), false);
            return;
        }
        // Review finding 23: only someone this server knows (online, seen here, or in the server's profile cache, all
        // without a web call), so random uuids can't fill the file; their name isn't taken from the request.
        if (!data.knows(server, target)) {
            onDone.done(Component.translatable("message.eden_ld.friends.invalid", targetName), false);
            return;
        }
        if (data.outgoing.getOrDefault(self, Set.of()).size() >= MAX_OUTGOING_REQUESTS) {
            // Their own old requests may have run out since the last sweep.
            if (data.expireRequests()) {
                data.setDirty();
            }
            if (data.outgoing.getOrDefault(self, Set.of()).size() >= MAX_OUTGOING_REQUESTS) {
                onDone.done(Component.translatable("message.eden_ld.friends.too_many_requests",
                        MAX_OUTGOING_REQUESTS), false);
                return;
            }
        }
        if (data.requestCount >= MAX_REQUESTS_TOTAL) {
            long now = System.currentTimeMillis();
            if (now - requestsFullLogged >= 60_000) {
                requestsFullLogged = now;
                EdenLD.LOGGER.warn("[Minestar] Local mode: {} friend requests are waiting on this server; refusing new "
                        + "ones until some are answered or expire.", data.requestCount);
            }
            onDone.done(Component.translatable("message.eden_ld.friends.requests_full"), false);
            return;
        }
        data.putRequest(target, self, System.currentTimeMillis());
        data.setDirty();
        onDone.done(null, false);
    }

    /** Online, seen on this server under a name, or in the server's profile cache (memory only, no web lookup). */
    private boolean knows(MinecraftServer server, UUID uuid) {
        if (server.getPlayerList().getPlayer(uuid) != null) {
            return true;
        }
        Known entry = known.get(uuid);
        if (entry != null && !entry.name.isEmpty()) {
            return true;
        }
        return server.getProfileCache() != null && server.getProfileCache().get(uuid).isPresent();
    }

    private void putRequest(UUID to, UUID from, long at) {
        if (requests.computeIfAbsent(to, k -> new LinkedHashMap<>()).put(from, at) == null) {
            requestCount++;
        }
        outgoing.computeIfAbsent(from, k -> new LinkedHashSet<>()).add(to);
    }

    private boolean removeRequest(UUID to, UUID from) {
        Map<UUID, Long> inner = requests.get(to);
        if (inner == null || inner.remove(from) == null) {
            return false;
        }
        if (inner.isEmpty()) {
            requests.remove(to);
        }
        requestCount--;
        removeFrom(outgoing, from, to);
        return true;
    }

    /** Drops requests older than {@link #REQUEST_EXPIRY_MS}; true when any went. */
    private boolean expireRequests() {
        long cutoff = System.currentTimeMillis() - REQUEST_EXPIRY_MS;
        List<UUID[]> old = new ArrayList<>();
        requests.forEach((to, from) -> from.forEach((sender, at) -> {
            if (at < cutoff) {
                old.add(new UUID[]{to, sender});
            }
        }));
        for (UUID[] pair : old) {
            removeRequest(pair[0], pair[1]);
        }
        return !old.isEmpty();
    }

    /** Removes a friend, or declines or cancels a request in either direction. */
    static void remove(ServerPlayer player, UUID target, MinestarHook.FriendCallback onDone) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }
        LocalSocial data = get(server);
        UUID self = player.getUUID();
        boolean changed = removeFrom(data.friends, self, target) | removeFrom(data.friends, target, self);
        changed |= data.removeRequest(self, target) | data.removeRequest(target, self);
        if (changed) {
            data.setDirty();
        }
        onDone.done(null, false);
    }

    private static boolean removeFrom(Map<UUID, Set<UUID>> map, UUID owner, UUID other) {
        Set<UUID> set = map.get(owner);
        if (set == null || !set.remove(other)) {
            return false;
        }
        if (set.isEmpty()) {
            map.remove(owner);
        }
        return true;
    }

    // ---------------------------------------------------------------- profiles

    /**
     * The local profile: online players' now, others' as they last left. Someone not seen here since local mode came
     * (1.3.127) gets a minimal profile (name, LuckPerms group if loaded, level 0) instead of none, which read as
     * "Minestar data isn't available" (review finding 89); the hub shows this server's playtime from their stats.
     */
    static ProfileSnapshot profile(MinecraftServer server, UUID uuid) {
        LocalSocial data = get(server);
        ServerPlayer online = server.getPlayerList().getPlayer(uuid);
        Known entry = data.known.get(uuid);
        String name = online != null ? online.getGameProfile().getName()
                : entry != null && !entry.name.isEmpty() ? entry.name : data.name(server, uuid);
        int level = online != null ? ActivityLevels.level(online) : entry == null ? 0 : entry.level;
        long playtime = online != null ? online.getStats().getValue(Stats.CUSTOM.get(Stats.PLAY_TIME)) / 20L
                : entry == null ? 0 : entry.playtimeSeconds;
        // Live while LuckPerms has them loaded; otherwise as last seen (review finding 89).
        boolean live = online != null || entry == null || LuckPermsCompat.userLoaded(uuid);
        String group = live ? groupOf(uuid) : entry.group;
        boolean prime = live ? isPrime(uuid) : entry.prime;
        // A stored date their playtime doesn't fit in is hidden (review of 1.3.133: 1.3.127–1.3.132 wrote some).
        long firstSeen = entry == null || !plausible(entry.firstSeen, playtime, System.currentTimeMillis()) ? -1
                : entry.firstSeen;
        return new ProfileSnapshot(name, group, -1, prime, firstSeen, playtime,
                level, 0, 0, 0, 0, List.of(), friendUuids(server, uuid));
    }

    /** The highest-weight LuckPerms group the player is in ("" without LuckPerms or a group). */
    static String groupOf(UUID uuid) {
        for (String group : LuckPermsCompat.groupNames()) {
            if (!"default".equals(group) && LuckPermsCompat.inGroup(uuid, group)) {
                return group;
            }
        }
        return "";
    }

    /** Prime is being in {@code minestar.prime_group} (LuckPerms). */
    static boolean isPrime(UUID uuid) {
        String group = EdenLD.CONFIG.minestar.prime_group;
        return group != null && !group.isBlank() && LuckPermsCompat.inGroup(uuid, group);
    }

    private String name(MinecraftServer server, UUID uuid) {
        ServerPlayer online = server.getPlayerList().getPlayer(uuid);
        if (online != null) {
            return online.getGameProfile().getName();
        }
        Known entry = known.get(uuid);
        if (entry != null && !entry.name.isEmpty()) {
            return entry.name;
        }
        return server.getProfileCache() == null ? "?" : server.getProfileCache().get(uuid)
                .map(com.mojang.authlib.GameProfile::getName).orElse("?");
    }

    // ---------------------------------------------------------------- saving

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag friendList = new ListTag();
        friends.forEach((owner, set) -> {
            for (UUID other : set) {
                // Each pair once.
                if (owner.compareTo(other) < 0) {
                    CompoundTag pair = new CompoundTag();
                    pair.putUUID("a", owner);
                    pair.putUUID("b", other);
                    friendList.add(pair);
                }
            }
        });
        tag.put("friends", friendList);
        ListTag requestList = new ListTag();
        requests.forEach((to, from) -> from.forEach((sender, at) -> {
            CompoundTag request = new CompoundTag();
            request.putUUID("to", to);
            request.putUUID("from", sender);
            request.putLong("at", at);
            requestList.add(request);
        }));
        tag.put("requests", requestList);
        ListTag knownList = new ListTag();
        known.forEach((uuid, entry) -> {
            CompoundTag k = new CompoundTag();
            k.putUUID("uuid", uuid);
            k.putString("name", entry.name);
            k.putLong("first", entry.firstSeen);
            k.putLong("last", entry.lastSeen);
            k.putInt("level", entry.level);
            k.putLong("playtime", entry.playtimeSeconds);
            k.putString("group", entry.group);
            k.putBoolean("prime", entry.prime);
            knownList.add(k);
        });
        tag.put("known", knownList);
        return tag;
    }

    private static LocalSocial load(CompoundTag tag, HolderLookup.Provider registries) {
        LocalSocial data = new LocalSocial();
        for (Tag t : tag.getList("friends", Tag.TAG_COMPOUND)) {
            CompoundTag pair = (CompoundTag) t;
            if (pair.hasUUID("a") && pair.hasUUID("b")) {
                UUID a = pair.getUUID("a");
                UUID b = pair.getUUID("b");
                data.friends.computeIfAbsent(a, k -> new LinkedHashSet<>()).add(b);
                data.friends.computeIfAbsent(b, k -> new LinkedHashSet<>()).add(a);
            }
        }
        for (Tag t : tag.getList("requests", Tag.TAG_COMPOUND)) {
            CompoundTag request = (CompoundTag) t;
            if (request.hasUUID("to") && request.hasUUID("from")) {
                data.putRequest(request.getUUID("to"), request.getUUID("from"), request.getLong("at"));
            }
        }
        for (Tag t : tag.getList("known", Tag.TAG_COMPOUND)) {
            CompoundTag k = (CompoundTag) t;
            if (k.hasUUID("uuid")) {
                Known entry = new Known();
                entry.name = k.getString("name");
                entry.firstSeen = k.getLong("first");
                entry.lastSeen = k.getLong("last");
                entry.level = k.getInt("level");
                entry.playtimeSeconds = k.getLong("playtime");
                entry.group = k.getString("group");
                entry.prime = k.getBoolean("prime");
                data.known.put(k.getUUID("uuid"), entry);
            }
        }
        // Review finding 23: requests nobody answered in time go (saved without them at the next save).
        if (data.expireRequests()) {
            data.setDirty();
        }
        return data;
    }
}
