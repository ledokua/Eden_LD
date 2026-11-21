package net.ledok.eden_ld.mixin;

import net.ledok.eden_ld.combat.PvpIgnore;
import net.ledok.eden_ld.world.Hazards;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * What the hazards' PvP rule ({@link Hazards}) needs from vanilla, which doesn't remember who lit a fire:
 * a player set alight while another player was hitting them burns as that player's doing until the fire
 * goes out; and lightning remembers who called it (a Channeling trident) while it hits, and leaves alone whoever that
 * player can't hurt.
 */
@Mixin(Entity.class)
public abstract class EntityHazardMixin {

    @Shadow
    public abstract int getRemainingFireTicks();

    @Inject(method = "setRemainingFireTicks", at = @At("HEAD"))
    private void eden_ld$fireOrigin(int ticks, CallbackInfo ci) {
        if ((Object) this instanceof Player player && !player.level().isClientSide()
                && player instanceof Hazards.HazardState state) {
            if (ticks <= 0) {
                state.eden_ld$setPvpFire(false);
            } else if (ticks > getRemainingFireTicks()) {
                // Lit (or lit again): whose doing depends on who's hitting them now.
                state.eden_ld$setPvpFire(Hazards.recentlyHitByPlayer(player));
            }
        }
    }

    /**
     * Lightning a player called (a Channeling trident) skips the sides that player can't hurt: friends and ignored pairs
     * and their pets take no strike or burn from it (review finding 58: the bolt's damage names no attacker,
     * so the damage gate alone let it through).
     */
    @Inject(method = "thunderHit", at = @At("HEAD"), cancellable = true)
    private void eden_ld$lightningCause(ServerLevel level, LightningBolt lightning, CallbackInfo ci) {
        if (!PvpIgnore.allows(lightning.getCause(), (Entity) (Object) this)) {
            ci.cancel();
            return;
        }
        Hazards.lightning(lightning.getCause());
    }

    @Inject(method = "thunderHit", at = @At("RETURN"))
    private void eden_ld$lightningDone(ServerLevel level, LightningBolt lightning, CallbackInfo ci) {
        Hazards.lightning(null);
    }
}
