package net.ledok.eden_ld.mixin;

import net.ledok.eden_ld.world.PlayerSaves;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Marks a save of all players, so {@link PlayerDataStorageAsyncMixin} hands those files to the writer. */
@Mixin(PlayerList.class)
public abstract class PlayerListSaveAllMixin {

    @Inject(method = "saveAll", at = @At("HEAD"))
    private void eden_ld$enterSaveAll(CallbackInfo ci) {
        PlayerSaves.setInSaveAll(true);
    }

    @Inject(method = "saveAll", at = @At("RETURN"))
    private void eden_ld$leaveSaveAll(CallbackInfo ci) {
        PlayerSaves.setInSaveAll(false);
    }
}
