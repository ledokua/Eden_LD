package net.ledok.eden_ld.hub.profile;

import com.mojang.authlib.GameProfile;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.ledok.eden_ld.EdenLD;
import net.ledok.eden_ld.hub.HubPayloads;
import net.ledok.eden_ld.hub.HubSettings;
import net.ledok.eden_ld.hub.guild.Guild;
import net.ledok.eden_ld.hub.guild.GuildManager;
import net.ledok.eden_ld.minestar.MinestarHook;
import net.ledok.eden_ld.minestar.ProfileSnapshot;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.ServerStatsCounter;
import net.minecraft.stats.Stats;
import net.minecraft.world.level.storage.LevelResource;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

/**
 * Profile pages: a player's own on the Profile tab, anyone else's from a Profile button (Friends,
 * party and guild members) — if their Settings → Privacy allows it. Minestar data (level, rewards,
 * all-servers playtime, registration) comes from the cached profile for online players and a
 * short-lived fetch for anyone else; Eden adds this server's playtime, the guild and the chosen
 * background.
 */
public final class ProfileServer {

    private ProfileServer() {
    }

    public static void initialize() {
        ProfileLevels.initialize();
        ServerPlayNetworking.registerGlobalReceiver(HubPayloads.ProfileRequest.TYPE,
                (payload, context) -> send(context.player(), payload.target()));
        ServerPlayNetworking.registerGlobalReceiver(HubPayloads.BackgroundSelect.TYPE, (payload, context) -> {
            ServerPlayer player = context.player();
            MinecraftServer server = player.getServer();
            if (server != null && HubSettings.selectBackground(server, player.getUUID(), payload.background())) {
                send(player, player.getUUID());
                Wardrobe.send(player);
            }
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
        MinestarHook.fetchProfile(server, target, profile -> {
            if (!viewer.hasDisconnected()) {
                ServerPlayNetworking.send(viewer, build(server, viewer.getUUID(), target, name, profile));
            }
        });
    }

    private static HubPayloads.ProfileData build(MinecraftServer server, UUID viewer, UUID target, String name,
                                                 @Nullable ProfileSnapshot profile) {
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
                serverPlaytimeTicks(server, target), profile == null ? -1 : profile.registeredAtMillis(), rewards,
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

    /** Playtime on this server (ticks) from the player's statistics; -1 when unknown. */
    private static long serverPlaytimeTicks(MinecraftServer server, UUID uuid) {
        ServerPlayer online = server.getPlayerList().getPlayer(uuid);
        if (online != null) {
            return online.getStats().getValue(Stats.CUSTOM.get(Stats.PLAY_TIME));
        }
        Path file = server.getWorldPath(LevelResource.PLAYER_STATS_DIR).resolve(uuid + ".json");
        if (!Files.isRegularFile(file)) {
            return -1;
        }
        try {
            return new ServerStatsCounter(server, file.toFile()).getValue(Stats.CUSTOM.get(Stats.PLAY_TIME));
        } catch (RuntimeException e) {
            EdenLD.LOGGER.debug("[Profile] Can't read the statistics of {}: {}", uuid, e.toString());
            return -1;
        }
    }
}
