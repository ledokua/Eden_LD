package net.ledok.eden_ld.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.ledok.eden_ld.world.WorldBorders;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.level.border.WorldBorder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * A player joining, respawning or changing dimension is sent the border of the dimension they're in —
 * vanilla always sends the Overworld's (see {@link WorldBorders}).
 */
@Mixin(PlayerList.class)
public abstract class PlayerListBorderMixin {

    @WrapOperation(method = "sendLevelInfo",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;getWorldBorder()Lnet/minecraft/world/level/border/WorldBorder;"))
    private WorldBorder eden_ld$dimensionBorder(ServerLevel overworld, Operation<WorldBorder> original,
                                                @Local(argsOnly = true) ServerLevel level) {
        return WorldBorders.MANAGED ? level.getWorldBorder() : original.call(overworld);
    }
}
