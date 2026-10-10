package net.ledok.eden_ld.mixin;

import net.minecraft.world.level.border.WorldBorder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** A border's stored center (Overworld units), which a scaled dimension's {@code getCenterX()} divides. */
@Mixin(WorldBorder.class)
public interface WorldBorderAccessor {

    @Accessor("centerX")
    double eden_ld$storedCenterX();

    @Accessor("centerZ")
    double eden_ld$storedCenterZ();
}
