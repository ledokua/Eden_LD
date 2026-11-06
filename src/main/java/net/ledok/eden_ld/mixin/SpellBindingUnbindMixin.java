package net.ledok.eden_ld.mixin;

import net.ledok.eden_ld.compat.SpellUnbinding;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Unbinding at Spell Engine's binding table gives the spell's scroll back ({@link SpellUnbinding}). Server side only. */
@Pseudo
@Mixin(targets = "net.spell_engine.spellbinding.SpellBindingScreenHandler")
public abstract class SpellBindingUnbindMixin {

    @Shadow(remap = false)
    @Final
    public int[] mode;

    @Shadow(remap = false)
    @Final
    public int[] spellId;

    @Shadow(remap = false)
    @Final
    private Container inventory;

    @Shadow(remap = false)
    @Final
    private ContainerLevelAccess context;

    @Inject(method = "clickMenuButton", at = @At("HEAD"), cancellable = true, require = 0)
    private void eden_ld$unbindWithScroll(Player player, int id, CallbackInfoReturnable<Boolean> cir) {
        if (player instanceof ServerPlayer serverPlayer && id >= 0 && id < spellId.length
                && SpellUnbinding.unbind((AbstractContainerMenu) (Object) this, serverPlayer, mode[0], spellId[id], inventory, context)) {
            cir.setReturnValue(true);
        }
    }
}
