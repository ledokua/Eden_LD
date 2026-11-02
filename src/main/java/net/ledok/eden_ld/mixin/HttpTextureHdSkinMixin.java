package net.ledok.eden_ld.mixin;

import com.mojang.blaze3d.platform.NativeImage;
import net.ledok.eden_ld.client.HdSkins;
import net.minecraft.client.renderer.texture.HttpTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Keeps HD player skins ({@link HdSkins}): square 128…1024 and legacy 2:1 128×64…1024×512, shrunk to 256 wide, legacy
 * ones made square like vanilla's 64×32, then vanilla's transparency fixes scaled. Vanilla discards anything but 64×64
 * and 64×32 ("Discarding incorrectly sized skin texture"), and keeps handling those two. Only skin downloads come here
 * (capes and elytra don't process as skins). Handled whole at HEAD: Entity Texture Features hooks this method further
 * down and would otherwise apply its 64-pixel alpha areas to the HD skin; it re-reads skins through here too and gets
 * the same answer (its skin features stay off for HD skins: their marker isn't where it looks).
 */
@Mixin(HttpTexture.class)
public abstract class HttpTextureHdSkinMixin {

    @Inject(method = "processLegacySkin", at = @At("HEAD"), cancellable = true)
    private void eden_ld$keepHdSkin(NativeImage image, CallbackInfoReturnable<NativeImage> cir) {
        HdSkins.Plan plan = HdSkins.plan(image.getWidth(), image.getHeight());
        if (plan == null) {
            return;
        }
        NativeImage skin = image;
        if (skin.getWidth() != plan.width()) {
            // Shrink first (a filtered resize, not nearest-neighbour), so the fixes below work on the final size.
            NativeImage small = new NativeImage(plan.width(), plan.height(), true);
            skin.resizeSubRectTo(0, 0, skin.getWidth(), skin.getHeight(), small);
            skin.close();
            skin = small;
        }
        int size = plan.size();
        if (plan.legacy()) {
            // As vanilla's 64×32: a square image, the lower half cleared, the right arm and leg mirrored into the left.
            NativeImage square = new NativeImage(size, size, true);
            square.copyFrom(skin);
            skin.close();
            skin = square;
            int half = size / 2;
            skin.fillRect(0, half, size, half, 0);
            for (int[] copy : HdSkins.legacyCopies(size)) {
                skin.copyRect(copy[0], copy[1], copy[2], copy[3], copy[4], copy[5], true, false);
            }
        }
        int[][] opaque = HdSkins.opaqueAreas(size);
        eden_ld$opaque(skin, opaque[0]);
        if (plan.legacy()) {
            eden_ld$hatHack(skin, HdSkins.hatArea(size));
        }
        eden_ld$opaque(skin, opaque[1]);
        eden_ld$opaque(skin, opaque[2]);
        cir.setReturnValue(skin);
    }

    /** Vanilla's setNoAlpha: the area made fully opaque. */
    private static void eden_ld$opaque(NativeImage skin, int[] area) {
        for (int x = area[0]; x < area[2]; x++) {
            for (int y = area[1]; y < area[3]; y++) {
                skin.setPixelRGBA(x, y, skin.getPixelRGBA(x, y) | 0xFF000000);
            }
        }
    }

    /** Vanilla's doNotchTransparencyHack: a hat area without any see-through pixel is made fully transparent. */
    private static void eden_ld$hatHack(NativeImage skin, int[] area) {
        for (int x = area[0]; x < area[2]; x++) {
            for (int y = area[1]; y < area[3]; y++) {
                if ((skin.getPixelRGBA(x, y) >>> 24) < 128) {
                    return;
                }
            }
        }
        for (int x = area[0]; x < area[2]; x++) {
            for (int y = area[1]; y < area[3]; y++) {
                skin.setPixelRGBA(x, y, skin.getPixelRGBA(x, y) & 0x00FFFFFF);
            }
        }
    }
}
