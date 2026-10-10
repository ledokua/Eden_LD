package net.ledok.eden_ld.hub.activity;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

/**
 * Whether a player may enter an activity (difficulty null) or one of its difficulties: their
 * activity level against what it needs. Arenas_LD asks this through its entry gate (for every party
 * member when a run starts); the hub uses it for its cards.
 */
public final class ActivityGate {

    private ActivityGate() {
    }

    /** Null when allowed, else the reason to show ("🔒 Lv 25+"). Unknown activities are allowed (not Eden's to judge). */
    @Nullable
    public static Component check(ServerPlayer player, ResourceLocation id, @Nullable String difficulty) {
        MinecraftServer server = player.getServer();
        Activity activity = server == null ? null : Activities.get(server, id);
        if (activity == null) {
            return null;
        }
        int required = activity.levelFor(difficulty);
        if (required < 0 || ActivityLevels.meets(player, required)) {
            return null;
        }
        return Component.translatable("message.eden_ld.activities.locked_level", required);
    }
}
