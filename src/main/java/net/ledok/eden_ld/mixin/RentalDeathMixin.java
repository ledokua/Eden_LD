package net.ledok.eden_ld.mixin;

import net.ledok.eden_ld.rental.RentalStash;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A renter's death drops nothing: before the death loot (and before graves look at the inventory), the inventory and
 * Trinkets are emptied: rental items gone, the rest kept for the session's end.
 */
@Mixin(ServerPlayer.class)
abstract class RentalDeathMixin {

    @Inject(method = "die", at = @At("HEAD"))
    private void eden_ld$emptyRenter(DamageSource source, CallbackInfo ci) {
        RentalStash.onDeath((ServerPlayer) (Object) this);
    }
}
