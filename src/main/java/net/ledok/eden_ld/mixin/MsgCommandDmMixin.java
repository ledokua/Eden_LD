package net.ledok.eden_ld.mixin;

import net.ledok.eden_ld.chat.dm.DirectMessages;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.PlayerChatMessage;
import net.minecraft.server.commands.MsgCommand;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Collection;

/**
 * {@code /msg}, {@code /tell} and {@code /w} from a player with Eden go to their DM with the target (1.3.94,
 * {@link DirectMessages}) — kept, shown in its chat tab — instead of a whisper. The console, clients without Eden and
 * several targets keep vanilla's whisper (and {@code MsgCommandIgnoreMixin}). No Fabric event covers this command.
 */
@Mixin(MsgCommand.class)
public abstract class MsgCommandDmMixin {

    @Inject(method = "sendMessage", at = @At("HEAD"), cancellable = true)
    private static void eden_ld$toConversation(CommandSourceStack source, Collection<ServerPlayer> targets,
                                               PlayerChatMessage message, CallbackInfo ci) {
        if (DirectMessages.routeTell(source, targets, message)) {
            ci.cancel();
        }
    }
}
