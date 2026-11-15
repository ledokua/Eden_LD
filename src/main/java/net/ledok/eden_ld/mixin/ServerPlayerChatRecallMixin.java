package net.ledok.eden_ld.mixin;

import net.ledok.eden_ld.hub.moderation.ChatRecall;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.OutgoingChatMessage;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Every chat line a player receives goes into {@link ChatRecall} (evidence for complaints they file). */
@Mixin(ServerPlayer.class)
abstract class ServerPlayerChatRecallMixin {

    @Inject(method = "sendSystemMessage(Lnet/minecraft/network/chat/Component;Z)V", at = @At("HEAD"))
    private void eden_ld$recallSystem(Component message, boolean overlay, CallbackInfo ci) {
        if (!overlay) {
            ChatRecall.system(((ServerPlayer) (Object) this).getUUID(), message);
        }
    }

    @Inject(method = "sendChatMessage", at = @At("HEAD"))
    private void eden_ld$recallChat(OutgoingChatMessage message, boolean filtered, ChatType.Bound bound, CallbackInfo ci) {
        ChatRecall.chat(((ServerPlayer) (Object) this).getUUID(), message, bound);
    }
}
