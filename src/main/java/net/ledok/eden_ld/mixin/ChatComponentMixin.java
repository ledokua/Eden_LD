package net.ledok.eden_ld.mixin;

import net.ledok.eden_ld.client.chat.ChatFilter;
import net.minecraft.client.GuiMessage;
import net.minecraft.client.gui.components.ChatComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Lines of kinds the player hid (Settings → Chat) stay in the chat's history but aren't shown;
 * {@link ChatFilter#refresh} lays the window out again when the rules change.
 */
@Mixin(ChatComponent.class)
public abstract class ChatComponentMixin {

    @Inject(method = "addMessageToDisplayQueue", at = @At("HEAD"), cancellable = true)
    private void eden_ld$hideFiltered(GuiMessage message, CallbackInfo ci) {
        if (ChatFilter.hidden(message)) {
            ci.cancel();
        }
    }
}
