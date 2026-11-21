package net.ledok.eden_ld.hub.guild;

import net.ledok.eden_ld.compat.opac.OpacCompat;
import net.ledok.eden_ld.EdenData;
import net.ledok.eden_ld.EdenLD;
import net.ledok.eden_ld.api.hub.EdenGuildApi;
import net.minecraft.ChatFormatting;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * All guilds (world {@link SavedData}) and every rule about them. Server thread only.
 * Mutating methods return null on success or a player-facing error. Invites live in
 * memory (they expire anyway); applications are saved with the guild.
 *
 * <p>Rank rules: officers invite, handle applications, set the MOTD, and kick/promote/demote
 * ranks below their own up to member; the leader also manages officers, settings, transfer,
 * and disband.
 */
public final class GuildManager extends SavedData {

    private static final Factory<GuildManager> FACTORY = new Factory<>(GuildManager::new, GuildManager::load, null);

    public static final int MAX_NAME_LENGTH = GuildRules.MAX_NAME_LENGTH;
    public static final int MIN_NAME_LENGTH = GuildRules.MIN_NAME_LENGTH;
    public static final int MAX_TAG_LENGTH = GuildRules.MAX_TAG_LENGTH;
    public static final int MAX_MOTD_LENGTH = 120;
    private static final int CHAT_HISTORY_LIMIT = 50;
    private static final long INVITE_EXPIRY_MS = 30 * 60 * 1000L;
    private static final long APPLICATION_EXPIRY_MS = 7L * 24 * 60 * 60 * 1000;

    public record Invite(UUID guildId, String inviterName, long expiresAt) {
    }

    private final Map<UUID, Guild> guilds = new LinkedHashMap<>();
    private final Map<UUID, UUID> memberIndex = new HashMap<>();
    /** invitee → guild → invite. */
    private final Map<UUID, Map<UUID, Invite>> invites = new HashMap<>();
    /** Officers hear about one player's application to one guild at most this often (review finding 29). */
    private static final long APPLICATION_NOTICE_MS = 10 * 60 * 1000L;
    /**
     * applicant → guild → when its officers were last told (review finding 29). In memory only, and kept through
     * {@link #cancelApplication}, so an Apply / Cancel loop can't flood officers' chat; entries older than
     * {@link #APPLICATION_NOTICE_MS} are dropped on the next application.
     */
    private final Map<UUID, Map<UUID, Long>> applicationNotices = new HashMap<>();

    public static GuildManager get(MinecraftServer server) {
        return EdenData.get(server, FACTORY, "guilds");
    }

    // ---------------------------------------------------------------- queries

    @Nullable
    public Guild guildOf(UUID player) {
        UUID id = memberIndex.get(player);
        return id == null ? null : guilds.get(id);
    }

    @Nullable
    public Guild byId(UUID id) {
        return guilds.get(id);
    }

    @Nullable
    public Guild byTag(String tag) {
        for (Guild guild : guilds.values()) {
            if (guild.tag.equalsIgnoreCase(tag)) {
                return guild;
            }
        }
        return null;
    }

    public Collection<Guild> all() {
        return guilds.values();
    }

    public boolean sameGuild(UUID a, UUID b) {
        UUID ga = memberIndex.get(a);
        return ga != null && ga.equals(memberIndex.get(b));
    }

    public int memberLimit(Guild guild) {
        return Math.max(2, EdenLD.CONFIG.hub.guild_member_limit)
                + guild.bank.memberLevel * Math.max(0, EdenLD.CONFIG.hub.guild_member_upgrade_step);
    }

    /** Live invites to this player (expired ones and ones from deleted guilds dropped). */
    public Map<UUID, Invite> invitesFor(UUID player) {
        Map<UUID, Invite> waiting = invites.get(player);
        if (waiting == null) {
            return Map.of();
        }
        long now = System.currentTimeMillis();
        waiting.values().removeIf(invite -> invite.expiresAt < now || !guilds.containsKey(invite.guildId));
        return Map.copyOf(waiting);
    }

    /** Players this guild has invited (still pending): invitee → invite. */
    public Map<UUID, Invite> outgoingInvites(UUID guildId) {
        Map<UUID, Invite> out = new LinkedHashMap<>();
        invites.forEach((invitee, byGuild) -> {
            Invite invite = byGuild.get(guildId);
            if (invite != null && invite.expiresAt >= System.currentTimeMillis()) {
                out.put(invitee, invite);
            }
        });
        return out;
    }

