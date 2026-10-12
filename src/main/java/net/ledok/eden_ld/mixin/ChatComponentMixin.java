package net.ledok.eden_ld.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.ledok.eden_ld.client.chat.ChatActivity;
import net.ledok.eden_ld.client.chat.ChatFilter;
import net.minecraft.client.GuiMessage;
import net.minecraft.client.GuiMessageTag;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Lines of kinds the player hid (Settings → Chat) stay in the chat's history but aren't shown;
 * {@link ChatFilter#refresh} lays the window out again when the rules change. A line mentioning the player gets
 * {@link ChatActivity}'s tag (the stripe in the notification color), a tint, and a longer stay on the HUD; lines the
 * open chat draws count as seen (the channel pips' dots and @ counts).
 */
@Mixin(ChatComponent.class)
public abstract class ChatComponentMixin {

    @Inject(method = "addMessageToDisplayQueue", at = @At("HEAD"), cancellable = true)
    private void eden_ld$hideFiltered(GuiMessage message, CallbackInfo ci) {
        if (ChatFilter.hidden(message)) {
            ci.cancel();
        }
    }

    @ModifyVariable(method = "addMessage(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/MessageSignature;Lnet/minecraft/client/GuiMessageTag;)V",
            at = @At("HEAD"), argsOnly = true)
    private GuiMessageTag eden_ld$mentionTag(GuiMessageTag tag, @Local(argsOnly = true) Component content) {
        GuiMessageTag mention = ChatActivity.mentionTag(content);
        return mention != null ? mention : tag;
    }

    @Inject(method = "addMessageToQueue", at = @At("HEAD"))
    private void eden_ld$countUnseen(GuiMessage message, CallbackInfo ci) {
        ChatActivity.added(message);
    }

    @Inject(method = "clearMessages", at = @At("HEAD"))
    private void eden_ld$forgetUnseen(boolean clearSentMsgHistory, CallbackInfo ci) {
        ChatActivity.reset();
    }

    @WrapOperation(method = "addMessageToDisplayQueue",
            at = @At(value = "INVOKE", target = "Ljava/util/List;add(ILjava/lang/Object;)V"))
    private void eden_ld$rememberLine(List<Object> lines, int index, Object line, Operation<Void> original,
                                      @Local(argsOnly = true) GuiMessage message) {
        original.call(lines, index, line);
        if (line instanceof GuiMessage.Line made) {
            ChatActivity.line(made, message);
        }
    }

    /** A mention stays on the HUD longer: its lines count as younger while drawn. */
    @WrapOperation(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/GuiMessage$Line;addedTime()I"))
    private int eden_ld$mentionLingers(GuiMessage.Line line, Operation<Integer> original) {
        int added = original.call(line);
        return ChatActivity.isMention(line.tag()) ? added + ChatActivity.MENTION_EXTRA_TICKS : added;
    }

    /** Each drawn line's background: seen (in the open chat), and tinted when it mentions the player. */
    @WrapOperation(method = "render", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/GuiGraphics;fill(IIIII)V", ordinal = 0))
    private void eden_ld$mentionTint(GuiGraphics graphics, int x0, int y0, int x1, int y1, int color,
                                     Operation<Void> original, @Local GuiMessage.Line line,
                                     @Local(argsOnly = true, ordinal = 0) int tickCount,
                                     @Local(argsOnly = true) boolean focused) {
        original.call(graphics, x0, y0, x1, y1, color);
        if (focused) {
            ChatActivity.drawn(line);
        }
        if (ChatActivity.isMention(line.tag())) {
            double fade = focused ? 1.0 : timeFactor(tickCount - line.addedTime() - ChatActivity.MENTION_EXTRA_TICKS);
            int alpha = (int) (0x50 * fade);
            if (alpha > 3) {
                graphics.fill(x0, y0, x1, y1, alpha << 24 | line.tag().indicatorColor() & 0xFFFFFF);
            }
        }
    }

    /** Vanilla's fade-out of HUD lines (ChatComponent.getTimeFactor). */
    @org.spongepowered.asm.mixin.Unique
    private static double timeFactor(int age) {
        double factor = Math.min(1.0, Math.max(0.0, (1.0 - age / 200.0) * 10.0));
        return factor * factor;
    }
}
