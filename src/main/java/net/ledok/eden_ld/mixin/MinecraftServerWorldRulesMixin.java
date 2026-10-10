package net.ledok.eden_ld.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import net.ledok.eden_ld.world.WorldBorders;
import net.ledok.eden_ld.world.DimensionGameRules;
import net.ledok.eden_ld.world.WorldRules;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.border.BorderChangeListener;
import net.minecraft.world.level.border.WorldBorder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The server-wide side of the dimensions' own settings (see {@link WorldRules}). */
@Mixin(MinecraftServer.class)
public abstract class MinecraftServerWorldRulesMixin {

    /**
     * The server's gamerules are the server-wide ones even when the Overworld changes some for itself:
     * vanilla returns the Overworld's ({@code overworld().getGameRules()}), and {@code /gamerule} — like every
     * mod asking the server — must keep meaning the whole server.
     */
    @ModifyReturnValue(method = "getGameRules", at = @At("RETURN"))
    private GameRules eden_ld$serverWide(GameRules rules) {
        return rules instanceof DimensionGameRules own ? own.serverRules() : rules;
    }

    /** Vanilla lets every dimension spawn monsters unless the server is on Peaceful; a dimension's own difficulty decides for it. */
    @Inject(method = "updateMobSpawningFlags", at = @At("TAIL"))
    private void eden_ld$dimensionSpawning(CallbackInfo ci) {
        WorldRules.updateSpawning((MinecraftServer) (Object) this);
    }

    /**
     * Each dimension keeps its own border (see {@link WorldBorders}): vanilla makes every other dimension's border
     * follow the Overworld's ...
     */
    @WrapWithCondition(method = "createLevels", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/border/WorldBorder;addListener(Lnet/minecraft/world/level/border/BorderChangeListener;)V"))
    private boolean eden_ld$ownBorders(WorldBorder overworld, BorderChangeListener follower) {
        return !WorldBorders.MANAGED;
    }

    /** ... and sends the Overworld's border changes to every player, wherever they are; Eden tells each dimension's players its own. */
    @WrapWithCondition(method = "createLevels", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/players/PlayerList;addWorldborderListener(Lnet/minecraft/server/level/ServerLevel;)V"))
    private boolean eden_ld$ownBorderUpdates(PlayerList players, ServerLevel overworld) {
        return !WorldBorders.MANAGED;
    }

    /** {@code /difficulty} told every player the server's difficulty; players in a dimension with its own hear that one again. */
    @Inject(method = "setDifficulty", at = @At("TAIL"))
    private void eden_ld$dimensionDifficulty(CallbackInfo ci) {
        WorldRules.resendDifficulty((MinecraftServer) (Object) this);
    }
}
