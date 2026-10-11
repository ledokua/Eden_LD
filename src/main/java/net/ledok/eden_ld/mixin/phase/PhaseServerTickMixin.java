package net.ledok.eden_ld.mixin.phase;

import net.ledok.eden_ld.tutorial.phase.PrivateBlocks;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.BooleanSupplier;

/** Private block copies tick after the worlds do. */
@Mixin(MinecraftServer.class)
public abstract class PhaseServerTickMixin {

    @Inject(method = "tickChildren", at = @At("TAIL"))
    private void eden_ld$tickPrivateBlocks(BooleanSupplier hasTimeLeft, CallbackInfo ci) {
        PrivateBlocks.tick((MinecraftServer) (Object) this);
    }
}
