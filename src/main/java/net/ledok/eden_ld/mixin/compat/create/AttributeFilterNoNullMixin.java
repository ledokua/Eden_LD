package net.ledok.eden_ld.mixin.compat.create;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Create's attribute filter never takes an attribute the server couldn't read. The screen sends the chosen attribute
 * as NBT and the server decodes it with {@code ItemAttribute.loadStatic}, which gives null when that fails, e.g.
 * "is in &lt;group&gt;" for a creative tab that exists only on the client (Mine Cells' owo-lib tab is registered by
 * its client entrypoint). The null went into the filter's attribute list, and every packet carrying the filter then
 * failed to encode (NullPointerException in container_set_slot): the player was disconnected. Now the attribute is
 * just not added. Nothing without Create.
 */
@Pseudo
@Mixin(targets = "com.simibubi.create.content.logistics.filter.AttributeFilterMenu", remap = false)
abstract class AttributeFilterNoNullMixin {

    @Inject(method = "appendSelectedAttribute", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void eden_ld$skipUnreadable(@Coerce Object attribute, boolean inverted, CallbackInfo ci) {
        if (attribute == null) {
            ci.cancel();
        }
    }
}
