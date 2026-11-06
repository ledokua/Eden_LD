package net.ledok.eden_ld.mixin;

import net.ledok.eden_ld.compat.SpellUnbinding;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.spell_engine.spellbinding.SpellBinding;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Spell Engine's binding table also offers the book's own spells, so they can be unbound without a spare scroll. */
@Pseudo
@Mixin(targets = "net.spell_engine.spellbinding.SpellBinding", remap = false)
public abstract class SpellBindingOffersMixin {

    @Inject(method = "offersFor", at = @At("RETURN"), cancellable = true, remap = false, require = 0)
    private static void eden_ld$offerBoundSpells(Level level, boolean creative, ItemStack book, ItemStack consumable,
                                                 int libraryPower, CallbackInfoReturnable<SpellBinding.OfferResult> cir) {
        cir.setReturnValue(SpellUnbinding.withBoundSpells(level, book, cir.getReturnValue()));
    }
}
