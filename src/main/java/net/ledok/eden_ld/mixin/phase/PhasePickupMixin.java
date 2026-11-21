package net.ledok.eden_ld.mixin.phase;

import net.ledok.eden_ld.tutorial.phase.Phases;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.AbstractArrow;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Review finding 96: items, XP orbs and arrows are picked up only by a player who sees them (the same phase). Vanilla
 * pickup checks only the delay and the owner, so a tutorial player walking over another phase's invisible drops took
 * them. {@code Phases.sees} returns at once where nobody is phased (every dimension but the tutorial's).
 */
@Mixin({ItemEntity.class, ExperienceOrb.class, AbstractArrow.class})
public abstract class PhasePickupMixin {

    @Inject(method = "playerTouch", at = @At("HEAD"), cancellable = true)
    private void eden_ld$pickUpInPhaseOnly(Player player, CallbackInfo ci) {
        if (!player.level().isClientSide() && !Phases.sees(player, (Entity) (Object) this)) {
            ci.cancel();
        }
    }
}
