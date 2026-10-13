package net.ledok.eden_ld.tutorial;

import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.ledok.eden_ld.EdenLD;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Blocks a player in the tutorial can't use ({@code tutorial.locked_blocks}: ids or #tags; by default signs,
 * chiseled bookshelves and trapdoors): the right-click is refused, the block and their inventory are sent again (a
 * trapdoor their game already swung open closes, a book stays in hand), and a line above the hotbar says why.
 */
public final class TutorialLockedBlocks {

    private TutorialLockedBlocks() {
    }

    static void initialize() {
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            if (level.isClientSide() || !(player instanceof ServerPlayer serverPlayer) || !Tutorial.isActive(serverPlayer)) {
                return InteractionResult.PASS;
            }
            BlockPos pos = hit.getBlockPos();
            if (!locked(level.getBlockState(pos))) {
                return InteractionResult.PASS;
            }
            serverPlayer.connection.send(new ClientboundBlockUpdatePacket(level, pos));
            serverPlayer.containerMenu.sendAllDataToRemote();
            serverPlayer.displayClientMessage(Component.translatable("message.eden_ld.tutorial.locked_block"), true);
            return InteractionResult.FAIL;
        });
    }

    /** Whether the block is on the list (an id, or a #tag it's in). */
    public static boolean locked(BlockState state) {
        for (String entry : EdenLD.CONFIG.tutorial.locked_blocks) {
            if (entry == null || entry.isBlank()) {
                continue;
            }
            boolean tag = entry.startsWith("#");
            ResourceLocation id = ResourceLocation.tryParse(tag ? entry.substring(1).trim() : entry.trim());
            if (id == null) {
                continue;
            }
            if (tag ? state.is(TagKey.create(Registries.BLOCK, id)) : BuiltInRegistries.BLOCK.getKey(state.getBlock()).equals(id)) {
                return true;
            }
        }
        return false;
    }
}
