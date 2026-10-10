package net.ledok.eden_ld.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.ledok.eden_ld.world.WorldBorders;
import net.minecraft.network.protocol.game.ClientboundInitializeBorderPacket;
import net.minecraft.network.protocol.game.ClientboundSetBorderCenterPacket;
import net.minecraft.world.level.border.WorldBorder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Border packets carry the stored center, as vanilla's always do — it only ever sends the Overworld's
 * border, whose center isn't scaled. With each dimension sending its own (see {@link WorldBorders}), a
 * Nether border's {@code getCenterX()} would arrive already divided by 8 and the client divides again.
 */
@Mixin({ClientboundInitializeBorderPacket.class, ClientboundSetBorderCenterPacket.class})
public abstract class BorderPacketMixin {

    @WrapOperation(method = "<init>(Lnet/minecraft/world/level/border/WorldBorder;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/border/WorldBorder;getCenterX()D"))
    private double eden_ld$storedCenterX(WorldBorder border, Operation<Double> original) {
        return WorldBorders.MANAGED ? ((WorldBorderAccessor) border).eden_ld$storedCenterX() : original.call(border);
    }

    @WrapOperation(method = "<init>(Lnet/minecraft/world/level/border/WorldBorder;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/border/WorldBorder;getCenterZ()D"))
    private double eden_ld$storedCenterZ(WorldBorder border, Operation<Double> original) {
        return WorldBorders.MANAGED ? ((WorldBorderAccessor) border).eden_ld$storedCenterZ() : original.call(border);
    }
}
