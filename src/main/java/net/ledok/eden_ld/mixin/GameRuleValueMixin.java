package net.ledok.eden_ld.mixin;

import net.ledok.eden_ld.world.WorldRules;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.GameRules;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A rule value changed with a server at hand (a command or a mod; Eden's own changes pass none): when it
 * belongs to a dimension's own rules — a mod wrote through that dimension's {@code getGameRules()}, like
 * Axiom's world editor — Eden saves it like any other change.
 */
@Mixin(GameRules.Value.class)
public abstract class GameRuleValueMixin {

    @Inject(method = "onChanged", at = @At("HEAD"))
    private void eden_ld$changed(@Nullable MinecraftServer server, CallbackInfo ci) {
        if (server != null) {
            WorldRules.changedThrough((GameRules.Value<?>) (Object) this, server);
        }
    }
}
