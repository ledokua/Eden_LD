package net.ledok.eden_ld.mixin;

import net.ledok.eden_ld.compat.CombatTeamsCompat;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Friends and players who ignore each other (docs/PLAN-pvp-toggle.md: partymates, guildmates, run teams, anyone with
 * PvP off) can't harm each other in vanilla's own team sense too: tamed wolves (and other pets built on
 * {@code TamableAnimal#wantsToAttack}) consult {@code canHarmPlayer} before picking a player target, so pets never turn
 * on them. A player hitting someone they ignore hears why in the action bar.
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

    /**
     * A melee attack (vanilla's, and Better Combat's, which attacks through here too) on someone the player ignores:
     * "Bob has PvP off" in the action bar, at most every 2 seconds. The damage checks refuse the hit itself.
     */
    @Inject(method = "attack", at = @At("HEAD"))
    private void eden_ld$ignoredHit(net.minecraft.world.entity.Entity target, CallbackInfo ci) {
        if ((Object) this instanceof net.minecraft.server.level.ServerPlayer player) {
            net.ledok.eden_ld.combat.PlayerPvp.attacking(player, target);
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
