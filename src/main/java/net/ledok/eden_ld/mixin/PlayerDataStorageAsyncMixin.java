package net.ledok.eden_ld.mixin;

import net.ledok.eden_ld.EdenLD;
import net.ledok.eden_ld.world.AsyncSavedData;
import net.ledok.eden_ld.world.PlayerSaves;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.storage.PlayerDataStorage;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.io.File;
import java.util.Optional;

/**
 * Player files in a save of all players go through {@link AsyncSavedData}'s writer ({@code saving.json}
 * {@code async_player_saves}): the per-player gzip and write were 12% of the live autosave. Any other save (logout) and
 * every read first wait for that player's queued file, so an older snapshot never lands after a newer one.
 */
@Mixin(PlayerDataStorage.class)
public abstract class PlayerDataStorageAsyncMixin {

    @Shadow
    @Final
    private File playerDir;

    @Inject(method = "save", at = @At("HEAD"), cancellable = true)
    private void eden_ld$saveOffThread(Player player, CallbackInfo ci) {
        File file = new File(playerDir, player.getStringUUID() + ".dat");
        if (PlayerSaves.inSaveAll() && EdenLD.CONFIG.saving.async_player_saves && AsyncSavedData.canQueue()) {
            CompoundTag tag = player.saveWithoutId(new CompoundTag());
            AsyncSavedData.queuePlayer(file.toPath(), new File(playerDir, player.getStringUUID() + ".dat_old").toPath(), tag.copy());
            ci.cancel();
            return;
        }
        AsyncSavedData.awaitFile(file);
    }

    @Inject(method = "load(Lnet/minecraft/world/entity/player/Player;Ljava/lang/String;)Ljava/util/Optional;",
            at = @At("HEAD"))
    private void eden_ld$awaitBeforeRead(Player player, String extension, CallbackInfoReturnable<Optional<CompoundTag>> cir) {
        AsyncSavedData.awaitFile(new File(playerDir, player.getStringUUID() + extension));
    }
}
