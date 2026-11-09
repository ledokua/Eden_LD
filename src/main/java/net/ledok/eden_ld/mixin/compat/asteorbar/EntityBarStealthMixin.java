package net.ledok.eden_ld.mixin.compat.asteorbar;

import com.mojang.blaze3d.vertex.PoseStack;
import net.ledok.eden_ld.combat.Stealth;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** AsteorBar draws no health bar over someone in stealth ({@link Stealth}) who is invisible to this player. */
@Pseudo
@Mixin(targets = "com.afoxxvi.asteorbar.entity.EntityRenderer", remap = false)
public abstract class EntityBarStealthMixin {

    @Inject(method = "render", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private static void eden_ld$hideBar(LivingEntity entity, PoseStack pose, MultiBufferSource buffers, CallbackInfo ci) {
        eden_ld$cancelIfHidden(entity, ci);
    }

    @Inject(method = "extraRender", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private static void eden_ld$hideExtra(LivingEntity entity, PoseStack pose, MultiBufferSource buffers, float a, float b,
                                          float c, CallbackInfo ci) {
        eden_ld$cancelIfHidden(entity, ci);
    }

    @Inject(method = "extraTextRender", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private static void eden_ld$hideText(LivingEntity entity, PoseStack pose, MultiBufferSource buffers, float a, float b,
                                         float c, float d, CallbackInfo ci) {
        eden_ld$cancelIfHidden(entity, ci);
    }

    @Unique
    private static void eden_ld$cancelIfHidden(LivingEntity entity, CallbackInfo ci) {
        if (Stealth.hiddenFrom(entity, Minecraft.getInstance().player)) {
            ci.cancel();
        }
    }
}
