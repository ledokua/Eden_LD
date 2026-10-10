package net.ledok.eden_ld.world;

import net.minecraft.world.level.GameRules;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Where a gamerule can be set in Eden's world settings (docs/PLAN-world-rules.md), plus a lookup of every
 * registered rule — Minecraft's and the mods' — by id.
 */
public enum RuleScope {
    /** Can differ per dimension; its server-wide value is on GLOBAL. Every mod rule, and 35 of Minecraft's. */
    DIMENSION,
    /** Server-wide only (GLOBAL): one value for the whole server. */
    GLOBAL,
    /** Matters only in the Overworld (the world spawn, phantoms, patrols, wandering traders): its page sets the server-wide value. */
    OVERWORLD,
    /** Not offered in the window; {@code /gamerule} still sets it. */
    HIDDEN;

    private static final Map<GameRules.Key<?>, RuleScope> VANILLA = Map.ofEntries(
            // One clock and one weather for the whole server (the Overworld's).
            Map.entry(GameRules.RULE_DAYLIGHT, GLOBAL),
            Map.entry(GameRules.RULE_WEATHER_CYCLE, GLOBAL),
            // Server-wide by choice (owner, 2026-10-05): not worth a per-dimension version.
            Map.entry(GameRules.RULE_REDUCEDDEBUGINFO, GLOBAL),
            Map.entry(GameRules.RULE_DO_IMMEDIATE_RESPAWN, GLOBAL),
            Map.entry(GameRules.RULE_LIMITED_CRAFTING, GLOBAL),
            Map.entry(GameRules.RULE_KEEPINVENTORY, GLOBAL),
            Map.entry(GameRules.RULE_SPAWN_RADIUS, OVERWORLD),
            Map.entry(GameRules.RULE_SPAWN_CHUNK_RADIUS, OVERWORLD),
            Map.entry(GameRules.RULE_DOINSOMNIA, OVERWORLD),
            Map.entry(GameRules.RULE_DO_PATROL_SPAWNING, OVERWORLD),
            Map.entry(GameRules.RULE_DO_TRADER_SPAWNING, OVERWORLD),
            Map.entry(GameRules.RULE_SENDCOMMANDFEEDBACK, HIDDEN),
            Map.entry(GameRules.RULE_LOGADMINCOMMANDS, HIDDEN),
            Map.entry(GameRules.RULE_MAX_COMMAND_CHAIN_LENGTH, HIDDEN),
            Map.entry(GameRules.RULE_MAX_COMMAND_FORK_COUNT, HIDDEN),
            Map.entry(GameRules.RULE_COMMAND_MODIFICATION_BLOCK_LIMIT, HIDDEN));

    /** Every registered rule by id, and its type; filled on first use (mods register theirs while loading). */
    private static final Map<String, GameRules.Key<?>> KEYS = new LinkedHashMap<>();
    private static final Map<GameRules.Key<?>, GameRules.Type<?>> TYPES = new LinkedHashMap<>();
    /** Minecraft's own rules (the {@code GameRules.RULE_*} constants); the rest come from mods. */
    private static final Set<GameRules.Key<?>> MINECRAFT = new HashSet<>();

    public static RuleScope of(GameRules.Key<?> key) {
        return VANILLA.getOrDefault(key, DIMENSION);
    }

    /** A rule by its id ({@code doFireTick}, {@code minecells.suffocationFix}), or null when nothing registered it. */
    @Nullable
    public static GameRules.Key<?> key(String id) {
        index();
        return KEYS.get(id);
    }

    /** Every registered rule in id order (Minecraft's and the mods'). */
    public static Map<String, GameRules.Key<?>> all() {
        index();
        return Collections.unmodifiableMap(KEYS);
    }

    @SuppressWarnings("unchecked")
    public static <T extends GameRules.Value<T>> GameRules.Type<T> type(GameRules.Key<T> key) {
        index();
        return (GameRules.Type<T>) TYPES.get(key);
    }

    /** A mod's rule rather than Minecraft's. */
    public static boolean modded(GameRules.Key<?> key) {
        index();
        return !MINECRAFT.contains(key);
    }

    private static synchronized void index() {
        if (!KEYS.isEmpty()) {
            return;
        }
        GameRules.visitGameRuleTypes(new GameRules.GameRuleTypeVisitor() {
            @Override
            public <T extends GameRules.Value<T>> void visit(GameRules.Key<T> key, GameRules.Type<T> type) {
                KEYS.put(key.getId(), key);
                TYPES.put(key, type);
            }
        });
        for (Field field : GameRules.class.getFields()) {
            if (Modifier.isStatic(field.getModifiers()) && field.getType() == GameRules.Key.class) {
                try {
                    MINECRAFT.add((GameRules.Key<?>) field.get(null));
                } catch (IllegalAccessException ignored) {
                    // Public constants; can't happen.
                }
            }
        }
    }
}
