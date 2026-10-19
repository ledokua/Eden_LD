package net.ledok.eden_ld.mixin.phase;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.ledok.eden_ld.tutorial.phase.PhaseContext;
import net.ledok.eden_ld.tutorial.phase.Phases;
import net.ledok.eden_ld.tutorial.phase.PrivateBlocks;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.OptionalInt;

/**
 * A private block's menu: remembered when it opens; the player's ticks (menu updates) run in its scope. Items the
 * player drops land in their phase.
 */
@Mixin(ServerPlayer.class)
public abstract class PhaseServerPlayerMixin {

    @Inject(method = "openMenu", at = @At("RETURN"))
    private void eden_ld$rememberPrivateMenu(MenuProvider provider, CallbackInfoReturnable<OptionalInt> cir) {
        if (PrivateBlocks.scopeOpen()) {
            PrivateBlocks.menuOpened((ServerPlayer) (Object) this);
        }
    }

    @WrapMethod(method = "tick")
    private void eden_ld$tickInMenuScope(Operation<Void> original) {
        eden_ld$inMenuScope(() -> original.call());
    }

    @WrapMethod(method = "doTick")
    private void eden_ld$doTickInMenuScope(Operation<Void> original) {
        eden_ld$inMenuScope(() -> original.call());
    }

    /**
     * {@code ServerPlayer#drop} adds the item to the level only after {@code Player#drop} (where {@code PhasePlayerMixin}
     * puts it in the player's phase) has returned, so the phase has to hold around this one too (an item left in a
     * closed crafting grid, a full inventory).
     */
    @WrapMethod(method = "drop(Lnet/minecraft/world/item/ItemStack;ZZ)Lnet/minecraft/world/entity/item/ItemEntity;")
    private ItemEntity eden_ld$dropInPhase(ItemStack stack, boolean throwRandomly, boolean retainOwnership,
                                           Operation<ItemEntity> original) {
        return PhaseContext.call(Phases.of((ServerPlayer) (Object) this),
                () -> original.call(stack, throwRandomly, retainOwnership));
    }

    private void eden_ld$inMenuScope(Runnable body) {
        ServerPlayer self = (ServerPlayer) (Object) this;
        boolean scoped = PrivateBlocks.pushMenu(self);
        try {
            PhaseContext.run(scoped ? PrivateBlocks.menuPhase(self) : null, body);
        } finally {
            if (scoped) {
                PrivateBlocks.popScope();
            }
        }
    }
}
