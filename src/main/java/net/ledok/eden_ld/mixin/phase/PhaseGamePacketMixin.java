package net.ledok.eden_ld.mixin.phase;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.ledok.eden_ld.tutorial.phase.PhaseContext;
import net.ledok.eden_ld.tutorial.phase.PrivateBlocks;
import net.minecraft.network.protocol.game.ServerboundContainerButtonClickPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.network.protocol.game.ServerboundPlaceRecipePacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Clicks, buttons and recipe fills in a private block's menu work on the player's copy; closing forgets it. */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class PhaseGamePacketMixin {

    @Shadow
    public ServerPlayer player;

    @WrapMethod(method = "handleContainerClick")
    private void eden_ld$clickInScope(ServerboundContainerClickPacket packet, Operation<Void> original) {
        eden_ld$inMenuScope(() -> original.call(packet));
    }

    @WrapMethod(method = "handleContainerButtonClick")
    private void eden_ld$buttonInScope(ServerboundContainerButtonClickPacket packet, Operation<Void> original) {
        eden_ld$inMenuScope(() -> original.call(packet));
    }

    @WrapMethod(method = "handlePlaceRecipe")
    private void eden_ld$recipeInScope(ServerboundPlaceRecipePacket packet, Operation<Void> original) {
        eden_ld$inMenuScope(() -> original.call(packet));
    }

    @WrapMethod(method = "handleContainerClose")
    private void eden_ld$closeInScope(ServerboundContainerClosePacket packet, Operation<Void> original) {
        eden_ld$inMenuScope(() -> original.call(packet));
        PrivateBlocks.menuClosed(player);
    }

    private void eden_ld$inMenuScope(Runnable body) {
        boolean scoped = PrivateBlocks.pushMenu(player);
        try {
            PhaseContext.run(scoped ? PrivateBlocks.menuPhase(player) : null, body);
        } finally {
            if (scoped) {
                PrivateBlocks.popScope();
            }
        }
    }
}
