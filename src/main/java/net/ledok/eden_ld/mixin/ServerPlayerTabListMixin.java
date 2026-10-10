package net.ledok.eden_ld.mixin;

import net.ledok.eden_ld.chat.TabList;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The tab list shows Eden's names (guild tag, prefix, group color, suffix); see {@link TabList}. */
@Mixin(ServerPlayer.class)
public abstract class ServerPlayerTabListMixin {

    @Inject(method = "getTabListDisplayName", at = @At("HEAD"), cancellable = true)
    private void eden_ld$tabName(CallbackInfoReturnable<Component> cir) {
        if (TabList.enabled()) {
            cir.setReturnValue(TabList.name((ServerPlayer) (Object) this));
        }
    }
}
