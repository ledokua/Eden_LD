package net.ledok.eden_ld.mixin;

import com.mojang.blaze3d.platform.NativeImage;
import net.ledok.eden_ld.client.HdSkins;
import net.minecraft.client.renderer.texture.HttpTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Keeps square 128×128 and 256×256 player skins ({@link HdSkins}); vanilla discards anything but 64×64 and
 * 64×32 ("Discarding incorrectly sized skin texture"). Only skin downloads come here (capes and elytra don't
 * process as skins). Handled whole at HEAD: Entity Texture Features hooks this method further down and would
 * otherwise apply its 64-pixel alpha areas to the HD skin; it re-reads skins through here too and gets the same
 * answer (its skin features stay off for HD skins: their marker isn't where it looks).
 */
@Mixin(HttpTexture.class)
public abstract class HttpTextureHdSkinMixin {

    @Inject(method = "processLegacySkin", at = @At("HEAD"), cancellable = true)
    private void eden_ld$keepHdSkin(NativeImage image, CallbackInfoReturnable<NativeImage> cir) {
        if (!HdSkins.isHd(image.getWidth(), image.getHeight())) {
            return;
        }
        for (int[] area : HdSkins.opaqueAreas(image.getWidth())) {
            for (int x = area[0]; x < area[2]; x++) {
                for (int y = area[1]; y < area[3]; y++) {
                    image.setPixelRGBA(x, y, image.getPixelRGBA(x, y) | 0xFF000000);
                }
            }
        }
        cir.setReturnValue(image);
    }
}
