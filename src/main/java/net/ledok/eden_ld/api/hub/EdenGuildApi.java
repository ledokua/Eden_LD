package net.ledok.eden_ld.api.hub;

import net.ledok.eden_ld.hub.guild.Guild;
import net.ledok.eden_ld.hub.guild.GuildManager;
import net.minecraft.server.MinecraftServer;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Read access to Eden_LD guilds for other mods (source-stable). Server side, server thread.
 * Guilds are persistent; members can be offline. Friendly fire between guildmates is off
 * (the same protection as parties, suspendable through {@link EdenPartyApi}).
 */
public final class EdenGuildApi {

    private EdenGuildApi() {
    }

    /** Ranks from lowest to highest. */
    public enum Rank { RECRUIT, MEMBER, OFFICER, VICE_LEADER, LEADER }

    /** Immutable snapshot; {@code tag} ≤ 5 symbols, {@code color} RGB; members in join order. */
    public record GuildInfo(UUID id, String name, String tag, int color, UUID leader, Map<UUID, Rank> members) {
    }

    public interface MembershipListener {
        default void onJoin(UUID guildId, UUID player) {
        }

        /** Also fires for kicks and disbanding. */
        default void onLeave(UUID guildId, UUID player) {
        }
    }

    private static final List<MembershipListener> LISTENERS = new CopyOnWriteArrayList<>();

    public static Optional<GuildInfo> getGuild(MinecraftServer server, UUID player) {
        Guild guild = GuildManager.get(server).guildOf(player);
        return guild == null ? Optional.empty() : Optional.of(info(guild));
    }

    public static Optional<GuildInfo> getGuildById(MinecraftServer server, UUID guildId) {
        Guild guild = GuildManager.get(server).byId(guildId);
        return guild == null ? Optional.empty() : Optional.of(info(guild));
    }

    public static boolean sameGuild(MinecraftServer server, UUID a, UUID b) {
        return GuildManager.get(server).sameGuild(a, b);
    }

    public static void addMembershipListener(MembershipListener listener) {
        LISTENERS.add(listener);
    }

    private static GuildInfo info(Guild guild) {
        Map<UUID, Rank> members = new LinkedHashMap<>();
        guild.members.forEach((uuid, rank) -> members.put(uuid, Rank.values()[rank.level]));
        return new GuildInfo(guild.id, guild.name, guild.tag, guild.color, guild.leader(), Map.copyOf(members));
    }

    /** Internal — fired by GuildManager; not API. */
    public static void notifyJoin(UUID guildId, UUID player) {
        LISTENERS.forEach(listener -> listener.onJoin(guildId, player));
    }

    /** Internal — fired by GuildManager; not API. */
    public static void notifyLeave(UUID guildId, UUID player) {
        LISTENERS.forEach(listener -> listener.onLeave(guildId, player));
    }
}
