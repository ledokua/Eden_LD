package net.ledok.eden_ld.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.ledok.eden_ld.world.DimensionGameRules;
import net.ledok.eden_ld.world.DimensionHolder;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * A dimension's own settings (see {@link net.ledok.eden_ld.world.WorldRules}): {@code getGameRules()}
 * answers with its gamerules and {@code getDifficulty()} with its difficulty when it has them; every other
 * level keeps the server's.
 */
@Mixin(Level.class)
public abstract class LevelDimensionMixin implements LevelAccessor, DimensionHolder {

    @Unique
    @Nullable
    private volatile DimensionGameRules eden_ld$gameRules;
    @Unique
    @Nullable
    private volatile Difficulty eden_ld$difficulty;
    @Unique
    @Nullable
    private volatile Boolean eden_ld$pvp;

    @ModifyReturnValue(method = "getGameRules", at = @At("RETURN"))
    private GameRules eden_ld$dimensionRules(GameRules rules) {
        DimensionGameRules own = eden_ld$gameRules;
        return own != null ? own : rules;
    }

    /**
     * {@code LevelAccessor}'s default reads the level data, which every dimension shares with the Overworld;
     * a dimension with a difficulty of its own answers with it. Everything that asks the level follows:
     * mob damage, hunger, local difficulty, monster spawning and despawning in Peaceful.
     */
    @Override
    public Difficulty getDifficulty() {
        Difficulty own = eden_ld$difficulty;
        return own != null ? own : getLevelData().getDifficulty();
    }

    @Override
    @Nullable
    public DimensionGameRules eden_ld$gameRules() {
        return eden_ld$gameRules;
    }

    @Override
    public void eden_ld$setGameRules(@Nullable DimensionGameRules rules) {
        eden_ld$gameRules = rules;
    }

    @Override
    @Nullable
    public Difficulty eden_ld$difficulty() {
        return eden_ld$difficulty;
    }

    @Override
    public void eden_ld$setDifficulty(@Nullable Difficulty difficulty) {
        eden_ld$difficulty = difficulty;
    }

    @Override
    @Nullable
    public Boolean eden_ld$pvp() {
        return eden_ld$pvp;
    }

    @Override
    public void eden_ld$setPvp(@Nullable Boolean pvp) {
        eden_ld$pvp = pvp;
    }
}
