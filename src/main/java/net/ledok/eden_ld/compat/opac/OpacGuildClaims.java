package net.ledok.eden_ld.compat.opac;

import net.ledok.eden_ld.EdenLD;
import net.ledok.eden_ld.hub.guild.Guild;
import net.ledok.eden_ld.hub.guild.GuildMemberClaims;
import net.ledok.eden_ld.hub.guild.GuildManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import org.jetbrains.annotations.Nullable;
import xaero.pac.common.claims.player.api.IPlayerChunkClaimAPI;
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
 * the bank's claim upgrades and the members' own claims ({@link GuildMemberClaims}: Prime) are OPAC "bonus chunk
 * claims" on the leader's player config, added
 * and removed as deltas (an admin's own bonus for that player stays untouched); a leadership
 * change moves the bonus and the old leader's claims to the new leader. {@link Guild#opacOwner}
 * / {@link Guild#opacBonus} remember what was applied where, and {@link OpacBonusLedger} the raw
 * value each change left, so a hard stop that saved only one side is put right at the next start.
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

    /** How often outside bonus edits are adopted, owed bonus retried and old move records dropped (ticks). */
    static final int PERIODIC_TICKS = 20 * 60;

    /**
     * Once at server start, before the first {@link #reconcile}: chunks of a recent leadership move that OPAC still has
     * under the former owner move again, claim times for chunks OPAC loaded from disk (review finding 13), and OPAC bonus
     * values are put back to what Eden's saved records expect (review finding 12).
     */
    static void startup(MinecraftServer server) {
        OpenPACServerAPI api = OpenPACServerAPI.get(server);
        OpacBonusLedger ledger = OpacBonusLedger.get(server);
        if (ledger != null) {
            redoMoves(server, api.getServerClaimsManager(), ledger);
        }
        ClaimTimes times = ClaimTimes.get(server);
        if (times != null) {
            times.seed(api.getServerClaimsManager().getPlayerInfoStream());
        }
        if (ledger == null) {
            return;
        }
        ledger.all().forEach((player, expected) -> {
            IPlayerConfigAPI config = api.getPlayerConfigManager().getLoadedConfig(player);
            int raw = raw(config);
            if (raw == expected) {
                return;
            }
            // OPAC saves its player configs on its own timer, Eden's records at the world save: after a hard stop one
            // of them is older. The ledger was saved with guilds.dat and hub.dat, so it matches Eden's claim levels,
            // banks and level perks (and outside edits adopted up to a minute before the stop); the raw value follows it.
            IPlayerConfigAPI.SetResult result = config.tryToSet(PlayerConfigOptions.BONUS_CHUNK_CLAIMS, expected);
            if (result == IPlayerConfigAPI.SetResult.SUCCESS) {
                EdenLD.LOGGER.warn("OPAC bonus chunk claims of {} were {}, but Eden's saved records expect {} (the last "
                        + "stop didn't save both): restored", player, raw, expected);
            } else {
                EdenLD.LOGGER.warn("OPAC bonus chunk claims of {} are {}, Eden's saved records expect {}; couldn't "
                        + "restore: {}", player, raw, expected, result);
            }
        });
    }

    /**
     * After a hard stop that saved guilds.dat (and this ledger) but not OPAC's claims, OPAC still has a moved guild's
     * land under the former leader while the bonus restore gives the guild bonus to the new one: the former leader would
     * sit over their limit holding guild land. Every recorded chunk still owned by the move's former owner goes to the
     * guild's current claim owner again (review finding 12 follow-up). The records stay, re-stamped, until OPAC has saved.
     */
    private static void redoMoves(MinecraftServer server, IServerClaimsManagerAPI claims, OpacBonusLedger ledger) {
        GuildManager manager = GuildManager.get(server);
        for (OpacBonusLedger.Move move : ledger.moves()) {
            Guild guild = manager.byId(move.guild());
            UUID to = guild == null ? null : guild.opacOwner;
            if (to == null || to.equals(move.from())) {
                continue;
            }
            List<Chunk> back = new ArrayList<>();
            for (OpacBonusLedger.MovedChunk chunk : move.chunks()) {
                IPlayerChunkClaimAPI claim = claims.get(chunk.dimension(), chunk.x(), chunk.z());
                if (claim != null && move.from().equals(claim.getPlayerId())) {
                    back.add(new Chunk(chunk.dimension(), chunk.x(), chunk.z(), claim.isForceloadable(), 0));
                }
            }
            if (!back.isEmpty()) {
                reclaim(claims, back, to);
                EdenLD.LOGGER.warn("Guild {}: OPAC still had {} chunks of the last leadership move under the former "
                        + "leader {} (the stop didn't save both); moved them to {} again", guild.name, back.size(),
                        move.from(), to);
            }
        }
        ledger.restampMoves(System.currentTimeMillis());
    }

    /**
     * Once a minute (review finding 12 follow-up): outside bonus edits are adopted, so a hard stop reverts at most about
     * a minute of them; bonus OPAC refused to take back is retried; move records OPAC has surely saved are dropped.
     */
    static void periodic(MinecraftServer server) {
        OpacBonusLedger ledger = OpacBonusLedger.get(server);
        if (ledger == null) {
            return;
        }
        OpenPACServerAPI api = OpenPACServerAPI.get(server);
        adoptOutsideEdits(api, ledger);
        ledger.owed().forEach((player, amount) -> {
            if (addBonus(server, api, player, -amount)) {
                ledger.paid(player);
                EdenLD.LOGGER.info("Took back {} guild bonus chunk claims from {} (left over from an ended guild)",
                        amount, player);
            }
        });
        // OPAC saves claims every autosaveInterval minutes (its config); twice that plus a minute is surely past a save.
        long keep = (Math.max(1, intOption("autosaveInterval")) * 2L + 1) * 60_000L;
        ledger.dropMoves(System.currentTimeMillis() - keep);
    }

    /**
     * At a clean stop (both sides save): raw values that differ from Eden's last write are outside changes made while
     * running and are adopted; move records are no longer needed (review finding 12).
     */
    static void stopping(MinecraftServer server) {
        OpacBonusLedger ledger = OpacBonusLedger.get(server);
        if (ledger == null) {
            return;
        }
        adoptOutsideEdits(OpenPACServerAPI.get(server), ledger);
        ledger.dropMoves(null);
    }

    /**
     * For every player Eden has changed: a raw value that differs from the ledger while Eden made no change since is an
     * outside edit (an admin's /openpac-player-config, the NPCs_LD bonus_claims dialog action), so the ledger takes it
     * over and a later start keeps it.
     */
    private static void adoptOutsideEdits(OpenPACServerAPI api, OpacBonusLedger ledger) {
        ledger.all().forEach((player, expected) -> {
            int raw = raw(api.getPlayerConfigManager().getLoadedConfig(player));
            if (raw != expected) {
                EdenLD.LOGGER.info("OPAC bonus chunk claims of {} were changed outside Eden ({} -> {}); keeping it",
                        player, expected, raw);
                ledger.set(player, raw);
            }
        });
    }

    /** The guild's bonus as it should be: bought claim upgrades, plus what the members bring (Prime: LuckPerms meta). */
    private static int wanted(MinecraftServer server, Guild guild) {
        int step = Math.max(0, EdenLD.CONFIG.hub.guild_claim_upgrade_step);
        return guild.bank.claimLevel * step + GuildMemberClaims.get(server).total(guild);
    }

    static void reconcile(MinecraftServer server) {
        if (!active(server)) {
            return;
        }
        OpenPACServerAPI api = OpenPACServerAPI.get(server);
        GuildManager manager = GuildManager.get(server);
        boolean changed = false;
        for (Guild guild : manager.all()) {
            UUID leader = guild.leader();
            if (leader == null) {
                continue;
            }
            if (guild.opacOwner != null && !guild.opacOwner.equals(leader)) {
                // Leadership changed: the old leader gives back the bonus (and the claims). Eden's record changes only
                // once OPAC took the bonus back (review finding 12); otherwise the next reconcile tries again.
                if (!addBonus(server, api, guild.opacOwner, -guild.opacBonus)) {
                    continue;
                }
                List<Chunk> moved = moveClaims(server, api.getServerClaimsManager(), guild.opacOwner, leader);
                EdenLD.LOGGER.info("Guild {}: moved {} claimed chunks to the new leader {}", guild.name, moved.size(),
                        leader);
                // Until OPAC has saved the move, a hard stop could leave the land under the old leader: remembered so the
                // next start can move it again (see redoMoves).
                OpacBonusLedger ledger = OpacBonusLedger.get(server);
                if (ledger != null && !moved.isEmpty()) {
                    ledger.addMove(new OpacBonusLedger.Move(guild.id, guild.opacOwner, System.currentTimeMillis(),
                            moved.stream().map(chunk -> new OpacBonusLedger.MovedChunk(chunk.dimension(), chunk.x(),
                                    chunk.z())).toList()));
                }
                guild.opacOwner = null;
                guild.opacBonus = 0;
                changed = true;
            }
            int wanted = wanted(server, guild);
            if (guild.opacOwner == null || guild.opacBonus != wanted) {
                // Recorded only when OPAC took it (review finding 12): a refused change is retried, never assumed.
                if (addBonus(server, api, leader, wanted - guild.opacBonus)) {
                    guild.opacOwner = leader;
                    guild.opacBonus = wanted;
                    changed = true;
                }
            }
        }
        if (changed) {
            manager.markChanged();
        }
    }

    /**
     * What {@code target} would hold and may hold if they led the guild now (review finding 11): their own chunks plus
     * every chunk the current claim owner holds (reconcile moves them all), against their base limit as party owner +
     * OPAC's party bonus + their own bonus + the guild's bonus. Null when guild claims aren't active.
     */
    @Nullable
    static OpacCompat.ClaimsInfo afterTransfer(MinecraftServer server, Guild guild, UUID target) {
        if (!active(server)) {
            return null;
        }
        OpenPACServerAPI api = OpenPACServerAPI.get(server);
        IServerClaimsManagerAPI claims = api.getServerClaimsManager();
        int held = claims.hasPlayerInfo(target) ? claims.getPlayerInfo(target).getClaimCount() : 0;
        UUID from = guild.opacOwner;
        if (from != null && !from.equals(target) && claims.hasPlayerInfo(from)) {
            held += claims.getPlayerInfo(from).getClaimCount();
        }
        // OPAC's party-owner bonus the target gets once they own the guild's party (they don't yet, so their base
        // limit doesn't include it); the same formula as ServerPlayerClaimInfoManager#getPartyOwnershipBonus.
        int others = Math.max(0, guild.members.size() - 1);
        int partyBonus = others * intOption("claimBonusPerPartyMember")
                + (others > 0 ? intOption("claimBonusForPartyOwner") : 0);
        Integer bonus = api.getPlayerConfigManager().getLoadedConfig(target)
                .getEffective(PlayerConfigOptions.BONUS_CHUNK_CLAIMS);
        int edenBonus = target.equals(guild.opacOwner) ? guild.opacBonus : 0;
        int limit = claims.getPlayerBaseClaimLimit(target) + partyBonus + (bonus == null ? 0 : bonus) - edenBonus
                + wanted(server, guild);
        return new OpacCompat.ClaimsInfo(held, limit);
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
        if (guild.opacOwner != null && guild.opacBonus != 0
                && !addBonus(server, api, guild.opacOwner, -guild.opacBonus)) {
            // The guild goes away regardless, so its record can't wait for a retry: the ledger keeps what OPAC still
            // owes back and the periodic pass retries it (review finding 12 follow-up: never assume OPAC took it).
            OpacBonusLedger ledger = OpacBonusLedger.get(server);
            if (ledger != null) {
                ledger.owe(guild.opacOwner, guild.opacBonus);
            }
        }
        guild.opacOwner = null;
        guild.opacBonus = 0;
        if (land == null || land.used() <= land.limit()) {
            return null;
        }
        // Always free land (owner's decision, 2026-11-13): expired claims kept the land from its old leader and from
        // everyone else alike. OPAC's playerClaimsConvertExpiredClaims still governs its own inactivity expiry.
        List<OpacCompat.FreedChunk> freed = releaseExcess(server, api.getServerClaimsManager(), guild.leader(),
                land.used() - land.limit());
        StringBuilder where = new StringBuilder();
        for (OpacCompat.FreedChunk chunk : freed) {
            where.append(where.isEmpty() ? "" : "; ").append(chunk.dimension()).append(' ')
                    .append(chunk.x()).append(',').append(chunk.z());
        }
        EdenLD.LOGGER.info("Guild {} ended: released {} of the leader's {} chunks over their own limit of {} (unclaimed): {}",
                guild.name, freed.size(), land.used(), land.limit(), where);
        return new OpacCompat.Released(freed.size(), freed);
    }

    /** How many of the leader's chunks a disband would free now (over their own limit); 0 when none. */
    static int disbandExcess(MinecraftServer server, Guild guild) {
        OpacCompat.ClaimsInfo land = afterGuild(server, guild);
        return land == null ? 0 : Math.max(0, land.used() - land.limit());
    }

    /** Unclaims {@code count} of {@code owner}'s chunks, newest first (config: oldest first); returns which. */
    private static List<OpacCompat.FreedChunk> releaseExcess(MinecraftServer server, IServerClaimsManagerAPI claims,
                                                             UUID owner, int count) {
        List<Chunk> chunks = chunksOf(server, claims, owner);
        Comparator<Chunk> byTime = Comparator.comparingLong(Chunk::time);
        chunks.sort(EdenLD.CONFIG.hub.guild_release_newest_first ? byTime.reversed() : byTime);
        List<OpacCompat.FreedChunk> freed = new ArrayList<>();
        for (Chunk chunk : chunks.subList(0, Math.min(count, chunks.size()))) {
            claims.unclaim(chunk.dimension(), chunk.x(), chunk.z());
            freed.add(new OpacCompat.FreedChunk(chunk.dimension(), chunk.x(), chunk.z()));
        }
        return freed;
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
        // OPAC's own party-owner bonus as OPAC sees it right now (ServerPlayerClaimInfoManager#getPartyOwnershipBonus:
        // only while the leader owns a primary party, from that party's live member count). Estimating it from the
        // guild's member list subtracted a bonus OPAC no longer included once the guild's party view was gone, so a
        // disband computed the leader's own limit as 0 and released all their land (2026-11-13, guild gOlDi).
        int partyBonus = 0;
        var parties = ServerData.from(server).getPlayerPartySystemManager();
        if (parties.isPrimaryPartyOwner(leader)) {
            int others = Math.max(0, parties.getPrimaryMemberCount(leader) - 1);
            partyBonus = others * intOption("claimBonusPerPartyMember")
                    + (others > 0 ? intOption("claimBonusForPartyOwner") : 0);
        }
        Integer bonus = api.getPlayerConfigManager().getLoadedConfig(leader)
                .getEffective(PlayerConfigOptions.BONUS_CHUNK_CLAIMS);
        int edenBonus = leader.equals(guild.opacOwner) ? guild.opacBonus : 0;
        int own = claims.getPlayerBaseClaimLimit(leader) - partyBonus + (bonus == null ? 0 : bonus) - edenBonus;
        // Never below the server's plain per-player limit: a wrong estimate must not release a leader's own land.
        int floor = intOption("maxPlayerClaims");
        if (own < floor) {
            EdenLD.LOGGER.warn("Guild {}: the leader's own claim limit came out as {} (base {}, party bonus {}, bonus {}, "
                            + "guild bonus {}); using the server's {} instead.", guild.name, own,
                    claims.getPlayerBaseClaimLimit(leader), partyBonus, bonus, edenBonus, floor);
            own = floor;
        }
        return new OpacCompat.ClaimsInfo(used, own);
    }

    /** A player's raw OPAC bonus chunk claims (their own config value; 0 when unset). */
    private static int raw(IPlayerConfigAPI config) {
        Integer raw = config.getRaw(PlayerConfigOptions.BONUS_CHUNK_CLAIMS);
        return raw == null ? 0 : raw;
    }

    /**
     * Adds {@code delta} to a player's OPAC bonus chunk claims (never below 0); false when OPAC refused. The value it
     * leaves goes into {@link OpacBonusLedger} (review finding 12); a raw value that differs from the last one Eden left
     * is an admin's change made while running, which stays (the delta applies on top of it).
     */
    private static boolean addBonus(MinecraftServer server, OpenPACServerAPI api, UUID player, int delta) {
        if (delta == 0) {
            return true;
        }
        IPlayerConfigAPI config = api.getPlayerConfigManager().getLoadedConfig(player);
        int raw = raw(config);
        OpacBonusLedger ledger = OpacBonusLedger.get(server);
        Integer expected = ledger == null ? null : ledger.expected(player);
        if (expected != null && expected != raw) {
            EdenLD.LOGGER.info("OPAC bonus chunk claims of {} were changed outside Eden ({} -> {}); keeping it",
                    player, expected, raw);
        }
        int next = Math.max(0, raw + delta);
        IPlayerConfigAPI.SetResult result = config.tryToSet(PlayerConfigOptions.BONUS_CHUNK_CLAIMS, next);
        if (result != IPlayerConfigAPI.SetResult.SUCCESS) {
            EdenLD.LOGGER.warn("Couldn't set OPAC bonus chunk claims of {} to {}: {}", player, next, result);
            return false;
        }
        if (ledger != null) {
            ledger.set(player, next);
        }
        return true;
    }

    /** A player's own bonus claims (level perks), independent of guilds. */
    static boolean addPlayerBonus(MinecraftServer server, UUID player, int delta) {
        return addBonus(server, OpenPACServerAPI.get(server), player, delta);
    }

    /** Re-owns every chunk claimed by {@code from} to {@code to} (keeping claim times); returns which moved. */
    private static List<Chunk> moveClaims(MinecraftServer server, IServerClaimsManagerAPI claims, UUID from, UUID to) {
        List<Chunk> chunks = chunksOf(server, claims, from);
        reclaim(claims, chunks, to);
        return chunks;
    }

    /** Claims {@code chunks} for {@code to}, keeping their claim times and forceload state. */
    private static void reclaim(IServerClaimsManagerAPI claims, List<Chunk> chunks, UUID to) {
        // Sub-configs are per player, so moved chunks use the new leader's main claim config (-1).
        ClaimTimes.keepTimes = true;
        try {
            for (Chunk chunk : chunks) {
                claims.claim(chunk.dimension(), to, -1, chunk.x(), chunk.z(), chunk.forceload());
            }
        } finally {
            ClaimTimes.keepTimes = false;
        }
    }
}
