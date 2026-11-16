package net.ledok.eden_ld.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.pockyl.diegesis.impl.network.Packets;
import net.ledok.eden_ld.hub.daily.QuestConditions;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

/**
 * Hands-On Delight's stations (Diegesis): the cooking pot, the keg, the cutting board work in the world with no menu
 * open, so what a player made there (a meal scooped, a drink drawn, a cut) counted for craft quests at the crafting
 * table (QuestConditions reads an inventory-only player as there). Every station action now counts at its block, the
 * same id the station's own menu has: farmersdelight:cooking_pot, brewinandchewin:keg, farmersdelight:cutting_board.
 * Nothing happens without Diegesis ({@code @Pseudo}).
 */
@Pseudo
@Mixin(targets = "com.pockyl.diegesis.impl.server.StationManager", remap = false)
public abstract class StationActionMixin {

    @WrapMethod(method = "handleAction", require = 0)
    private void eden_ld$atTheStation(ServerPlayer player, Packets.Action packet, Operation<Void> original) {
        // The position comes from the client: never load a chunk for it (Diegesis drops an action away from the station anyway).
        if (!player.serverLevel().isLoaded(packet.pos())) {
            original.call(player, packet);
            return;
        }
        ResourceLocation block = BuiltInRegistries.BLOCK.getKey(player.serverLevel().getBlockState(packet.pos()).getBlock());
        QuestConditions.madeAt(block, () -> original.call(player, packet));
    }
}
