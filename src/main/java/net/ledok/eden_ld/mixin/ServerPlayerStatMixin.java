package net.ledok.eden_ld.mixin;

import net.ledok.eden_ld.hub.daily.DailyProgressHooks;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Daily quest progress from vanilla statistics (placing, mining, fishing, any stat). */
@Mixin(ServerPlayer.class)
public abstract class ServerPlayerStatMixin {

    @Inject(method = "awardStat", at = @At("HEAD"))
    private void eden_ld$dailyProgress(Stat<?> stat, int amount, CallbackInfo ci) {
        if (amount > 0) {
            DailyProgressHooks.onStat((ServerPlayer) (Object) this, stat, amount);
        }
    }
}
