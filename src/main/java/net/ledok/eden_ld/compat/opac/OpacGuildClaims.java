package net.ledok.eden_ld.compat.opac;

import net.ledok.eden_ld.EdenLD;
import net.ledok.eden_ld.hub.guild.Guild;
import net.ledok.eden_ld.hub.guild.GuildManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import org.jetbrains.annotations.Nullable;
import xaero.pac.common.claims.api.SpecialClaimOwners;
import xaero.pac.common.server.ServerData;
import xaero.pac.common.server.api.OpenPACServerAPI;
import xaero.pac.common.server.claims.api.IServerClaimsManagerAPI;
import xaero.pac.common.server.player.config.api.v2.IPlayerConfigAPI;
import xaero.pac.common.server.player.config.api.v2.PlayerConfigOptions;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Guild claims in OPAC. With party-owned claims the guild's claims are its leader's, so:
 * the bank's claim upgrades are OPAC "bonus chunk claims" on the leader's player config, added
 * and removed as deltas (an admin's own bonus for that player stays untouched); a leadership
 * change moves the bonus and the old leader's claims to the new leader. {@link Guild#opacOwner}
 * / {@link Guild#opacBonus} remember what was applied where.
 * <p>
 * Guild land must never turn into personal land beyond a player's own limit — otherwise
 * "claim with the guild's limit, disband, keep everything, repeat in a new guild" grows without
 * bound. Hence claims always follow the leader, and when a guild ends (like FTB Chunks when a
 * team's last member leaves) the leader keeps its land only up to their own limit: the rest is
 * released — newest claims first by default ({@link ClaimTimes}) — as OPAC releases inactive
 * players' land (expired claims or wilderness, per its {@code playerClaimsConvertExpiredClaims}).
 */
final class OpacGuildClaims {

    private record Chunk(ResourceLocation dimension, int x, int z, boolean forceload, long time) {
    }

    private static boolean lookupFailed;

    private OpacGuildClaims() {
    }

    /**
     * Guild claims work when Eden's guilds are OPAC's primary party system and party-owned claims
     * are on; otherwise claims stay personal and Eden leaves OPAC alone.
     */
    static boolean active(MinecraftServer server) {
        var data = ServerData.from(server);
        return data != null
                && data.getPlayerPartySystemManager().getPrimarySystem() instanceof EdenGuildPartySystem
                && partyOwnedClaims();
    }

    private static boolean partyOwnedClaims() {
        return Boolean.TRUE.equals(serverOption("partyOwnedClaims"));
    }

    /**
     * An OPAC server config value (its config class isn't API, and its value type comes from a
     * config library Eden doesn't compile against: read it reflectively). Null when unreadable,
     * which turns guild claims off.
     */
    @Nullable
    private static Object serverOption(String name) {
        if (lookupFailed) {
            return null;
        }
        try {
            Class<?> config = Class.forName("xaero.pac.common.server.config.ServerConfig");
            Object value = config.getField(name).get(config.getField("CONFIG").get(null));
            return value.getClass().getMethod("get").invoke(value);
        } catch (ReflectiveOperationException | RuntimeException e) {
            lookupFailed = true;
            EdenLD.LOGGER.warn("Couldn't read OPAC's {} option; guild claims are off", name, e);
            return null;
        }
    }

    private static int intOption(String name) {
        return serverOption(name) instanceof Integer value ? value : 0;
    }

    static void reconcile(MinecraftServer server) {
        if (!active(server)) {
            return;
        }
        OpenPACServerAPI api = OpenPACServerAPI.get(server);
        GuildManager manager = GuildManager.get(server);
        int step = Math.max(0, EdenLD.CONFIG.hub.guild_claim_upgrade_step);
        boolean changed = false;
        for (Guild guild : manager.all()) {
            UUID leader = guild.leader();
            if (leader == null) {
                continue;
            }
            if (guild.opacOwner != null && !guild.opacOwner.equals(leader)) {
                // Leadership changed: the old leader gives back the bonus (and the claims).
                addBonus(api, guild.opacOwner, -guild.opacBonus);
                int moved = moveClaims(server, api.getServerClaimsManager(), guild.opacOwner, leader);
                EdenLD.LOGGER.info("Guild {}: moved {} claimed chunks to the new leader {}", guild.name, moved, leader);
                guild.opacOwner = null;
                guild.opacBonus = 0;
            }
            int wanted = guild.bank.claimLevel * step;
            if (guild.opacOwner == null || guild.opacBonus != wanted) {
                addBonus(api, leader, wanted - guild.opacBonus);
                guild.opacOwner = leader;
                guild.opacBonus = wanted;
                changed = true;
            }
        }
        if (changed) {
            manager.markChanged();
        }
    }

    /**
     * The guild is going away (members still in it): the bought bonus goes, and the land the
     * leader can't keep on their own is released. Returns what was released (null: nothing).
     */
    @Nullable
    static OpacCompat.Released end(MinecraftServer server, Guild guild) {
        // Measured before the bonus is taken back: afterGuild already leaves it out.
        OpacCompat.ClaimsInfo land = afterGuild(server, guild);
        OpenPACServerAPI api = OpenPACServerAPI.get(server);
        if (guild.opacOwner != null && guild.opacBonus != 0) {
            addBonus(api, guild.opacOwner, -guild.opacBonus);
        }
        guild.opacOwner = null;
        guild.opacBonus = 0;
        if (land == null || land.used() <= land.limit()) {
            return null;
        }
        boolean expire = Boolean.TRUE.equals(serverOption("playerClaimsConvertExpiredClaims"));
        int released = releaseExcess(server, api.getServerClaimsManager(), guild.leader(),
                land.used() - land.limit(), expire);
        EdenLD.LOGGER.info("Guild {} ended: released {} of the leader's {} chunks over their own limit of {} ({})",
                guild.name, released, land.used(), land.limit(), expire ? "expired" : "unclaimed");
        return new OpacCompat.Released(released, expire);
    }

    /** Releases {@code count} of {@code owner}'s chunks, newest first (config: oldest first). */
    private static int releaseExcess(MinecraftServer server, IServerClaimsManagerAPI claims, UUID owner,
                                     int count, boolean expire) {
        List<Chunk> chunks = chunksOf(server, claims, owner);
        Comparator<Chunk> byTime = Comparator.comparingLong(Chunk::time);
        chunks.sort(EdenLD.CONFIG.hub.guild_release_newest_first ? byTime.reversed() : byTime);
        int released = 0;
        for (Chunk chunk : chunks.subList(0, Math.min(count, chunks.size()))) {
            if (expire) {
                claims.claim(chunk.dimension(), SpecialClaimOwners.EXPIRED, -1, chunk.x(), chunk.z(), false);
            } else {
                claims.unclaim(chunk.dimension(), chunk.x(), chunk.z());
            }
            released++;
        }
        return released;
    }

    /** Every chunk {@code owner} holds, with claim times (0 when unknown); collected before any change. */
    private static List<Chunk> chunksOf(MinecraftServer server, IServerClaimsManagerAPI claims, UUID owner) {
        List<Chunk> chunks = new ArrayList<>();
        if (!claims.hasPlayerInfo(owner)) {
            return chunks;
        }
        ClaimTimes times = ClaimTimes.get(server);
        claims.getPlayerInfo(owner).getStream().forEach(dimension -> dimension.getValue().getStream()
                .forEach(posList -> {
                    boolean forceload = posList.getClaimState().isForceloadable();
                    posList.getStream().forEach(pos -> chunks.add(new Chunk(dimension.getKey(), pos.x, pos.z,
                            forceload, times == null ? 0 : times.time(dimension.getKey(), pos.x, pos.z, owner))));
                }));
        return chunks;
    }

    @Nullable
    static OpacCompat.ClaimsInfo claims(MinecraftServer server, Guild guild) {
        UUID leader = guild.leader();
        if (leader == null || !active(server)) {
            return null;
        }
        OpenPACServerAPI api = OpenPACServerAPI.get(server);
        IServerClaimsManagerAPI claims = api.getServerClaimsManager();
        int used = claims.hasPlayerInfo(leader) ? claims.getPlayerInfo(leader).getClaimCount() : 0;
        Integer bonus = api.getPlayerConfigManager().getLoadedConfig(leader)
                .getEffective(PlayerConfigOptions.BONUS_CHUNK_CLAIMS);
        int limit = claims.getPlayerBaseClaimLimit(leader) + (bonus == null ? 0 : bonus);
        return new OpacCompat.ClaimsInfo(used, limit);
    }

    /**
     * The leader's claims now, and how many would still be within their limit once the guild is
     * gone (no guild-member bonus, no bought bonus). Null when guild claims aren't active.
     */
    @Nullable
    static OpacCompat.ClaimsInfo afterGuild(MinecraftServer server, Guild guild) {
        UUID leader = guild.leader();
        if (leader == null || !active(server)) {
            return null;
        }
        OpenPACServerAPI api = OpenPACServerAPI.get(server);
        IServerClaimsManagerAPI claims = api.getServerClaimsManager();
        int used = claims.hasPlayerInfo(leader) ? claims.getPlayerInfo(leader).getClaimCount() : 0;
        // OPAC's own party-owner bonus (ServerPlayerClaimInfoManager#getPartyOwnershipBonus).
        int others = Math.max(0, guild.members.size() - 1);
        int partyBonus = others * intOption("claimBonusPerPartyMember")
                + (others > 0 ? intOption("claimBonusForPartyOwner") : 0);
        Integer bonus = api.getPlayerConfigManager().getLoadedConfig(leader)
                .getEffective(PlayerConfigOptions.BONUS_CHUNK_CLAIMS);
        int edenBonus = leader.equals(guild.opacOwner) ? guild.opacBonus : 0;
        int own = claims.getPlayerBaseClaimLimit(leader) - partyBonus + (bonus == null ? 0 : bonus) - edenBonus;
        return new OpacCompat.ClaimsInfo(used, Math.max(0, own));
    }

    /** Adds {@code delta} to a player's OPAC bonus chunk claims (never below 0); false when OPAC refused. */
    private static boolean addBonus(OpenPACServerAPI api, UUID player, int delta) {
        if (delta == 0) {
            return true;
        }
        IPlayerConfigAPI config = api.getPlayerConfigManager().getLoadedConfig(player);
        Integer raw = config.getRaw(PlayerConfigOptions.BONUS_CHUNK_CLAIMS);
        int next = Math.max(0, (raw == null ? 0 : raw) + delta);
        IPlayerConfigAPI.SetResult result = config.tryToSet(PlayerConfigOptions.BONUS_CHUNK_CLAIMS, next);
        if (result != IPlayerConfigAPI.SetResult.SUCCESS) {
            EdenLD.LOGGER.warn("Couldn't set OPAC bonus chunk claims of {} to {}: {}", player, next, result);
            return false;
        }
        return true;
    }

    /** A player's own bonus claims (level perks), independent of guilds. */
    static boolean addPlayerBonus(MinecraftServer server, UUID player, int delta) {
        return addBonus(OpenPACServerAPI.get(server), player, delta);
    }

    /** Re-owns every chunk claimed by {@code from} to {@code to} (keeping claim times); returns how many moved. */
    private static int moveClaims(MinecraftServer server, IServerClaimsManagerAPI claims, UUID from, UUID to) {
        List<Chunk> chunks = chunksOf(server, claims, from);
        // Sub-configs are per player, so moved chunks use the new leader's main claim config (-1).
        ClaimTimes.keepTimes = true;
        try {
            for (Chunk chunk : chunks) {
                claims.claim(chunk.dimension(), to, -1, chunk.x(), chunk.z(), chunk.forceload());
            }
        } finally {
            ClaimTimes.keepTimes = false;
        }
        return chunks.size();
    }
}