    /** Guild ids this player has a pending application with. */
    public List<UUID> applicationsOf(UUID player) {
        List<UUID> out = new ArrayList<>();
        for (Guild guild : guilds.values()) {
            pruneApplications(guild);
            if (guild.applications.containsKey(player)) {
                out.add(guild.id);
            }
        }
        return out;
    }

    private void pruneApplications(Guild guild) {
        long cutoff = System.currentTimeMillis() - APPLICATION_EXPIRY_MS;
        if (guild.applications.values().removeIf(time -> time < cutoff)) {
            guild.applicantNames.keySet().retainAll(guild.applications.keySet());
            setDirty();
        }
    }

    public void pruneApplicationsOf(Guild guild) {
        pruneApplications(guild);
    }

    // ---------------------------------------------------------------- validation

    /** Length and uniqueness by {@link GuildRules#nameProblem}, and no characters chat refuses. */
    @Nullable
    public Component validateName(String name, @Nullable Guild self) {
        String trimmed = name.trim();
        String problem = GuildRules.nameProblem(trimmed, guilds.values().stream()
                .filter(guild -> guild != self).map(guild -> guild.name).toList());
        if ("bad_name".equals(problem)) {
            return error("bad_name", MIN_NAME_LENGTH, MAX_NAME_LENGTH);
        }
        if (!net.ledok.eden_ld.chat.ChatGuard.allowedText(trimmed)) {
            return Component.translatable("message.eden_ld.text.illegal_characters");
        }
        return problem == null ? null : error(problem, trimmed);
    }

    /** 1–5 letters or digits (any alphabet — Cyrillic tags are fine). */
    @Nullable
    public Component validateTag(String tag, @Nullable Guild self) {
        String trimmed = tag.trim();
        String problem = GuildRules.tagProblem(trimmed, guilds.values().stream()
                .filter(guild -> guild != self).map(guild -> guild.tag).toList());
        if ("bad_tag".equals(problem)) {
            return error("bad_tag", MAX_TAG_LENGTH);
        }
        return problem == null ? null : error(problem, trimmed);
    }

    // ---------------------------------------------------------------- lifecycle

    /** Creates a guild with {@code founder} as leader (costs are charged by the caller). */
    @Nullable
    public Component create(ServerPlayer founder, String name, String tag, int color) {
        if (guildOf(founder.getUUID()) != null) {
            return error("already_in_guild");
        }
        Component bad = validateName(name, null);
        if (bad == null) {
            bad = validateTag(tag, null);
        }
        if (bad != null) {
            return bad;
        }
        Guild guild = new Guild(UUID.randomUUID(), name.trim(), tag.trim(), color & 0xFFFFFF,
                System.currentTimeMillis());
        guilds.put(guild.id, guild);
        addMember(guild, founder.getUUID(), founder.getGameProfile().getName(), GuildRank.LEADER);
        return null;
    }

    private void addMember(Guild guild, UUID player, String name, GuildRank rank) {
        guild.members.put(player, rank);
        guild.memberNames.put(player, name);
        guild.joinedAt.put(player, System.currentTimeMillis());
        memberIndex.put(player, guild.id);
        // Joining one guild ends every other pending relationship.
        invites.remove(player);
        for (Guild other : guilds.values()) {
            if (other.applications.remove(player) != null) {
                other.applicantNames.remove(player);
            }
        }
        setDirty();
        EdenGuildApi.notifyJoin(guild.id, player);
    }

    private void removeMember(Guild guild, UUID player) {
        guild.members.remove(player);
        guild.memberNames.remove(player);
        guild.joinedAt.remove(player);
        memberIndex.remove(player);
        setDirty();
        EdenGuildApi.notifyLeave(guild.id, player);
    }

