package net.ledok.eden_ld.mixin;

import net.minecraft.server.players.GameProfileCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

/**
 * The user cache's entries (everyone who has joined), for the Moderation window's player search;
 * read through {@link GameProfileInfoAccessor} — no Mojang lookups.
 */
@Mixin(GameProfileCache.class)
public interface GameProfileCacheAccessor {
    @Accessor("profilesByName")
    Map<String, ?> eden_ld$profilesByName();
}
