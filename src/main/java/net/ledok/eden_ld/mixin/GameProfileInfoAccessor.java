package net.ledok.eden_ld.mixin;

import com.mojang.authlib.GameProfile;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** One user cache entry's profile (the class is package-private). */
@Mixin(targets = "net.minecraft.server.players.GameProfileCache$GameProfileInfo")
public interface GameProfileInfoAccessor {
    @Accessor("profile")
    GameProfile eden_ld$profile();
}
