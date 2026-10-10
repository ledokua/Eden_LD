package net.ledok.eden_ld.hub.activity;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;

/** Where activities come from (Arenas_LD, a datapack). Server thread. */
public interface ActivitySource {

    List<Activity> activities(MinecraftServer server);

    /** Shown in the Moderation window ("Arenas_LD", "datapack"). */
    String name();

    /** Opens the activity's lobby screen (or whatever "go there" means) for the player; false if unknown. */
    boolean open(ServerPlayer player, ResourceLocation activity);
}
