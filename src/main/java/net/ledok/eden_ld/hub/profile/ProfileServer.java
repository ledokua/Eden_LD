package net.ledok.eden_ld.hub.profile;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.ledok.eden_ld.EdenLD;
import net.ledok.eden_ld.hub.HubPayloads;
import net.ledok.eden_ld.hub.HubSettings;
import net.ledok.eden_ld.hub.guild.Guild;
import net.ledok.eden_ld.hub.guild.GuildManager;
import net.ledok.eden_ld.minestar.MinestarHook;
import net.ledok.eden_ld.minestar.ProfileSnapshot;
import net.minecraft.Util;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.ServerStatsCounter;
import net.minecraft.stats.Stats;
import net.minecraft.world.level.storage.LevelResource;
import org.jetbrains.annotations.Nullable;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongConsumer;

/**
 * Profile pages: a player's own on the Profile tab, anyone else's from a Profile button (Friends,
 * party and guild members) — if their Settings → Privacy allows it. Minestar data (level, rewards,
 * all-servers playtime, registration) comes from the cached profile for online players and a
 * short-lived fetch for anyone else; Eden adds this server's playtime, the guild and the chosen
 * background.
 */
public final class ProfileServer {

    /**
     * Offline players' playtime from their statistics file, kept {@value #PLAYTIME_TTL_MS} ms ("unknown" too) and read
     * off the server thread, one read per player at a time (review finding 36: every ProfileRequest used to read and
     * parse the whole file on the server thread). Dropped when the player leaves, since leaving rewrites the file.
     */
    private static final long PLAYTIME_TTL_MS = 5 * 60_000L;
    private static final int PLAYTIME_CACHE_MAX = 2048;
    private static final Map<UUID, Playtime> PLAYTIME = new ConcurrentHashMap<>();
    private static final Map<UUID, CompletableFuture<Long>> PLAYTIME_LOADING = new ConcurrentHashMap<>();

    private record Playtime(long ticks, long at) {
    }

    private ProfileServer() {
    }

