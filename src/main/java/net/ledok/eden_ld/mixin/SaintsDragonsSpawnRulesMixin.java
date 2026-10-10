package net.ledok.eden_ld.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.ServerLevelAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Saint's Dragons 0.9.5 crash fix. Its spawn rule counts the dragons around a spot by asking the live
 * world (a 192- and a 320-block box) — also while chunks generate, which runs on worker threads (C2ME),
 * so it walked the world's entity lists while the server thread changed them. That corrupted them and
 * crashed the server ("Exception ticking world" in {@code ClassInstanceMultiMap}, 2026-10-05). Spawns
 * while chunks generate, and any check off the server thread, now skip the count — a chunk being
 * generated has no dragons around it that the count could see anyway; natural spawns on the server
 * thread keep the density limit. Nothing happens without Saint's Dragons ({@code @Pseudo}), and a later
 * version without this method just isn't patched ({@code require = 0}).
 */
@Pseudo
@Mixin(targets = "com.leon.saintsdragons.server.world.DragonSpawnRules", remap = false)
public abstract class SaintsDragonsSpawnRulesMixin {

    @Inject(method = "passesNearbyDragonDensityCheck", at = @At("HEAD"), cancellable = true, require = 0)
    private static void eden_ld$onlyOnServerThread(LevelAccessor level, MobSpawnType spawnType, BlockPos pos, Class<?> dragon,
                                                    CallbackInfoReturnable<Boolean> cir) {
        if (spawnType == MobSpawnType.CHUNK_GENERATION
                || level instanceof ServerLevelAccessor accessor && !accessor.getLevel().getServer().isSameThread()) {
            cir.setReturnValue(true);
        }
    }
}