    @Nullable
    private OpacCompat.Released delete(MinecraftServer server, Guild guild) {
        // Guild land stays with the leader only up to their own limit (OPAC); the rest becomes free land. Tell them
        // what went and where, so they can claim it again quickly (the server log lists every chunk too).
        UUID leader = guild.leader();
        OpacCompat.Released released = OpacCompat.onGuildDeleted(server, guild);
        if (released != null && leader != null) {
            notify(server, leader, "claims_released_unclaimed", released.chunks());
            ServerPlayer online = server.getPlayerList().getPlayer(leader);
            if (online != null) {
                int shown = Math.min(FREED_LISTED, released.freed().size());
                for (OpacCompat.FreedChunk chunk : released.freed().subList(0, shown)) {
                    online.sendSystemMessage(Component.translatableWithFallback("message.eden_ld.guild.claims_freed_chunk",
                            "Freed: %1$s, chunk %2$s, %3$s (block %4$s, %5$s)",
                            chunk.dimension().toString(), chunk.x(), chunk.z(), chunk.x() * 16 + 8, chunk.z() * 16 + 8)
                            .withStyle(ChatFormatting.GRAY));
                }
                if (released.freed().size() > shown) {
                    online.sendSystemMessage(Component.translatableWithFallback("message.eden_ld.guild.claims_freed_more",
                            "\u2026and %s more (the server log lists them all).",
                            released.freed().size() - shown).withStyle(ChatFormatting.GRAY));
                }
            }
        }
        List<UUID> members = new ArrayList<>(guild.members.keySet());
        for (UUID member : members) {
            removeMember(guild, member);
            notify(server, member, "disbanded", guild.name);
        }
        guilds.remove(guild.id);
        invites.values().forEach(byGuild -> byGuild.remove(guild.id));
        setDirty();
        return released;
    }

    // ---------------------------------------------------------------- invites & applications

    @Nullable
    public Component invite(ServerPlayer actor, ServerPlayer target) {
        Guild guild = guildOf(actor.getUUID());
        if (guild == null) {
            return error("not_in_guild");
        }
        if (!guild.rank(actor.getUUID()).atLeast(GuildRank.OFFICER)) {
            return error("no_permission");
        }
        if (guildOf(target.getUUID()) != null) {
            return error("target_in_guild", target.getGameProfile().getName());
        }
        if (guild.members.size() + outgoingInvites(guild.id).size() >= memberLimit(guild)) {
            return error("full");
        }
        if (invitesFor(target.getUUID()).containsKey(guild.id)) {
            return error("already_invited", target.getGameProfile().getName());
        }
        MinecraftServer server = actor.getServer();
        if (server != null && net.ledok.eden_ld.hub.IgnoreList.ignores(server, target.getUUID(), actor.getUUID())) {
            return error("invites_blocked", target.getGameProfile().getName());
        }
        invites.computeIfAbsent(target.getUUID(), k -> new HashMap<>())
                .put(guild.id, new Invite(guild.id, actor.getGameProfile().getName(),
                        System.currentTimeMillis() + INVITE_EXPIRY_MS));
        target.sendSystemMessage(Component.translatable("message.eden_ld.guild.invited",
                        actor.getGameProfile().getName(), guild.name)
                .withStyle(ChatFormatting.GOLD));
        return null;
    }

    public void cancelInvite(UUID actor, UUID target) {
        Guild guild = guildOf(actor);
        Map<UUID, Invite> waiting = invites.get(target);
        if (guild != null && waiting != null && guild.rank(actor).atLeast(GuildRank.OFFICER)) {
            waiting.remove(guild.id);
        }
    }

    @Nullable
    public Component acceptInvite(MinecraftServer server, ServerPlayer player, UUID guildId) {
        if (guildOf(player.getUUID()) != null) {
            return error("already_in_guild");
        }
        Invite invite = invitesFor(player.getUUID()).get(guildId);
        Guild guild = guilds.get(guildId);
        if (invite == null || guild == null) {
            return error("no_invite");
        }
        if (guild.members.size() >= memberLimit(guild)) {
            return error("full");
        }
        addMember(guild, player.getUUID(), player.getGameProfile().getName(), GuildRank.RECRUIT);
        notifyMembers(server, guild, "member_joined", player.getGameProfile().getName());
        return null;
    }

    public void declineInvite(UUID player, UUID guildId) {
        Map<UUID, Invite> waiting = invites.get(player);
        if (waiting != null) {
            waiting.remove(guildId);
        }
    }

