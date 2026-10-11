package net.ledok.eden_ld.mixin.phase;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.ledok.eden_ld.tutorial.phase.PhaseContext;
import net.ledok.eden_ld.tutorial.phase.Phased;
import net.ledok.eden_ld.tutorial.phase.Phases;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.TraceableEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.UUID;

/**
 * New entities take the phase of what made them: the acting phase, else their owner (pets), their shooter
 * (projectiles), their vehicle or a passenger. Phased entities tick in their phase; one whose player is gone or out
 * of the tutorial is removed.
 */
@Mixin(ServerLevel.class)
public abstract class PhaseServerLevelMixin {

    @Inject(method = "addFreshEntity", at = @At("HEAD"))
    private void eden_ld$inheritPhase(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        if (!(entity instanceof Phased phased) || phased.eden_ld$phase() != null) {
            return;
        }
        UUID phase = PhaseContext.current();
        if (phase == null && entity instanceof OwnableEntity ownable) {
            phase = Phases.of(ownable.getOwner());
        }
        if (phase == null && entity instanceof TraceableEntity traceable) {
            phase = Phases.of(traceable.getOwner());
        }
        if (phase == null) {
            phase = Phases.of(entity.getVehicle());
        }
        if (phase == null) {
            for (Entity passenger : entity.getPassengers()) {
                phase = Phases.of(passenger);
                if (phase != null) {
                    break;
                }
            }
        }
        if (phase != null) {
            phased.eden_ld$setPhase(phase);
        }
    }

    @WrapMethod(method = "tickNonPassenger")
    private void eden_ld$tickInPhase(Entity entity, Operation<Void> original) {
        UUID phase = Phases.of(entity);
        if (phase == null) {
            original.call(entity);
            return;
        }
        if (!(entity instanceof Player) && entity.getServer() != null && !Phases.hasOwner(entity.getServer(), phase)) {
            entity.discard();
            return;
        }
        PhaseContext.run(phase, () -> original.call(entity));
    }

    @WrapMethod(method = "tickPassenger")
    private void eden_ld$tickPassengerInPhase(Entity vehicle, Entity passenger, Operation<Void> original) {
        PhaseContext.run(Phases.of(passenger), () -> original.call(vehicle, passenger));
    }
}
