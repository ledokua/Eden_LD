package net.ledok.eden_ld.hub.activity;

import net.ledok.eden_ld.EdenLD;
import net.ledok.eden_ld.compat.PuffishCompat;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

/**
 * The player's activity level: their level in the Pufferfish Skills category set in config
 * ({@code activities.level_category}). With no category set (or Pufferfish Skills missing) nothing
 * is locked. Dev runs can fake it with {@code -Deden_ld.devActivityLevel=12}.
 */
public final class ActivityLevels {

    private static final Integer DEV_LEVEL = Integer.getInteger("eden_ld.devActivityLevel");
    private static boolean warned;

    private ActivityLevels() {
    }

    /** Whether levels lock anything at all. */
    public static boolean gated() {
        return DEV_LEVEL != null || category() != null && PuffishCompat.LOADED;
    }

    /** The player's level (0 when unknown; meaningless while {@link #gated()} is false). */
    public static int level(ServerPlayer player) {
        if (DEV_LEVEL != null) {
            return DEV_LEVEL;
        }
        ResourceLocation category = category();
        return category == null ? 0 : Math.max(0, PuffishCompat.level(player, category));
    }

    /** Whether the player meets {@code required} (always, while nothing is gated). */
    public static boolean meets(ServerPlayer player, int required) {
        return !gated() || required <= 0 || level(player) >= required;
    }

    @Nullable
    private static ResourceLocation category() {
        String configured = EdenLD.CONFIG.activities.level_category;
        if (configured == null || configured.isBlank()) {
            if (!warned) {
                warned = true;
                EdenLD.LOGGER.warn("[Activities] activities.level_category is empty: no activity is locked by level.");
            }
            return null;
        }
        return ResourceLocation.tryParse(configured.trim());
    }
}
