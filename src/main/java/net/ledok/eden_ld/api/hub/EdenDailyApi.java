package net.ledok.eden_ld.api.hub;

import net.ledok.eden_ld.hub.HubServer;
import net.ledok.eden_ld.hub.daily.DailyQuests;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Feed daily quest progress from other mods (source-stable). Server thread.
 *
 * <p>A quest's {@code objective} is a progress type. Eden counts the built-in ones itself
 * (kills, crafting, placing, mining, eating, fishing, Spell Engine casts, vanilla stats) and
 * credits {@link #COMPLETE_DUNGEON} for won activity runs (Arenas_LD, {@link EdenActivityApi#reportRun};
 * target = the activity id). Any id your
 * mod invents (e.g. {@code arenas_ld:win_run}) arrives through {@link #reportProgress}.
 */
public final class EdenDailyApi {

    public static final ResourceLocation KILL_MOBS = DailyQuests.KILL_MOBS;
    public static final ResourceLocation CRAFT_ITEMS = DailyQuests.CRAFT_ITEMS;
    public static final ResourceLocation CAST_SPELLS = DailyQuests.CAST_SPELLS;
    public static final ResourceLocation COMPLETE_DUNGEON = DailyQuests.COMPLETE_DUNGEON;
    public static final ResourceLocation PLACE_BLOCKS = DailyQuests.PLACE_BLOCKS;
    public static final ResourceLocation BREAK_BLOCKS = DailyQuests.BREAK_BLOCKS;
    public static final ResourceLocation EAT_FOOD = DailyQuests.EAT_FOOD;
    public static final ResourceLocation CATCH_FISH = DailyQuests.CATCH_FISH;
    public static final ResourceLocation STAT = DailyQuests.STAT;

    private EdenDailyApi() {
    }

    /**
     * Adds {@code amount} progress to the player's unclaimed quests of this {@code type}.
     * {@code subject} is what was done (a spell id, a dungeon id, …) and is matched against a
     * quest's {@code target} by exact id; quests without a target accept any subject (null too).
     */
    public static void reportProgress(ServerPlayer player, ResourceLocation type, @Nullable ResourceLocation subject,
                                      int amount) {
        if (DailyQuests.report(player, type, DailyQuests.idMatcher(subject), amount)) {
            HubServer.pushDailies(player);
        }
    }

    /** Extra free rerolls for today only (they expire at the daily reset). Works offline too. */
    public static void grantExtraRerolls(MinecraftServer server, UUID player, int amount) {
        DailyQuests.grantExtraRerolls(server, player, amount);
        HubServer.pushDailies(server, player);
    }
}
