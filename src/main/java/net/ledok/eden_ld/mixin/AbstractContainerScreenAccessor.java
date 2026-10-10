package net.ledok.eden_ld.mixin;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Where a container screen's panel sits — it moves when a recipe book opens beside it. */
@Mixin(AbstractContainerScreen.class)
public interface AbstractContainerScreenAccessor {

    @Accessor("leftPos")
    int eden_ld$leftPos();

    @Accessor("topPos")
    int eden_ld$topPos();
}