    @Nullable
    public Component apply(MinecraftServer server, ServerPlayer player, UUID guildId) {
        Guild guild = guilds.get(guildId);
        if (guild == null) {
            return error("unknown");
        }
        if (guildOf(player.getUUID()) != null) {
            return error("already_in_guild");
        }
        if (guild.joinMode != Guild.JoinMode.APPLICATIONS) {
            return error("not_accepting");
        }
        if (guild.applications.containsKey(player.getUUID())) {
            return error("already_applied");
        }
        long now = System.currentTimeMillis();
        guild.applications.put(player.getUUID(), now);
        guild.applicantNames.put(player.getUUID(), player.getGameProfile().getName());
        setDirty();
        // Once per applicant and guild every APPLICATION_NOTICE_MS, and not to officers who ignore the applicant
        // (review finding 29): Apply / Cancel in a loop used to post a line to every officer per click.
        applicationNotices.values().removeIf(byGuild -> {
            byGuild.values().removeIf(at -> now - at >= APPLICATION_NOTICE_MS);
            return byGuild.isEmpty();
        });
        Map<UUID, Long> told = applicationNotices.computeIfAbsent(player.getUUID(), k -> new HashMap<>());
        if (told.putIfAbsent(guildId, now) == null) {
            guild.members.forEach((member, rank) -> {
                if (rank.atLeast(GuildRank.OFFICER)
                        && !net.ledok.eden_ld.hub.IgnoreList.ignores(server, member, player.getUUID())) {
                    notify(server, member, "application_received", player.getGameProfile().getName());
                }
            });
        }
        return null;
    }

    public void cancelApplication(UUID player, UUID guildId) {
        Guild guild = guilds.get(guildId);
        if (guild != null && guild.applications.remove(player) != null) {
            guild.applicantNames.remove(player);
            setDirty();
        }
    }

    @Nullable
    public Component acceptApplication(MinecraftServer server, UUID actor, UUID applicant) {
        Guild guild = guildOf(actor);
        if (guild == null || !guild.rank(actor).atLeast(GuildRank.OFFICER)) {
            return error("no_permission");
        }
        if (!guild.applications.containsKey(applicant)) {
            return error("no_application");
        }
        String name = guild.applicantNames.getOrDefault(applicant, "?");
        if (guildOf(applicant) != null) {
            guild.applications.remove(applicant);
            guild.applicantNames.remove(applicant);
            setDirty();
            return error("target_in_guild", name);
        }
        if (guild.members.size() >= memberLimit(guild)) {
            return error("full");
        }
        // Not into a guild mid-run, mid-duel or in the tutorial, the one way in that skipped the busy lock (review
        // finding 68); the application stays for later.
        if (net.ledok.busylib.BusyState.isBusy(applicant)) {
            return error("target_busy", name);
        }
        addMember(guild, applicant, name, GuildRank.RECRUIT);
        notifyMembers(server, guild, "member_joined", name);
        return null;
    }

    public void declineApplication(UUID actor, UUID applicant) {
        Guild guild = guildOf(actor);
        if (guild != null && guild.rank(actor).atLeast(GuildRank.OFFICER)
                && guild.applications.remove(applicant) != null) {
            guild.applicantNames.remove(applicant);
            setDirty();
        }
    }

    /** Instant join for OPEN guilds. */
    @Nullable
    public Component join(MinecraftServer server, ServerPlayer player, UUID guildId) {
        Guild guild = guilds.get(guildId);
        if (guild == null) {
            return error("unknown");
        }
        if (guildOf(player.getUUID()) != null) {
            return error("already_in_guild");
        }
        if (guild.joinMode != Guild.JoinMode.OPEN) {
            return error("not_open");
        }
        if (guild.members.size() >= memberLimit(guild)) {
            return error("full");
        }
        addMember(guild, player.getUUID(), player.getGameProfile().getName(), GuildRank.RECRUIT);
        notifyMembers(server, guild, "member_joined", player.getGameProfile().getName());
        return null;
    }

    // ---------------------------------------------------------------- membership

    /**
     * A leader with other members must transfer first; a leader alone disbands the guild with the same bank check and
     * land warning as {@link #disband} (review finding 55: leaving used to delete the guild with no word about the land
     * over the leader's own limit); the warning tells them to press Leave again.
     */
    @Nullable
    public Component leave(MinecraftServer server, ServerPlayer player) {
        Guild guild = guildOf(player.getUUID());
        if (guild == null) {
            return error("not_in_guild");
        }
        if (guild.rank(player.getUUID()) == GuildRank.LEADER) {
            if (guild.members.size() > 1) {
                return error("leader_must_transfer");
            }
            if (GuildBankService.holdsAnything(guild)) {
                return error("bank_not_empty");
            }
            Component landWarning = landWarning(server, guild, player.getUUID(), true);
            if (landWarning != null) {
                return landWarning;
            }
            delete(server, guild);
            return null;
        }
        removeMember(guild, player.getUUID());
        notifyMembers(server, guild, "member_left", player.getGameProfile().getName());
        return null;
    }

