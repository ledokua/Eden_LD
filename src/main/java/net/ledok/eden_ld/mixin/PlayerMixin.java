package net.ledok.eden_ld.mixin;

import net.ledok.eden_ld.compat.CombatTeamsCompat;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Partymates can't harm each other in vanilla's own team sense too: tamed wolves (and
 * other pets built on {@code TamableAnimal#wantsToAttack}) consult {@code canHarmPlayer}
 * before picking a player target, so pets never turn on their owner's partymates.
 */
@Mixin(Player.class)
public abstract class PlayerMixin {

    /**
     * Server side, a player's display name is Eden's chat name — LuckPerms prefix, the name in
     * its group color, suffix — wherever the server names them (chat, deaths, joins, advancements).
     */
    @Inject(method = "getDisplayName", at = @At("RETURN"), cancellable = true)
    private void eden_ld$styledName(CallbackInfoReturnable<net.minecraft.network.chat.Component> cir) {
        if ((Object) this instanceof net.minecraft.server.level.ServerPlayer player) {
            cir.setReturnValue(net.ledok.eden_ld.chat.ChatStyle.displayName(player, cir.getReturnValue()));
        }
    }

    @Inject(method = "canHarmPlayer", at = @At("HEAD"), cancellable = true)
    private void eden_ld$sparePartymates(Player other, CallbackInfoReturnable<Boolean> cir) {
        CombatTeamsCompat.Relation relation = CombatTeamsCompat.resolve((Player) (Object) this, other);
        if (relation != null && !relation.friendlyFire()) {
            cir.setReturnValue(false);
        }
    }
}
