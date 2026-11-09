package net.ledok.eden_ld.mixin;

import net.ledok.eden_ld.client.fix.CrumblingTest;
import net.minecraft.client.renderer.entity.ItemRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Dev only ({@link CrumblingTest}): stands in for the unknown mod that ends the crumbling buffer while a block entity
 * draws, by ending it before every item render. Does nothing outside that test.
 */
@Mixin(ItemRenderer.class)
public abstract class CrumblingTestTriggerMixin {

    @Inject(method = "render", at = @At("HEAD"))
    private void eden_ld$endCrumblingBuffer(CallbackInfo ci) {
        if (CrumblingTest.ENABLED && CrumblingTest.endCrumblingBufferNow()) {
            net.minecraft.client.Minecraft.getInstance().renderBuffers().crumblingBufferSource().endBatch();
        }
    }
}
