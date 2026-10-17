package net.ledok.eden_ld.mixin;

import net.ledok.eden_ld.client.chat.ui.EdenChatHost;
import net.ledok.eden_ld.client.chat.ui.EdenChatUi;
import net.minecraft.client.gui.screens.InBedChatScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The bed chat in the new look ({@code docs/PLAN-chat-v2.md}, phase 4b): its keys reach the chat's canvas (menus, the
 * GIF viewer, the emoji picker) before the bed's own handling — which wakes the player on Esc and sends the line on
 * Enter without asking the chat screen. So Esc closes a menu or the picker without waking, and Enter in the picker's
 * search picks instead of sending. Keys the canvas leaves go on as vanilla's; the old look is untouched.
 */
@Mixin(InBedChatScreen.class)
public abstract class InBedChatScreenMixin {

    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
    private void eden_ld$canvasKeys(int keyCode, int scanCode, int modifiers, CallbackInfoReturnable<Boolean> cir) {
        EdenChatUi host = ((EdenChatHost) this).eden_ld$host();
        if (host != null && host.canvas().keyPressed(keyCode, scanCode, modifiers)) {
            cir.setReturnValue(true);
        }
    }
}
