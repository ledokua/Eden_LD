package net.ledok.eden_ld.mixin.phase;

import net.ledok.eden_ld.tutorial.phase.PhaseContext;
import net.ledok.eden_ld.tutorial.phase.Phases;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.Hopper;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** A hopper only picks up items of the phase it acts in (a player's private copy, or the shared world). */
@Mixin(HopperBlockEntity.class)
public abstract class PhaseHopperMixin {

    @Inject(method = "getItemsAtAndAbove", at = @At("RETURN"), cancellable = true)
    private static void eden_ld$samePhaseItems(Level level, Hopper hopper, CallbackInfoReturnable<List<ItemEntity>> cir) {
        List<ItemEntity> items = cir.getReturnValue();
        if (level.isClientSide() || items == null || items.isEmpty()) {
            return;
        }
        UUID phase = PhaseContext.current();
        if (items.stream().anyMatch(item -> !Objects.equals(Phases.of(item), phase))) {
            cir.setReturnValue(items.stream().filter(item -> Objects.equals(Phases.of(item), phase)).toList());
        }
    }
}
