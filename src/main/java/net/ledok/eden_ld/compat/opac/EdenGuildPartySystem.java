package net.ledok.eden_ld.compat.opac;

import net.ledok.eden_ld.hub.guild.Guild;
import net.ledok.eden_ld.hub.guild.GuildManager;
import net.ledok.eden_ld.hub.guild.GuildRank;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xaero.pac.common.server.parties.system.api.v2.IPlayerPartySystemAPI;

import java.util.UUID;

/**
 * Eden guilds as an OPAC party system. The leader is the party owner (and so, with party-owned
 * claims, the technical owner of the guild's claims). OPAC's rank equivalents: officers are
 * "Claimers" and "Moderators" (claim as the guild, add players to the claim config's groups);
 * the leader and vice-leader are "Admins" (edit the guild's claim config). Guilds have no
 * alliances.
 */
final class EdenGuildPartySystem implements IPlayerPartySystemAPI<Guild> {

    private final MinecraftServer server;

    EdenGuildPartySystem(MinecraftServer server) {
        this.server = server;
    }

    @Nullable
    private GuildManager guilds() {
        // OPAC may ask before the overworld (and so the saved data) exists.
        return server.overworld() == null ? null : GuildManager.get(server);
    }

    private boolean hasRank(UUID playerId, GuildRank rank) {
        Guild guild = getPartyByMember(playerId);
        return guild != null && guild.rank(playerId).atLeast(rank);
    }

    @Nullable
    @Override
    public Guild getPartyByOwner(@NotNull UUID playerId) {
        Guild guild = getPartyByMember(playerId);
        return guild != null && playerId.equals(guild.leader()) ? guild : null;
    }

    @Nullable
    @Override
    public Guild getPartyByMember(@NotNull UUID playerId) {
        GuildManager guilds = guilds();
        return guilds == null ? null : guilds.guildOf(playerId);
    }

    @Override
    public boolean isPlayerAllying(@NotNull UUID playerId, @NotNull UUID potentialAllyPlayerId) {
        return false;
    }

    @Override
    public boolean isPermittedToPartyClaim(@NotNull UUID playerId) {
        return hasRank(playerId, GuildRank.OFFICER);
    }

    @Override
    public boolean canEditPartyConfig(@NotNull UUID playerId) {
        return hasRank(playerId, GuildRank.VICE_LEADER);
    }

    @Override
    public boolean canCreatePartyConfigGroups(@NotNull UUID playerId) {
        return hasRank(playerId, GuildRank.VICE_LEADER);
    }

    @Override
    public boolean canIncludeGroupsInPartyConfigGroups(@NotNull UUID playerId) {
        return hasRank(playerId, GuildRank.VICE_LEADER);
    }

    @Override
    public boolean canIncludePlayersInPartyConfigGroups(@NotNull UUID playerId) {
        return hasRank(playerId, GuildRank.OFFICER);
    }

    @Nullable
    @Override
    public UUID getOwner(@NotNull Guild party) {
        return party.leader();
    }

    @Nullable
    @Override
    public Component getName(@NotNull Guild party) {
        return Component.literal(party.name);
    }

    @Override
    public int getMemberCount(@NotNull Guild party) {
        return party.members.size();
    }

    @Override
    public int getColor(@NotNull Guild party) {
        return party.color & 0xFFFFFF;
    }
}