    @Nullable
    public Component kick(MinecraftServer server, UUID actor, UUID target) {
        Guild guild = guildOf(actor);
        if (guild == null || !guild.isMember(target) || actor.equals(target)) {
            return error("no_permission");
        }
        GuildRank actorRank = guild.rank(actor);
        if (!actorRank.atLeast(GuildRank.OFFICER) || guild.rank(target).level >= actorRank.level) {
            return error("no_permission");
        }
        String name = guild.memberNames.getOrDefault(target, "?");
        removeMember(guild, target);
        notify(server, target, "kicked", guild.name);
        notifyMembers(server, guild, "member_kicked", name);
        return null;
    }

    /** One step up; officers may raise recruits to member, the leader anyone up to officer. */
    @Nullable
    public Component promote(UUID actor, UUID target) {
        Guild guild = guildOf(actor);
        if (guild == null || !guild.isMember(target)) {
            return error("no_permission");
        }
        GuildRank current = guild.rank(target);
        GuildRank next = GuildRank.of(current.level + 1);
        GuildRank actorRank = guild.rank(actor);
        // Up to one step below your own rank: leader → vice-leader, vice → officer, officer → member.
        boolean allowed = actorRank.atLeast(GuildRank.OFFICER) && next != current
                && next.level < actorRank.level && next != GuildRank.LEADER;
        if (!allowed) {
            return error("no_permission");
        }
        guild.members.put(target, next);
        setDirty();
        return null;
    }

    /** One step down; officers may lower members to recruit, the leader officers too. */
    @Nullable
    public Component demote(UUID actor, UUID target) {
        Guild guild = guildOf(actor);
        if (guild == null || !guild.isMember(target)) {
            return error("no_permission");
        }
        GuildRank current = guild.rank(target);
        GuildRank actorRank = guild.rank(actor);
        // Only ranks below your own; officers can only lower members to recruit.
        boolean allowed = actorRank.atLeast(GuildRank.OFFICER) && current != GuildRank.RECRUIT
                && current.level < actorRank.level;
        if (!allowed) {
            return error("no_permission");
        }
        guild.members.put(target, GuildRank.of(current.level - 1));
        setDirty();
        return null;
    }

    /** Leadership to another member; the old leader becomes the vice-leader. */
    @Nullable
    public Component transfer(MinecraftServer server, UUID actor, UUID target) {
        Guild guild = guildOf(actor);
        if (guild == null || guild.rank(actor) != GuildRank.LEADER || !guild.isMember(target) || actor.equals(target)) {
            return error("no_permission");
        }
        Component overLimit = transferOverLimit(server, guild, target);
        if (overLimit != null) {
            return overLimit;
        }
        guild.members.put(actor, GuildRank.VICE_LEADER);
        guild.members.put(target, GuildRank.LEADER);
        setDirty();
        notifyMembers(server, guild, "new_leader", guild.memberNames.getOrDefault(target, "?"));
        return null;
    }

    /**
     * Refuses a leadership move that would put the new leader over their claim limit (review finding 11). The move takes
     * every chunk of the old leader to the new one through OPAC's raw claim, which checks no limit; over it, OPAC's
     * over-limit rule then throttles every member's block actions on all guild land. Null when it fits or without guild
     * claims. Which land moves stays as documented (all of it): keeping the old leader's own share is the owner's call.
     */
    @Nullable
    private static Component transferOverLimit(MinecraftServer server, Guild guild, UUID target) {
        OpacCompat.ClaimsInfo after = overLimitAfter(server, guild, target);
        if (after == null) {
            return null;
        }
        int excess = after.used() - after.limit();
        return Component.translatableWithFallback("message.eden_ld.guild.transfer_over_limit",
                "%1$s can't take over the guild's land: with it they would hold %2$s claimed chunks, %3$s over their "
                + "claim limit of %4$s. Unclaim %3$s chunks (or buy claim upgrades) first, then try again.",
                guild.memberNames.getOrDefault(target, "?"), after.used(), excess, after.limit());
    }

