package net.ledok.eden_ld.mixin;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.llamalad7.mixinextras.sugar.Local;
import net.ledok.eden_ld.hub.IgnoreList;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.OutgoingChatMessage;
import net.minecraft.network.chat.PlayerChatMessage;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

import java.util.function.Predicate;

/**
 * Player chat vanilla broadcasts itself — {@code /me}, and {@code /say} from a player (Eden sends typed
 * chat its own way) — skips players who ignore the sender ({@link IgnoreList}).
 */
@Mixin(PlayerList.class)
public abstract class PlayerListIgnoreMixin {

    @Shadow
    @Final
    private MinecraftServer server;

    @WrapWithCondition(method = "broadcastChatMessage(Lnet/minecraft/network/chat/PlayerChatMessage;Ljava/util/function/Predicate;Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/network/chat/ChatType$Bound;)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ServerPlayer;sendChatMessage(Lnet/minecraft/network/chat/OutgoingChatMessage;ZLnet/minecraft/network/chat/ChatType$Bound;)V"))
    private boolean eden_ld$notIgnored(ServerPlayer recipient, OutgoingChatMessage message, boolean filtered,
                                       ChatType.Bound bound, @Local(argsOnly = true) @Nullable ServerPlayer sender) {
        return sender == null || !IgnoreList.ignores(server, recipient.getUUID(), sender.getUUID());
    }
}
