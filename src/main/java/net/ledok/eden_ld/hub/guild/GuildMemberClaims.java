package net.ledok.eden_ld.hub.guild;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.ledok.eden_ld.EdenData;
import net.ledok.eden_ld.compat.LuckPermsCompat;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Guild claims each member brings: the LuckPerms meta {@link #META_KEY} (the prime group sets 15), summed over the
 * guild's members — leader included — and added to the guild's claim limit with the bank's claim upgrades
 * ({@code OpacGuildClaims}). Each player's value is remembered here (world data), so offline members still count:
 * read on join and whenever LuckPerms recalculates someone (Prime bought or gone), and every few minutes for offline
 * guild members (LuckPerms loads them), so a Prime that runs out while they're away is noticed too.
 */
public final class GuildMemberClaims extends SavedData {

    /** The LuckPerms meta key: claims a member adds to their guild's limit (whole number; unset = 0). */
    public static final String META_KEY = "eden_ld.guild_claim_bonus";
    private static final int REFRESH_TICKS = 5 * 60 * 20;

    private static final Factory<GuildMemberClaims> FACTORY = new Factory<>(GuildMemberClaims::new, GuildMemberClaims::load, null);

    private final Map<UUID, Integer> bonus = new HashMap<>();
    private static int ticks;

    public static GuildMemberClaims get(MinecraftServer server) {
        return EdenData.get(server, FACTORY, "guild_member_claims");
    }

    static void initialize() {
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> server.execute(() ->
                refresh(server, handler.getPlayer())));
        // Prime bought, expired or taken away: LuckPerms recalculates the user (off-thread). Its API is up only once
        // the server has started.
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STARTED.register(started ->
                LuckPermsCompat.onUserDataChange(() -> started.execute(() ->
                        started.getPlayerList().getPlayers().forEach(player -> refresh(started, player)))));
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (++ticks >= REFRESH_TICKS) {
                ticks = 0;
                refreshOffline(server);
            }
        });
    }

    /** The claims {@code player} brings to their guild (their last known meta value). */
    public int of(UUID player) {
        return bonus.getOrDefault(player, 0);
    }

    /** What all of a guild's members bring together. */
    public int total(Guild guild) {
        int sum = 0;
        for (UUID member : guild.members.keySet()) {
            sum += of(member);
        }
        return sum;
    }

    private static void refresh(MinecraftServer server, ServerPlayer player) {
        update(server, player.getUUID(), LuckPermsCompat.metaInt(player, META_KEY).orElse(0));
    }

    /** Guild members not online: LuckPerms loads them, the answer comes back to the server thread. */
    private static void refreshOffline(MinecraftServer server) {
        for (Guild guild : GuildManager.get(server).all()) {
            for (UUID member : guild.members.keySet()) {
                if (server.getPlayerList().getPlayer(member) == null) {
                    LuckPermsCompat.loadMetaInt(member, META_KEY).thenAccept(value ->
                            server.execute(() -> update(server, member, value.orElse(0))));
                }
            }
        }
    }

    /** Stores a player's value; when it changed and they're in a guild, the guild's claims follow. */
    private static void update(MinecraftServer server, UUID player, int value) {
        GuildMemberClaims data = get(server);
        int clamped = Math.max(0, value);
        if (data.of(player) == clamped) {
            return;
        }
        if (clamped == 0) {
            data.bonus.remove(player);
        } else {
            data.bonus.put(player, clamped);
        }
        data.setDirty();
        if (GuildManager.get(server).guildOf(player) != null) {
            GuildServer.syncAll(server);
        }
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        CompoundTag all = new CompoundTag();
        bonus.forEach((uuid, value) -> all.putInt(uuid.toString(), value));
        tag.put("bonus", all);
        return tag;
    }

    private static GuildMemberClaims load(CompoundTag tag, HolderLookup.Provider registries) {
        GuildMemberClaims data = new GuildMemberClaims();
        CompoundTag all = tag.getCompound("bonus");
        for (String key : all.getAllKeys()) {
            try {
                data.bonus.put(UUID.fromString(key), all.getInt(key));
            } catch (IllegalArgumentException ignored) {
            }
        }
        return data;
    }
}
