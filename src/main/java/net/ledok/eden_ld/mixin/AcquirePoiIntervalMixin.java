package net.ledok.eden_ld.mixin;

import net.ledok.eden_ld.EdenLD;
import net.minecraft.world.entity.ai.behavior.AcquirePoi;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Villagers without a bed, job site or meeting point search for one less often ({@code villagers.json}
 * {@code poi_search_interval_ticks}). Vanilla re-runs the search 20 + up to 20 ticks after each try, and every try
 * pathfinds to up to 5 free spots; in villages whose beds and workstations can't be reached it never succeeds, so the
 * searches were 13% of the server thread. Only that wait changes: what is searched and how stay vanilla.
 */
@Mixin(AcquirePoi.class)
public abstract class AcquirePoiIntervalMixin {

    @org.spongepowered.asm.mixin.Unique
    private static boolean eden_ld$logged;

    /** The behavior's lambda that runs the search; its second {@code setValue} schedules the next one. */
    @ModifyArg(method = "method_46885",
            at = @At(value = "INVOKE", target = "Lorg/apache/commons/lang3/mutable/MutableLong;setValue(J)V", ordinal = 1),
            require = 0)
    private static long eden_ld$searchLessOften(long nextSearch) {
        if (!eden_ld$logged) {
            // Optional injection (require = 0): this line in the log shows it is in place.
            eden_ld$logged = true;
            EdenLD.LOGGER.info("[Villagers] Point-of-interest search interval: {} ticks (vanilla 20).",
                    EdenLD.CONFIG.villagers.poi_search_interval_ticks);
        }
        int interval = EdenLD.CONFIG.villagers.poi_search_interval_ticks;
        if (interval <= 20) {
            return nextSearch;
        }
        // Vanilla's wait is 20 + up to 20: make it about interval + up to interval.
        return nextSearch + (interval - 20) + ThreadLocalRandom.current().nextInt(interval);
    }
}
