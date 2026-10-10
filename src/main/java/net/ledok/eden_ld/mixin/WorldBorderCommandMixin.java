package net.ledok.eden_ld.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.ledok.eden_ld.world.WorldBorders;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.commands.WorldBorderCommand;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.border.WorldBorder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * {@code /worldborder} changes the border of the dimension it runs in ({@code execute in <dimension> …}
 * from the console), as Multi World Borders did — vanilla always takes the Overworld's. A center is in that
 * dimension's own coordinates (see {@link WorldBorders}).
 */
@Mixin(WorldBorderCommand.class)
public abstract class WorldBorderCommandMixin {

    @WrapOperation(method = {"setDamageBuffer", "setDamageAmount", "setWarningTime", "setWarningDistance", "getSize",
            "setCenter", "setSize"},
            at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;getWorldBorder()Lnet/minecraft/world/level/border/WorldBorder;"))
    private static WorldBorder eden_ld$dimensionBorder(ServerLevel overworld, Operation<WorldBorder> original,
                                                       @Local(argsOnly = true) CommandSourceStack source) {
        return WorldBorders.MANAGED ? source.getLevel().getWorldBorder() : original.call(overworld);
    }

    @WrapOperation(method = "setCenter",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/border/WorldBorder;setCenter(DD)V"))
    private static void eden_ld$ownCoordinates(WorldBorder border, double x, double z, Operation<Void> original,
                                               @Local(argsOnly = true) CommandSourceStack source) {
        double scale = WorldBorders.MANAGED ? source.getLevel().dimensionType().coordinateScale() : 1;
        original.call(border, x * scale, z * scale);
    }
}
