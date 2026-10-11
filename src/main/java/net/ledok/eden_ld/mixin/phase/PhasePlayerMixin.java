package net.ledok.eden_ld.mixin.phase;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.ledok.eden_ld.tutorial.phase.PhaseContext;
import net.ledok.eden_ld.tutorial.phase.Phases;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;

import java.util.UUID;

/**
 * A phased player attacks in their phase. Items they throw stay in their inventory (nothing to pass on or stash in
 * the tutorial); other drops (a full inventory) land in their phase.
 */
@Mixin(Player.class)
public abstract class PhasePlayerMixin {

    @WrapMethod(method = "attack")
    private void eden_ld$attackInPhase(Entity target, Operation<Void> original) {
        PhaseContext.run(Phases.of((Player) (Object) this), () -> original.call(target));
    }

    @WrapMethod(method = "drop(Lnet/minecraft/world/item/ItemStack;ZZ)Lnet/minecraft/world/entity/item/ItemEntity;")
    private ItemEntity eden_ld$dropInPhase(ItemStack stack, boolean throwRandomly, boolean retainOwnership,
                                           Operation<ItemEntity> original) {
        Player self = (Player) (Object) this;
        UUID phase = Phases.of(self);
        if (phase != null && retainOwnership && !stack.isEmpty()) {
            // Thrown by the player (Q, dragging out of the inventory): back it goes.
            self.getInventory().add(stack);
            if (stack.isEmpty()) {
                return null;
            }
        }
        return PhaseContext.call(phase, () -> original.call(stack, throwRandomly, retainOwnership));
    }
}
