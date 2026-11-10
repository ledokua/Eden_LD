package net.ledok.eden_ld.mixin.compat.bundleapi;

import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * Bundle API's contents (Runes' rune pouches) never write an empty stack: its codecs (network and saves) take the list
 * through these two getters, and a count-0 stack in it (a spent rune whose stack was shared by a shallow copy) made
 * every packet carrying the pouch fail ("Empty ItemStack not allowed": the player was disconnected) and its save
 * fail too. They get a copy without the empty stacks; the component itself is left alone (encoding can run off the
 * server thread). Nothing without Bundle API.
 */
@Pseudo
@Mixin(targets = "com.github.theredbrain.bundleapi.component.type.CustomBundleContentsComponent$Content", remap = false)
abstract class BundleContentsNoEmptyMixin {

    @Inject(method = {"lambda$static$0", "lambda$static$1"}, at = @At("RETURN"), cancellable = true, remap = false,
            require = 0)
    private static void eden_ld$withoutEmpty(CallbackInfoReturnable<List<ItemStack>> cir) {
        List<ItemStack> stacks = cir.getReturnValue();
        if (stacks == null) {
            return;
        }
        for (ItemStack stack : stacks) {
            if (stack == null || stack.isEmpty()) {
                cir.setReturnValue(stacks.stream().filter(s -> s != null && !s.isEmpty()).toList());
                return;
            }
        }
    }
}