    public static void initialize() {
        ProfileLevels.initialize();
        ServerPlayNetworking.registerGlobalReceiver(HubPayloads.ProfileRequest.TYPE,
                (payload, context) -> {
                    // Silent: the client re-asks on every name-style or ignore push while the Profile tab is open,
                    // so a refusal is no sign of abuse (review finding 36 follow-up).
                    if (net.ledok.eden_ld.hub.HubRateLimit.PROFILE_REQUEST.tryAcquire(context.player())) {
                        send(context.player(), payload.target());
                    }
                });
        ServerPlayNetworking.registerGlobalReceiver(HubPayloads.BackgroundSelect.TYPE, (payload, context) -> {
            ServerPlayer player = context.player();
            MinecraftServer server = player.getServer();
            if (server != null && HubSettings.selectBackground(server, player.getUUID(), payload.background())) {
                send(player, player.getUUID());
                Wardrobe.send(player);
            }
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                PLAYTIME.remove(handler.getPlayer().getUUID()));
        ServerLifecycleEvents.SERVER_STOPPED.register(stopped -> {
            PLAYTIME.clear();
            PLAYTIME_LOADING.clear();
        });
    }

    /**
     * Whether {@code viewer} may open {@code target}'s profile; {@code friends}: they are Minestar
     * friends. Everyone may open their own.
     */
    public static boolean mayView(MinecraftServer server, UUID viewer, UUID target, boolean friends) {
        if (viewer.equals(target)) {
            return true;
        }
        return switch (HubSettings.profileVisibleTo(server, target)) {
            case ANYONE -> true;
            case FRIENDS -> friends;
            case NOBODY -> false;
        };
    }

    /** Sends {@code target}'s profile page to {@code viewer} (a "private" page when not allowed). */
    public static void send(ServerPlayer viewer, UUID target) {
        MinecraftServer server = viewer.getServer();
        if (server == null) {
            return;
        }
        String name = nameOf(server, target);
        if (name == null) {
            ServerPlayNetworking.send(viewer, empty(target, "", HubPayloads.ProfileData.UNKNOWN));
            return;
        }
        boolean friends = MinestarHook.friendUuids(viewer.getUUID()).contains(target);
        if (!mayView(server, viewer.getUUID(), target, friends)) {
            ServerPlayNetworking.send(viewer, empty(target, name, HubPayloads.ProfileData.PRIVATE));
            return;
        }
        MinestarHook.fetchProfile(server, target, profile -> serverPlaytimeTicks(server, target, ticks -> {
            if (!viewer.hasDisconnected()) {
                ServerPlayNetworking.send(viewer, build(server, viewer.getUUID(), target, name, profile, ticks));
            }
        }));
    }

    private static HubPayloads.ProfileData build(MinecraftServer server, UUID viewer, UUID target, String name,
                                                 @Nullable ProfileSnapshot profile, long playtimeTicks) {
        boolean self = viewer.equals(target);
        Guild guild = GuildManager.get(server).guildOf(target);
        List<HubPayloads.ProfileReward> rewards = profile == null ? List.of() : profile.rewards().stream()
                .map(reward -> new HubPayloads.ProfileReward(reward.id(), reward.title(), reward.description(),
                        reward.imageUrl(), reward.rarity()))
                .toList();
        List<HubPayloads.PerkRule> perks = !self ? List.of() : LevelPerks.rules().stream()
                .map(rule -> new HubPayloads.PerkRule(rule.perk(), rule.from(), rule.every(), rule.amount(), rule.until()))
                .toList();
        return new HubPayloads.ProfileData(target, name, HubPayloads.ProfileData.SHOWN, self, profile != null,
                profile == null ? "" : profile.group(), profile == null ? -1 : profile.groupColor(),
                profile != null && profile.prime(),
                profile == null ? 0 : profile.level(), profile == null ? 0 : profile.totalXp(),
                profile == null ? 0 : profile.levelXp(), profile == null ? 0 : profile.levelXpNeeded(),
                profile == null ? 0 : profile.xpToNextLevel(), profile == null ? 0 : profile.playtimeSeconds(),
                playtimeTicks, profile == null ? -1 : profile.registeredAtMillis(), rewards,
                HubSettings.background(server, target),
                self ? HubSettings.availableBackgrounds(server, target) : List.of(),
                self ? LookRules.get(server, LookRules.Kind.BACKGROUND).offersFor(HubSettings.availableBackgrounds(server, target)) : List.of(),
                guild == null ? "" : guild.name, guild == null ? "" : guild.tag, guild == null ? 0 : guild.color,
                perks);
    }

    private static HubPayloads.ProfileData empty(UUID target, String name, int access) {
        return new HubPayloads.ProfileData(target, name, access, false, false, "", -1, false, 0, 0, 0, 0, 0, 0, -1,
                -1, List.of(), HubSettings.DEFAULT_BACKGROUND, List.of(), List.of(), "", "", 0, List.of());
    }

    @Nullable
    private static String nameOf(MinecraftServer server, UUID uuid) {
        ServerPlayer online = server.getPlayerList().getPlayer(uuid);
        if (online != null) {
            return online.getGameProfile().getName();
        }
        var cache = server.getProfileCache();
        return cache == null ? null : cache.get(uuid).map(GameProfile::getName).orElse(null);
    }

    /**
     * Playtime on this server (ticks) from the player's statistics, -1 when unknown, handed to {@code onDone} on the
     * server thread: at once for online players and cached values, else after a read off the server thread
     * (review finding 36).
     */
    private static void serverPlaytimeTicks(MinecraftServer server, UUID uuid, LongConsumer onDone) {
        ServerPlayer online = server.getPlayerList().getPlayer(uuid);
        if (online != null) {
            onDone.accept(online.getStats().getValue(Stats.CUSTOM.get(Stats.PLAY_TIME)));
            return;
        }
        Playtime cached = PLAYTIME.get(uuid);
        if (cached != null && System.currentTimeMillis() - cached.at() < PLAYTIME_TTL_MS) {
            onDone.accept(cached.ticks());
            return;
        }
        Path file = server.getWorldPath(LevelResource.PLAYER_STATS_DIR).resolve(uuid + ".json");
        CompletableFuture<Long> loading = PLAYTIME_LOADING.get(uuid);
        if (loading == null) {
            CompletableFuture<Long> fresh = new CompletableFuture<>();
            loading = PLAYTIME_LOADING.putIfAbsent(uuid, fresh);
            if (loading == null) {
                loading = fresh;
                Util.ioPool().execute(() -> {
                    long ticks = readPlaytime(server, file, uuid);
                    remember(uuid, ticks);
                    PLAYTIME_LOADING.remove(uuid, fresh);
                    fresh.complete(ticks);
                });
            }
        }
        loading.thenAccept(ticks -> {
            if (!server.isStopped()) {
                server.execute(() -> onDone.accept(ticks));
            }
        });
    }

    private static void remember(UUID uuid, long ticks) {
        long now = System.currentTimeMillis();
        if (PLAYTIME.size() >= PLAYTIME_CACHE_MAX) {
            PLAYTIME.values().removeIf(entry -> now - entry.at() >= PLAYTIME_TTL_MS);
            if (PLAYTIME.size() >= PLAYTIME_CACHE_MAX) {
                PLAYTIME.clear();
            }
        }
        PLAYTIME.put(uuid, new Playtime(ticks, now));
    }

    /**
     * Off the server thread. Only {@code stats → minecraft:custom → minecraft:play_time} is read; a file without it
     * (an old format) goes through vanilla's full reader with its data fixing.
     */
    private static long readPlaytime(MinecraftServer server, Path file, UUID uuid) {
        if (!Files.isRegularFile(file)) {
            return -1;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonElement root = JsonParser.parseReader(reader);
            if (root.isJsonObject() && root.getAsJsonObject().get("stats") instanceof JsonObject stats
                    && stats.get("minecraft:custom") instanceof JsonObject custom
                    && custom.get("minecraft:play_time") instanceof com.google.gson.JsonPrimitive value
                    && value.isNumber()) {
                return value.getAsLong();
            }
            return new ServerStatsCounter(server, file.toFile()).getValue(Stats.CUSTOM.get(Stats.PLAY_TIME));
        } catch (Exception e) {
            EdenLD.LOGGER.debug("[Profile] Can't read the statistics of {}: {}", uuid, e.toString());
            return -1;
        }
    }
}
