package net.ledok.eden_ld.mixin;

import net.ledok.eden_ld.client.chat.ClientTabList;
import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.client.multiplayer.PlayerInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import java.util.Comparator;

/** Tab list sorted by rank first (1.21.1 has no server-side order); see {@link ClientTabList}. */
@Mixin(PlayerTabOverlay.class)
public abstract class PlayerTabOverlayMixin {

    @ModifyArg(method = "getPlayerInfos", at = @At(value = "INVOKE",
            target = "Ljava/util/stream/Stream;sorted(Ljava/util/Comparator;)Ljava/util/stream/Stream;"))
    private Comparator<PlayerInfo> eden_ld$byRank(Comparator<PlayerInfo> vanilla) {
        return ClientTabList.comparator(vanilla);
    }
}
