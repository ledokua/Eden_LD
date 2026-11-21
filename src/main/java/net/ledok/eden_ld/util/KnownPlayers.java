package net.ledok.eden_ld.util;

import com.mojang.authlib.GameProfile;
import net.ledok.eden_ld.mixin.GameProfileCacheAccessor;
import net.ledok.eden_ld.mixin.GameProfileInfoAccessor;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.GameProfileCache;

import java.util.Locale;
import java.util.Optional;

/**
 * Players by a typed name, without asking Mojang: online first, then the server's user cache (everyone who has
 * joined). {@code GameProfileCache.get(String)} looks a name it doesn't know up over HTTP on the calling thread, which
 * froze the server thread for every unknown name a player typed or sent (review finding 22, 1.3.130).
 */
public final class KnownPlayers {

    private KnownPlayers() {
    }

    /** The player with this name (any case), if online or known to this server; never a remote lookup. */
    public static Optional<GameProfile> byName(MinecraftServer server, String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        String trimmed = name.trim();
        ServerPlayer online = server.getPlayerList().getPlayerByName(trimmed);
        if (online != null) {
            return Optional.of(online.getGameProfile());
        }
        GameProfileCache cache = server.getProfileCache();
        if (cache == null) {
            return Optional.empty();
        }
        Object entry = ((GameProfileCacheAccessor) cache).eden_ld$profilesByName().get(trimmed.toLowerCase(Locale.ROOT));
        GameProfile profile = entry == null ? null : ((GameProfileInfoAccessor) entry).eden_ld$profile();
        return profile == null || profile.getId() == null ? Optional.empty() : Optional.of(profile);
    }
}
