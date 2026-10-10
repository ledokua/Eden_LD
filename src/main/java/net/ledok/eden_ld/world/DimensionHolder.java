package net.ledok.eden_ld.world;

import net.minecraft.world.Difficulty;
import org.jetbrains.annotations.Nullable;

/**
 * Added to every {@code Level} by {@code LevelDimensionMixin}: what a server dimension sets for itself —
 * its own gamerules (which {@code getGameRules()} returns instead of the server-wide ones), difficulty
 * ({@code getDifficulty()}) and PvP. Null (as on clients) = the server's.
 */
public interface DimensionHolder {

    @Nullable
    DimensionGameRules eden_ld$gameRules();

    void eden_ld$setGameRules(@Nullable DimensionGameRules rules);

    @Nullable
    Difficulty eden_ld$difficulty();

    void eden_ld$setDifficulty(@Nullable Difficulty difficulty);

    @Nullable
    Boolean eden_ld$pvp();

    void eden_ld$setPvp(@Nullable Boolean pvp);
}
