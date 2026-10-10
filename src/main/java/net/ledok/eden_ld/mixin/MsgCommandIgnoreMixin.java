package net.ledok.eden_ld.mixin;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.llamalad7.mixinextras.sugar.Local;
import net.ledok.eden_ld.hub.IgnoreList;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.OutgoingChatMessage;
import net.minecraft.server.commands.MsgCommand;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Whispers ({@code /msg}, {@code /tell}, {@code /w}) from a player the target ignores ({@link IgnoreList})
 * never reach them; the sender still sees theirs as sent, so the ignore isn't given away.
 */
@Mixin(MsgCommand.class)
public abstract class MsgCommandIgnoreMixin {

    @WrapWithCondition(method = "sendMessage", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerPlayer;sendChatMessage(Lnet/minecraft/network/chat/OutgoingChatMessage;ZLnet/minecraft/network/chat/ChatType$Bound;)V"))
    private static boolean eden_ld$notIgnored(ServerPlayer target, OutgoingChatMessage message, boolean filtered,
                                              ChatType.Bound bound, @Local(argsOnly = true) CommandSourceStack source) {
        ServerPlayer sender = source.getPlayer();
        return sender == null || !IgnoreList.ignores(source.getServer(), target.getUUID(), sender.getUUID());
    }
}
