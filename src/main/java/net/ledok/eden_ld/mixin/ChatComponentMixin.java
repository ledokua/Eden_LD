package net.ledok.eden_ld.mixin;

import net.ledok.eden_ld.client.chat.ChatActivity;
import net.ledok.eden_ld.client.chat.panel.ChatPanel;
import net.ledok.eden_ld.client.chat.panel.ChatStore;
import net.minecraft.client.Minecraft;
import net.minecraft.client.GuiMessage;
import net.minecraft.client.GuiMessageTag;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.network.chat.Style;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Eden's chat window ({@code docs/PLAN-custom-chat.md}): vanilla's chat still takes every message (its log, the
 * narrator, other mods), and Eden's store takes a copy. On Eden servers {@link ChatPanel} draws instead of vanilla's
 * window and answers what the chat screen asks it — the style under the mouse, the tag under it, scrolling, the
 * waiting-messages line. Eden's lines are kept across a reconfiguration as vanilla keeps its own.
 */
@Mixin(ChatComponent.class)
public abstract class ChatComponentMixin {

    @Inject(method = "addMessageToQueue", at = @At("HEAD"))
    private void eden_ld$store(GuiMessage message, CallbackInfo ci) {
        ChatActivity.added(message);
    }

    @Inject(method = "clearMessages", at = @At("HEAD"))
    private void eden_ld$clearStore(boolean clearSentMsgHistory, CallbackInfo ci) {
        ChatActivity.cleared();
    }

    /** A reconfiguration (a proxy's server switch): vanilla saves its chat, then clears it. */
    @Inject(method = "storeState", at = @At("RETURN"))
    private void eden_ld$storeState(CallbackInfoReturnable<ChatComponent.State> cir) {
        ChatActivity.stored(cir.getReturnValue());
    }

    @Inject(method = "restoreState", at = @At("TAIL"))
    private void eden_ld$restoreState(ChatComponent.State state, CallbackInfo ci) {
        ChatActivity.restored(state);
    }

    /** The server deleted a message: vanilla puts a marker in its place, and so does the store. */
    @Inject(method = "createDeletedMarker", at = @At("RETURN"))
    private void eden_ld$deleted(GuiMessage message, CallbackInfoReturnable<GuiMessage> cir) {
        ChatStore.replace(message, cir.getReturnValue());
    }

    @Inject(method = "rescaleChat", at = @At("HEAD"))
    private void eden_ld$relayout(CallbackInfo ci) {
        ChatPanel.invalidate();
    }

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void eden_ld$render(GuiGraphics graphics, int tickCount, int mouseX, int mouseY, boolean focused,
                                CallbackInfo ci) {
        if (ChatPanel.active()) {
            ChatPanel.render(graphics, tickCount, mouseX, mouseY, focused);
            ci.cancel();
        }
    }

    @Inject(method = "scrollChat", at = @At("HEAD"), cancellable = true)
    private void eden_ld$scroll(int lines, CallbackInfo ci) {
        if (ChatPanel.active()) {
            ChatPanel.scroll(lines);
            ci.cancel();
        }
    }

    @Inject(method = "resetChatScroll", at = @At("HEAD"))
    private void eden_ld$resetScroll(CallbackInfo ci) {
        ChatPanel.resetScroll();
    }

    @Inject(method = "getClickedComponentStyleAt", at = @At("HEAD"), cancellable = true)
    private void eden_ld$styleAt(double mouseX, double mouseY, CallbackInfoReturnable<Style> cir) {
        if (ChatPanel.active()) {
            cir.setReturnValue(ChatPanel.styleAt(mouseX, mouseY));
        }
    }

    /** Chat Delay's "N messages waiting" line, where Eden's window draws it: a click lets the next one through. */
    @Inject(method = "handleChatQueueClicked", at = @At("HEAD"), cancellable = true)
    private void eden_ld$queueClicked(double mouseX, double mouseY, CallbackInfoReturnable<Boolean> cir) {
        if (ChatPanel.active()) {
            boolean hit = ChatPanel.queueLineAt(mouseX, mouseY);
            if (hit) {
                Minecraft.getInstance().getChatListener().acceptNextDelayedMessage();
            }
            cir.setReturnValue(hit);
        }
    }

    @Inject(method = "getMessageTagAt", at = @At("HEAD"), cancellable = true)
    private void eden_ld$tagAt(double mouseX, double mouseY, CallbackInfoReturnable<GuiMessageTag> cir) {
        if (ChatPanel.active()) {
            cir.setReturnValue(ChatPanel.tagAt(mouseX, mouseY));
        }
    }
}
