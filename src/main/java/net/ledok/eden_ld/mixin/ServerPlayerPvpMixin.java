package net.ledok.eden_ld.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.ledok.eden_ld.world.WorldRules;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * PvP per dimension: vanilla asks the server's {@code pvp} switch whenever a player is hurt by another
 * (melee, arrows, tridents, potions, explosions they caused) — through this one method, on the player
 * being hurt — so a dimension's PvP mode answers here: off only where the mode is off (see {@link WorldRules#pvp}).
 * Each player's own switch, parties, guilds, run teams and duels are decided next, by {@code canHarmPlayer}
 * ({@code PlayerMixin}).
 */
@Mixin(ServerPlayer.class)
public abstract class ServerPlayerPvpMixin {

    @ModifyReturnValue(method = "isPvpAllowed", at = @At("RETURN"))
    private boolean eden_ld$dimensionPvp(boolean server) {
        return WorldRules.pvp((ServerPlayer) (Object) this, server);
    }
}