    /** What {@code target} would hold and may hold as leader, only when it's over the limit; null otherwise. */
    @Nullable
    private static OpacCompat.ClaimsInfo overLimitAfter(MinecraftServer server, Guild guild, UUID target) {
        OpacCompat.ClaimsInfo after = OpacCompat.afterTransfer(server, guild, target);
        return after == null || after.used() <= after.limit() ? null : after;
    }

    /**
     * For staff after {@link #staffTransfer} (call it before the next guild sync, which moves the land): a note saying
     * the new leader will be over their claim limit with the guild's land, or null when the land fits (review finding 11
     * follow-up: staff aren't refused, but told).
     */
    @Nullable
    public static Component staffTransferNote(MinecraftServer server, Guild guild, UUID target) {
        OpacCompat.ClaimsInfo over = overLimitAfter(server, guild, target);
        if (over == null) {
            return null;
        }
        return Component.translatableWithFallback("message.eden_ld.moderation.guild.transfer_over_limit",
                "Note: with the guild's land, %1$s holds %2$s claimed chunks, %3$s over their claim limit of %4$s. "
                + "Until chunks are unclaimed or claim upgrades bought, OPAC restricts what members can do on the "
                + "guild's land.",
                guild.memberNames.getOrDefault(target, "?"), over.used(), over.used() - over.limit(), over.limit());
    }

    @Nullable
    public Component disband(MinecraftServer server, UUID actor) {
        Guild guild = guildOf(actor);
        if (guild == null || guild.rank(actor) != GuildRank.LEADER) {
            return error("no_permission");
        }
        // Disbanding would destroy the bank's LC and items: empty it first.
        if (GuildBankService.holdsAnything(guild)) {
            return error("bank_not_empty");
        }
        Component landWarning = landWarning(server, guild, actor);
        if (landWarning != null) {
            return landWarning;
        }
        delete(server, guild);
        return null;
    }

    /** Admin path: works with every member offline. What was freed of the leader's land; null when nothing. */
    @Nullable
    public OpacCompat.Released forceDisband(MinecraftServer server, Guild guild) {
        return delete(server, guild);
    }

    /** Freed chunks listed in the leader's chat after a disband (all of them are in the server log). */
    private static final int FREED_LISTED = 20;

    /** How long a disband warned about freeing land waits for the same person to disband again. */
    private static final long LAND_CONFIRM_MILLIS = 60_000;
    /** Who was warned about which guild's land, and until when the next disband counts as confirmed. */
    private static final Map<UUID, Map.Entry<UUID, Long>> LAND_WARNED = new HashMap<>();

    /**
     * The warning a disband that would free some of the leader's land gives first (OPAC: the chunks over the leader's
     * own limit become free land); null when nothing would be freed, or when {@code actor} was warned about this guild
     * in the last minute (their repeated disband confirms it). Also used by the admin disband.
     */
    @Nullable
    public static Component landWarning(MinecraftServer server, Guild guild, UUID actor) {
        return landWarning(server, guild, actor, false);
    }

    /** As {@link #landWarning(MinecraftServer, Guild, UUID)}; {@code leaving}: a sole leader's Leave, worded for it. */
    @Nullable
    private static Component landWarning(MinecraftServer server, Guild guild, UUID actor, boolean leaving) {
        int excess = OpacCompat.disbandExcess(server, guild);
        if (excess <= 0) {
            return null;
        }
        long now = System.currentTimeMillis();
        Map.Entry<UUID, Long> warned = LAND_WARNED.get(actor);
        if (warned != null && warned.getKey().equals(guild.id) && warned.getValue() >= now) {
            LAND_WARNED.remove(actor);
            return null;
        }
        LAND_WARNED.put(actor, Map.entry(guild.id, now + LAND_CONFIRM_MILLIS));
        if (leaving) {
            return Component.translatableWithFallback("message.eden_ld.guild.leave_frees_land",
                    "You're the only member, so leaving disbands the guild: %1$s chunks over your own claim limit "
                    + "(%2$s) will become free land, and anyone can claim them. Unclaim the ones you can spare first. "
                    + "To leave anyway, press Leave again within %3$s seconds.",
                    excess, Math.max(0, OpacCompat.ownLimit(server, guild)), LAND_CONFIRM_MILLIS / 1000);
        }
        return Component.translatableWithFallback("message.eden_ld.guild.disband_frees_land",
                "After the disband, %1$s chunks over your own claim limit (%2$s) will become free land, "
                + "and anyone can claim them. Unclaim the ones you can spare first. To disband anyway, "
                + "disband again within %3$s seconds.",
                excess,
                Math.max(0, OpacCompat.ownLimit(server, guild)), LAND_CONFIRM_MILLIS / 1000);
    }

