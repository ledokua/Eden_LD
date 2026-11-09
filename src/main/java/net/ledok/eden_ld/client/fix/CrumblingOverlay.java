package net.ledok.eden_ld.client.fix;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.SheetedDecalTextureGenerator;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexMultiConsumer;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import net.ledok.eden_ld.EdenLD;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.client.resources.model.ModelBakery;
import net.minecraft.server.level.BlockDestructionProgress;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.SortedSet;

/**
 * Crumbling overlay for a block entity being mined ({@code client_fixes.json} {@code fresh_crumbling_overlay}).
 * <p>
 * Sodium (like vanilla) takes one buffer from the shared crumbling buffer source when it starts drawing such a block
 * entity and sends every crumbling-affected layer to it. That buffer source ends its current buffer whenever it is asked
 * for another kind, so when anything does that while the block entity is still drawing (in the pack: drawing an
 * Extended Drawers drawer's item, with ImmediatelyFast and Better Block Entities), the next layer writes into an ended
 * buffer: "IllegalStateException: Not building!" and the client crashes. Here every layer gets the crumbling buffer
 * that is current at that moment, as vanilla's crumbling source hands out, with the block entity's position from the
 * start of its render.
 */
public final class CrumblingOverlay {

    private CrumblingOverlay() {
    }

    public static MultiBufferSource wrap(MultiBufferSource source, MultiBufferSource.BufferSource immediate, RenderBuffers buffers,
                                         Long2ObjectMap<SortedSet<BlockDestructionProgress>> progress, BlockEntity blockEntity,
                                         PoseStack poseStack) {
        // Sodium passes the plain buffer source unless the block entity is being mined.
        if (source == immediate || !EdenLD.CONFIG.client_fixes.fresh_crumbling_overlay) {
            return source;
        }
        SortedSet<BlockDestructionProgress> stages = progress.get(blockEntity.getBlockPos().asLong());
        if (stages == null || stages.isEmpty()) {
            return source;
        }
        int stage = stages.last().getProgress();
        if (stage < 0 || stage >= ModelBakery.DESTROY_TYPES.size()) {
            return source;
        }
        PoseStack.Pose pose = poseStack.last().copy();
        return layer -> {
            VertexConsumer main = immediate.getBuffer(layer);
            if (!layer.affectsCrumbling()) {
                return main;
            }
            VertexConsumer crumbling = buffers.crumblingBufferSource().getBuffer(ModelBakery.DESTROY_TYPES.get(stage));
            return VertexMultiConsumer.create(new SheetedDecalTextureGenerator(crumbling, pose, 1.0F), main);
        };
    }
}
