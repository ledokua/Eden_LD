package net.ledok.eden_ld.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import net.ledok.eden_ld.client.fix.CrumblingOverlay;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.server.level.BlockDestructionProgress;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

import java.util.SortedSet;

/**
 * Block entities being mined crashed the client with "Not building!" (an Extended Drawers drawer with an item, with
 * Sodium, ImmediatelyFast and Better Block Entities); see {@link CrumblingOverlay}. Nothing happens without Sodium
 * ({@code @Pseudo}), and a Sodium version without this call just isn't patched ({@code require = 0}).
 */
@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer")
public abstract class SodiumCrumblingOverlayMixin {

    @WrapOperation(method = "renderBlockEntity", require = 0, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/blockentity/BlockEntityRenderDispatcher;render(Lnet/minecraft/world/level/block/entity/BlockEntity;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;)V"))
    private static void eden_ld$freshCrumblingOverlay(BlockEntityRenderDispatcher dispatcher, BlockEntity blockEntity, float partialTick,
                                                      PoseStack poseStack, MultiBufferSource source, Operation<Void> original,
                                                      @Local(argsOnly = true) RenderBuffers buffers,
                                                      @Local(argsOnly = true) Long2ObjectMap<SortedSet<BlockDestructionProgress>> progress,
                                                      @Local(argsOnly = true) MultiBufferSource.BufferSource immediate) {
        original.call(dispatcher, blockEntity, partialTick, poseStack,
                CrumblingOverlay.wrap(source, immediate, buffers, progress, blockEntity, poseStack));
    }
}