    // ---------------------------------------------------------------- settings

    @Nullable
    public Component rename(UUID actor, String name) {
        Guild guild = leaderGuild(actor);
        if (guild == null) {
            return error("no_permission");
        }
        Component bad = validateName(name, guild);
        if (bad != null) {
            return bad;
        }
        guild.name = name.trim();
        setDirty();
        return null;
    }

    @Nullable
    public Component setTag(UUID actor, String tag) {
        Guild guild = leaderGuild(actor);
        if (guild == null) {
            return error("no_permission");
        }
        Component bad = validateTag(tag, guild);
        if (bad != null) {
            return bad;
        }
        guild.tag = tag.trim();
        setDirty();
        return null;
    }

    @Nullable
    public Component setColor(UUID actor, int color) {
        Guild guild = leaderGuild(actor);
        if (guild == null) {
            return error("no_permission");
        }
        guild.color = color & 0xFFFFFF;
        setDirty();
        return null;
    }

    @Nullable
    public Component setJoinMode(UUID actor, Guild.JoinMode mode) {
        Guild guild = leaderGuild(actor);
        if (guild == null) {
            return error("no_permission");
        }
        guild.joinMode = mode;
        setDirty();
        return null;
    }

    /** Officers and the leader. */
    @Nullable
    public Component setMotd(UUID actor, String motd) {
        Guild guild = guildOf(actor);
        if (guild == null || !guild.rank(actor).atLeast(GuildRank.OFFICER)) {
            return error("no_permission");
        }
        String trimmed = motd.trim();
        if (!net.ledok.eden_ld.chat.ChatGuard.allowedText(trimmed)) {
            return Component.translatable("message.eden_ld.text.illegal_characters");
        }
        // Every member reads it: not while muted (clearing it is fine).
        if (!trimmed.isEmpty() && net.ledok.eden_ld.chat.ChatGuard.mutedNow(actor)) {
            return Component.translatable("message.eden_ld.chat.muted");
        }
        guild.motd = trimmed.length() > MAX_MOTD_LENGTH ? trimmed.substring(0, MAX_MOTD_LENGTH) : trimmed;
        setDirty();
        return null;
    }

    // ---------------------------------------------------------------- staff (Moderation → Guilds)
    // The same rules as the members' own paths (names, tags, ranks, notices), without the actor's rank: staff act
    // from outside the guild, with every member offline if need be. Callers sync (GuildServer.syncAll) afterwards.

    @Nullable
    public Component staffRename(Guild guild, String name) {
        Component bad = validateName(name, guild);
        if (bad != null) {
            return bad;
        }
        guild.name = name.trim();
        setDirty();
        return null;
    }

    @Nullable
    public Component staffSetTag(Guild guild, String tag) {
        Component bad = validateTag(tag, guild);
        if (bad != null) {
            return bad;
        }
        guild.tag = tag.trim();
        setDirty();
        return null;
    }

    public void staffSetColor(Guild guild, int color) {
        guild.color = color & 0xFFFFFF;
        setDirty();
    }

    /** Removes a member (never the leader: leadership goes first); they and the guild are told as for a kick. */
    @Nullable
    public Component staffKick(MinecraftServer server, Guild guild, UUID target) {
        if (!guild.isMember(target)) {
            return error("staff_not_member");
        }
        if (guild.rank(target) == GuildRank.LEADER) {
            return error("staff_leader");
        }
        String name = guild.memberNames.getOrDefault(target, "?");
        removeMember(guild, target);
        notify(server, target, "kicked", guild.name);
        notifyMembers(server, guild, "member_kicked", name);
        return null;
    }

    /** Any rank below leader for any member but the leader (leadership moves only by {@link #staffTransfer}). */
    @Nullable
    public Component staffSetRank(Guild guild, UUID target, GuildRank rank) {
        if (!guild.isMember(target)) {
            return error("staff_not_member");
        }
        if (guild.rank(target) == GuildRank.LEADER || rank == GuildRank.LEADER) {
            return error("staff_leader");
        }
        guild.members.put(target, rank);
        setDirty();
        return null;
    }

