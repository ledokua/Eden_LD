package net.ledok.eden_ld.mixin;

import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * ChatPatches' right-click menu, drawn above the chat text. On 1.21.1 ChatPatches draws the menu
 * at the chat screen's own depth, while the game draws chat text 50 units up — so the lines
 * showed through the menu (with or without Eden). Lifted to 150: over the text and Eden's picker
 * (100), under command suggestions (200). Does nothing without ChatPatches.
 */
@Pseudo
@Mixin(targets = "obro1961.chatpatches.gui.ContextMenu", remap = false)
public abstract class ChatPatchesMenuMixin {

    @Inject(method = "render", at = @At("HEAD"), require = 0)
    private void eden_ld$raise(GuiGraphics graphics, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        graphics.pose().pushPose();
        graphics.pose().translate(0.0F, 0.0F, 150.0F);
    }

    @Inject(method = "render", at = @At("RETURN"), require = 0)
    private void eden_ld$lower(GuiGraphics graphics, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        graphics.pose().popPose();
    }
}
