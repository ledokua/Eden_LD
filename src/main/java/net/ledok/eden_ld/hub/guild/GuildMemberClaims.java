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

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Guild claims each member brings: the LuckPerms meta {@link #META_KEY} (the prime group sets 15), summed over the
 * guild's members — leader included — and added to the guild's claim limit with the bank's claim upgrades
 * ({@code OpacGuildClaims}). Each player's value is remembered here (world data), so offline members still count:
 * read on join and whenever LuckPerms recalculates an online player (Prime bought or gone), and every few minutes for
 * offline guild members who still bring claims (LuckPerms loads them, one at a time), so a Prime that runs out while
 * they're away is noticed too. A Prime bought while away counts from the member's next join.
 */
public final class GuildMemberClaims extends SavedData {

    /** The LuckPerms meta key: claims a member adds to their guild's limit (whole number; unset = 0). */
    public static final String META_KEY = "eden_ld.guild_claim_bonus";
    private static final int REFRESH_TICKS = 5 * 60 * 20;
    /** Ticks between two offline loads, so a check never loads the whole guild list at once (review finding 42). */
    private static final int LOAD_SPACING_TICKS = 20;

    private static final Factory<GuildMemberClaims> FACTORY = new Factory<>(GuildMemberClaims::new, GuildMemberClaims::load, null);

    private final Map<UUID, Integer> bonus = new HashMap<>();
    private static int ticks;
    /** Offline members still to check this round (server thread only). */
    private static final Deque<UUID> OFFLINE_QUEUE = new ArrayDeque<>();

    public static GuildMemberClaims get(MinecraftServer server) {
        return EdenData.get(server, FACTORY, "guild_member_claims");
    }

    static void initialize() {
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> server.execute(() ->
                refresh(server, handler.getPlayer())));
        // Prime bought, expired or taken away: LuckPerms recalculates the user (off-thread). Its API is up only once
        // the server has started.
        // Only the player whose data changed, on the server thread (review finding 42).
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STARTED.register(started ->
                LuckPermsCompat.onPlayerDataChange(player -> refresh(started, player)));
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPED.register(stopped -> {
            OFFLINE_QUEUE.clear();
            ticks = 0;
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (++ticks >= REFRESH_TICKS) {
                ticks = 0;
                if (OFFLINE_QUEUE.isEmpty()) {
                    queueOffline(server);
                }
            }
            if (ticks % LOAD_SPACING_TICKS == 0 && !OFFLINE_QUEUE.isEmpty()) {
                loadOffline(server, OFFLINE_QUEUE.poll());
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

    /**
     * Offline guild members who still bring claims, to recheck one by one (review finding 42): loading every offline
     * member each round made LuckPerms recalculate each, and every recalculation refreshed all online players.
     * Members with no stored bonus aren't loaded: a Prime they buy while away counts from their next join.
     */
    private static void queueOffline(MinecraftServer server) {
        GuildMemberClaims data = get(server);
        for (Guild guild : GuildManager.get(server).all()) {
            for (UUID member : guild.members.keySet()) {
                if (data.of(member) > 0 && server.getPlayerList().getPlayer(member) == null) {
                    OFFLINE_QUEUE.add(member);
                }
            }
        }
    }

    /** One offline member: LuckPerms loads them, the answer comes back to the server thread. */
    private static void loadOffline(MinecraftServer server, UUID member) {
        if (server.getPlayerList().getPlayer(member) != null) {
            return;
        }
        LuckPermsCompat.loadMetaInt(member, META_KEY).thenAccept(value ->
                net.ledok.eden_ld.util.OnServerThread.run(server, () -> {
                    if (!server.isStopped()) {
                        update(server, member, value.orElse(0));
                    }
                }));
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
        Guild guild = GuildManager.get(server).guildOf(player);
        if (guild != null) {
            // Coalesced: several members changing in one tick cost one sync (review finding 42). Open banks of the
            // guild follow after it, since they show the claim limit (review finding 97).
            GuildServer.scheduleSyncAndBank(guild.id);
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