    /** Leadership to another member, as the leader's own transfer: the old leader becomes the vice-leader. */
    @Nullable
    public Component staffTransfer(MinecraftServer server, Guild guild, UUID target) {
        if (!guild.isMember(target)) {
            return error("staff_not_member");
        }
        UUID leader = guild.leader();
        if (target.equals(leader)) {
            return error("staff_already_leader");
        }
        // Staff may move leadership over the new leader's limit (review finding 11 follow-up); it is logged, and
        // staffTransferNote tells the staff member.
        OpacCompat.ClaimsInfo over = overLimitAfter(server, guild, target);
        if (over != null) {
            EdenLD.LOGGER.warn("[Guilds] Staff made {} the leader of [{}] {} over their claim limit: {} claimed chunks "
                            + "after the move, {} over their limit of {}.", guild.memberNames.getOrDefault(target, "?"),
                    guild.tag, guild.name, over.used(), over.used() - over.limit(), over.limit());
        }
        if (leader != null) {
            guild.members.put(leader, GuildRank.VICE_LEADER);
        }
        guild.members.put(target, GuildRank.LEADER);
        setDirty();
        notifyMembers(server, guild, "new_leader", guild.memberNames.getOrDefault(target, "?"));
        return null;
    }

    /** One staff action into the guild's staff log (the newest {@link GuildRules#STAFF_LOG_LIMIT} kept). */
    public void addStaffLog(Guild guild, Guild.StaffEntry entry) {
        GuildRules.addCapped(guild.staffLog, entry, GuildRules.STAFF_LOG_LIMIT);
        setDirty();
    }

    @Nullable
    private Guild leaderGuild(UUID actor) {
        Guild guild = guildOf(actor);
        return guild != null && guild.rank(actor).manages() ? guild : null;
    }

    /** For the bank service: any bank change must be saved. */
    public void markChanged() {
        setDirty();
    }

    public void appendChat(Guild guild, Guild.ChatLine line) {
        guild.chat.add(line);
        while (guild.chat.size() > CHAT_HISTORY_LIMIT) {
            guild.chat.remove(0);
        }
        setDirty();
    }

    /** Keeps stored member names current (renamed accounts). */
    public void onJoin(ServerPlayer player) {
        Guild guild = guildOf(player.getUUID());
        if (guild != null && !player.getGameProfile().getName().equals(guild.memberNames.get(player.getUUID()))) {
            guild.memberNames.put(player.getUUID(), player.getGameProfile().getName());
            setDirty();
        }
    }

    // ---------------------------------------------------------------- messages

    private static Component error(String key, Object... args) {
        return Component.translatable("message.eden_ld.guild." + key, args);
    }

    private static void notify(MinecraftServer server, UUID player, String key, Object... args) {
        ServerPlayer online = server.getPlayerList().getPlayer(player);
        if (online != null) {
            online.sendSystemMessage(Component.translatable("message.eden_ld.guild." + key, args)
                    .withStyle(ChatFormatting.GOLD));
        }
    }

    private static void notifyMembers(MinecraftServer server, Guild guild, String key, Object... args) {
        for (UUID member : guild.members.keySet()) {
            notify(server, member, key, args);
        }
    }

    // ---------------------------------------------------------------- persistence

    private static GuildManager load(CompoundTag tag, HolderLookup.Provider registries) {
        GuildManager manager = new GuildManager();
        ListTag list = tag.getList("guilds", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            try {
                Guild guild = Guild.load(list.getCompound(i), registries);
                manager.guilds.put(guild.id, guild);
                guild.members.keySet().forEach(member -> manager.memberIndex.put(member, guild.id));
            } catch (Exception e) {
                EdenLD.LOGGER.error("[Guilds] Skipping an unreadable guild entry.", e);
            }
        }
        return manager;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        guilds.values().forEach(guild -> list.add(guild.save(registries)));
        tag.put("guilds", list);
        return tag;
    }

    /** Case-insensitive name lookup, for commands. */
    @Nullable
    public Guild byName(String name) {
        String wanted = name.toLowerCase(Locale.ROOT);
        for (Guild guild : guilds.values()) {
            if (guild.name.toLowerCase(Locale.ROOT).equals(wanted)) {
                return guild;
            }
        }
        return null;
    }
}
