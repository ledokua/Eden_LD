package net.ledok.eden_ld.api.hub;

import net.ledok.eden_ld.hub.activity.ActivityGate;
import net.ledok.eden_ld.hub.activity.ActivityLevels;
import net.ledok.eden_ld.hub.activity.ActivityServer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;

/**
 * The hub's Activities (dungeons, raids, mob arenas) for other mods (source-stable). Server thread.
 * Arenas_LD is read by Eden directly; this is for anything else that runs activity-like content
 * (activities defined as datapack files, {@code data/<ns>/hub_activities/}).
 */
public final class EdenActivityApi {

    private EdenActivityApi() {
    }

    /** The player's activity level (the configured Pufferfish Skills tree level). */
    public static int level(ServerPlayer player) {
        return ActivityLevels.level(player);
    }

    /** Null when the player may enter the activity (difficulty null) or that difficulty; else the reason. */
    @Nullable
    public static Component check(ServerPlayer player, ResourceLocation activity, @Nullable String difficulty) {
        return ActivityGate.check(player, activity, difficulty);
    }

    /**
     * A finished run: every player's stats, and wins count for the {@code complete_dungeon} daily
     * objective. {@code wave}: how far an arena run got (0 otherwise).
     */
    public static void reportRun(MinecraftServer server, ResourceLocation activity, @Nullable String difficulty,
                                 boolean won, long durationMillis, int wave, List<UUID> players) {
        ActivityServer.recordRun(server, activity, difficulty, won, durationMillis, wave, players);
    }
}
