package net.ledok.eden_ld.mixin.compat.waystones;

import net.blay09.mods.waystones.api.Waystone;
import net.blay09.mods.waystones.core.WaystoneSyncManager;
import net.ledok.eden_ld.tutorial.Tutorial;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A player in the tutorial can't forget the tutorial's waystones. Otherwise someone who traveled to the second room's
 * stone and then removed the first one from their list (Waystones' menu or the hub) was trapped in the second room.
 * Every way of forgetting a stone goes through this method. Their list is sent again, so the stone shows up again in
 * the menu at once. After the tutorial they can forget them as usual. Nothing without Waystones.
 */
@Pseudo
@Mixin(targets = "net.blay09.mods.waystones.core.PlayerWaystoneManager", remap = false)
abstract class TutorialKeepWaystonesMixin {

    @Inject(method = "deactivateWaystone", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private static void eden_ld$keepTutorialStones(Player player, Waystone waystone, CallbackInfo ci) {
        if (player instanceof ServerPlayer serverPlayer && Tutorial.isActive(serverPlayer)
                && waystone.getDimension() != null && Tutorial.isTutorialDimension(waystone.getDimension())) {
            ci.cancel();
            WaystoneSyncManager.sendActivatedWaystones(serverPlayer);
            serverPlayer.displayClientMessage(Component.translatable("message.eden_ld.tutorial.keep_waystone"), true);
        }
    }
}
