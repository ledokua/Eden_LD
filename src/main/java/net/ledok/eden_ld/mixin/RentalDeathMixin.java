package net.ledok.eden_ld.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.ledok.eden_ld.rental.RentalStash;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * A renter's death drops nothing and makes no grave: their items are put aside first (rental items gone, the rest kept
 * for the session's end), the death loot step (where Graves_LD makes its grave and adds the player's head, and where
 * experience drops) is skipped, and anything else spawned on the ground while they die is removed.
 */
@Mixin(ServerPlayer.class)
abstract class RentalDeathMixin {

    @WrapMethod(method = "die")
    private void eden_ld$renterDies(DamageSource source, Operation<Void> original) {
        ServerPlayer self = (ServerPlayer) (Object) this;
        if (!RentalStash.isRenting(self)) {
            original.call(source);
            return;
        }
        RentalStash.onDeath(self);
        RentalStash.dying(true);
        try {
            original.call(source);
        } finally {
            RentalStash.dying(false);
        }
    }

    @WrapOperation(method = "die", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerPlayer;dropAllDeathLoot(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/damagesource/DamageSource;)V"))
    private void eden_ld$noDeathLootForRenters(ServerPlayer player, ServerLevel level, DamageSource source,
                                              Operation<Void> original) {
        if (!RentalStash.isRenting(player)) {
            original.call(player, level, source);
        }
    }
}
