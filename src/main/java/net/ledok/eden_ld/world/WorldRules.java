package net.ledok.eden_ld.world;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityWorldChangeEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.ledok.eden_ld.EdenLD;
import net.ledok.eden_ld.combat.PlayerPvp;
import net.ledok.eden_ld.combat.PvpMode;
import net.ledok.eden_ld.compat.duels.DuelsHook;
import net.minecraft.network.protocol.game.ClientboundChangeDifficultyPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Per-dimension world settings, kept in {@code config/eden_ld/dimensions.json}: each dimension's own
 * gamerules, PvP mode (docs/PLAN-pvp-toggle.md) and difficulty (docs/PLAN-world-rules.md), and its border
 * ({@link WorldBorders}). A dimension stores only what it changes; everything else follows the server's — the
 * gamerules in the world save as {@code /gamerule} sets them, PvP and difficulty in {@code server.properties}. The file is read at start and on {@code /reload}, and
 * written on every change; what Eden doesn't know (a removed mod's rule or dimension) is kept in it as it is.
 */
public final class WorldRules {

    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve(EdenLD.MOD_ID).resolve("dimensions.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /** The file as read: dimension id → its settings ({@code "gamerules"}: rule id → value, {@code "pvp"}, {@code "difficulty"}). */
    private static JsonObject file = new JsonObject();
    /** The file didn't parse: nothing is saved over it until it's fixed and read again. */
    private static boolean broken;

    private WorldRules() {
    }

    public static void initialize() {
        ServerLifecycleEvents.SERVER_STARTING.register(server -> load());
        ServerWorldEvents.LOAD.register((server, level) -> {
            apply(level, true);
            WorldBorders.loaded(level);
        });
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            WorldBorders.start(server);
            reportUnloaded(server);
        });
        ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((server, resources, success) -> {
            load();
            server.getAllLevels().forEach(level -> apply(level, true));
            WorldBorders.reload(server);
            reportUnloaded(server);
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            WorldBorders.stopped();
            file = new JsonObject();
            broken = false;
        });
        // Vanilla tells a player the server's difficulty at these moments; a dimension with its own follows up.
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> sendOwnDifficulty(handler.getPlayer()));
        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> sendOwnDifficulty(newPlayer));
        ServerEntityWorldChangeEvents.AFTER_PLAYER_CHANGE_WORLD.register((player, origin, destination) -> sendOwnDifficulty(player));
    }

    /** False while the file has an error: changes are refused so they can't overwrite it. */
    public static boolean writable() {
        return !broken;
    }

    private static DimensionHolder holder(ServerLevel level) {
        return (DimensionHolder) level;
    }

    /** A dimension's own gamerules, or null when it changes none (yet). */
    @Nullable
    public static DimensionGameRules rules(ServerLevel level) {
        return holder(level).eden_ld$gameRules();
    }

    /** A dimension's own PvP mode, or null when it follows the server's ({@link #pvpMode}). */
    @Nullable
    public static PvpMode ownPvp(ServerLevel level) {
        return holder(level).eden_ld$pvp();
    }

    /**
     * A dimension's PvP mode (docs/PLAN-pvp-toggle.md): its own, else the server's default — each player's choice
     * while {@code server.properties} has {@code pvp=true}, else off ({@link PvpMode#serverDefault}).
     */
    public static PvpMode pvpMode(ServerLevel level) {
        PvpMode own = holder(level).eden_ld$pvp();
        return own != null ? own : PvpMode.serverDefault(level.getServer().isPvpAllowed());
    }

    /** A dimension's own difficulty, or null when it follows the server's. */
    @Nullable
    public static Difficulty ownDifficulty(ServerLevel level) {
        return holder(level).eden_ld$difficulty();
    }

    /**
     * Vanilla's PvP switch for {@code player} where they are ({@code server}: {@code server.properties}): off only in a
     * dimension whose mode is {@link PvpMode#OFF}, unless they're in a running Duels match (Duels relies on PvP being on
     * and keeps outsiders out itself). Who may hurt whom beyond that — each player's own choice, parties, guilds, run
     * teams and duels — is {@code CombatTeamsCompat}'s relation, which vanilla's check asks next.
     */
    public static boolean pvp(ServerPlayer player, boolean server) {
        PvpMode own = holder(player.serverLevel()).eden_ld$pvp();
        PvpMode mode = own != null ? own : PvpMode.serverDefault(server);
        return mode != PvpMode.OFF || DuelsHook.inMatch(player.getUUID());
    }

    // ---------------------------------------------------------------- changes

    /**
     * Changes {@code key} in {@code level} to {@code text} (the rule's text form). The server-wide value
     * means "follow it": the change is removed instead. True when the dimension now changes the rule.
     * Callers check {@link #writable()} first.
     */
    public static <T extends GameRules.Value<T>> boolean set(ServerLevel level, GameRules.Key<T> key, String text, String who) {
        if (RuleScope.of(key) != RuleScope.DIMENSION) {
            throw new IllegalArgumentException(key.getId() + " is server-wide");
        }
        GameRules serverRules = level.getServer().getWorldData().getGameRules();
        String value = DimensionGameRules.parse(key, text).serialize();
        if (value.equals(serverRules.getRule(key).serialize())) {
            reset(level, key, who);
            return false;
        }
        String dimension = id(level);
        gamerules(dimension, true).add(key.getId(), json(key, value));
        save();
        apply(level, false);
        EdenLD.LOGGER.info("[World] {} set {} to {} in {}.", who, key.getId(), value, dimension);
        return true;
    }

    /**
     * Sets the server-wide value of {@code key} from its text form, as {@code /gamerule} does (its change
     * callbacks run: players are told about the rules their game keeps a copy of).
     */
    public static <T extends GameRules.Value<T>> void setServerWide(MinecraftServer server, GameRules.Key<T> key, String text,
                                                                  String who) {
        T rule = server.getGameRules().getRule(key);
        rule.setFrom(DimensionGameRules.parse(key, text), server);
        EdenLD.LOGGER.info("[World] {} set {} to {} on the whole server.", who, key.getId(), rule.serialize());
    }

    /** Makes {@code level} follow the server-wide value of {@code key} again. False when it already did. */
    public static boolean reset(ServerLevel level, GameRules.Key<?> key, String who) {
        String dimension = id(level);
        JsonObject saved = gamerules(dimension, false);
        if (saved == null || saved.remove(key.getId()) == null) {
            return false;
        }
        prune(dimension);
        save();
        apply(level, false);
        EdenLD.LOGGER.info("[World] {} reset {} in {} to the server-wide value.", who, key.getId(), dimension);
        return true;
    }

    /**
     * Sets the PvP mode of {@code level}, or null — or the server's default ({@link PvpMode#serverDefault}) — to follow
     * the server. True when the dimension now has its own mode. Saved as {@code true} (on) and {@code false} (off), as
     * before the toggle, and {@code "toggle"}. Callers check {@link #writable()} first.
     */
    public static boolean setPvp(ServerLevel level, @Nullable PvpMode mode, String who) {
        boolean own = mode != null && mode != PvpMode.serverDefault(level.getServer().isPvpAllowed());
        return setting(level, "pvp", !own ? null : mode == PvpMode.TOGGLE ? new JsonPrimitive(mode.key)
                : new JsonPrimitive(mode == PvpMode.ON), who);
    }

    /**
     * Sets the difficulty of {@code level}, or null — or the server's own difficulty — to follow the server.
     * True when the dimension now has its own. Callers check {@link #writable()} first.
     */
    public static boolean setDifficulty(ServerLevel level, @Nullable Difficulty difficulty, String who) {
        boolean own = difficulty != null && difficulty != level.getServer().getWorldData().getDifficulty();
        return setting(level, "difficulty", own ? new JsonPrimitive(difficulty.getKey()) : null, who);
    }

    /** Writes (or, with null, removes) one of a dimension's own settings and applies the file again. */
    private static boolean setting(ServerLevel level, String name, @Nullable JsonPrimitive value, String who) {
        String dimension = id(level);
        if (value != null) {
            settings(dimension, true).add(name, value);
        } else if (settings(dimension, false) instanceof JsonObject saved) {
            saved.remove(name);
            prune(dimension);
        }
        save();
        apply(level, false);
        if (value != null) {
            EdenLD.LOGGER.info("[World] {} set {} to {} in {}.", who, name, value.getAsString(), dimension);
        } else {
            EdenLD.LOGGER.info("[World] {} set {} in {} to follow the server.", who, name, dimension);
        }
        return value != null;
    }

    /**
     * A rule value changed with a server at hand. When it is a dimension's own value — a mod wrote through
     * that dimension's {@code getGameRules()} — the file follows.
     */
    public static void changedThrough(GameRules.Value<?> value, MinecraftServer server) {
        for (ServerLevel level : server.getAllLevels()) {
            DimensionGameRules rules = rules(level);
            GameRules.Key<?> key = rules == null ? null : rules.keyOf(value);
            if (key != null) {
                String dimension = id(level);
                gamerules(dimension, true).add(key.getId(), json(key, value.serialize()));
                save();
                EdenLD.LOGGER.info("[World] {} in {} was changed to {} through the dimension's rules.",
                        key.getId(), dimension, value.serialize());
                return;
            }
        }
    }

    // ---------------------------------------------------------------- difficulty side effects

    /**
     * Monster spawning per dimension: vanilla switches it for every dimension from the server's difficulty
     * (off on Peaceful); a dimension with its own difficulty decides for itself. The server's
     * {@code spawn-monsters=false} still wins, unless the server is off only because it is on Peaceful.
     */
    public static void updateSpawning(MinecraftServer server) {
        for (ServerLevel level : server.getAllLevels()) {
            if (ownDifficulty(level) != null) {
                updateSpawning(level);
            }
        }
    }

    private static void updateSpawning(ServerLevel level) {
        MinecraftServer server = level.getServer();
        Difficulty own = ownDifficulty(level);
        boolean monsters = own == null ? server.isSpawningMonsters()
                : own != Difficulty.PEACEFUL
                && (server.isSpawningMonsters() || server.getWorldData().getDifficulty() == Difficulty.PEACEFUL);
        level.setSpawnSettings(monsters, server.isSpawningAnimals());
    }

    /** After {@code /difficulty}: players in a dimension with its own difficulty hear that one again. */
    public static void resendDifficulty(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            sendOwnDifficulty(player);
        }
    }

    private static void sendOwnDifficulty(ServerPlayer player) {
        if (ownDifficulty(player.serverLevel()) != null) {
            sendDifficulty(player);
        }
    }

    private static void sendDifficulty(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        player.connection.send(new ClientboundChangeDifficultyPacket(level.getDifficulty(),
                level.getLevelData().isDifficultyLocked()));
    }

    // ---------------------------------------------------------------- the file

    /**
     * Fills a dimension's own settings from the file: on load and /reload ({@code report}: entries that
     * can't apply are logged), and after a change.
     */
    static void apply(ServerLevel level, boolean report) {
        DimensionHolder holder = holder(level);
        JsonObject settings = settings(id(level), false);
        applyGamerules(level, holder, report);

        PvpMode pvp = null;
        JsonElement savedPvp = settings == null ? null : settings.get("pvp");
        if (savedPvp != null) {
            pvp = savedPvp.isJsonPrimitive() ? PvpMode.parse(savedPvp.getAsString()) : null;
            if (pvp == null && report) {
                EdenLD.LOGGER.warn("[World] dimensions.json: pvp in {} must be true (on), false (off) or \"toggle\", not {}.",
                        id(level), savedPvp);
            }
        }
        PvpMode before = pvpMode(level);
        holder.eden_ld$setPvp(pvp);
        if (pvpMode(level) != before) {
            // Players there see the new mode at once (the inventory button, nameplates, their combat mods' targeting).
            PlayerPvp.modeChanged(level);
        }

        Difficulty difficulty = null;
        JsonElement savedDifficulty = settings == null ? null : settings.get("difficulty");
        if (savedDifficulty != null) {
            difficulty = savedDifficulty.isJsonPrimitive()
                    ? Difficulty.byName(savedDifficulty.getAsString().trim().toLowerCase(Locale.ROOT)) : null;
            if (difficulty == null && report) {
                EdenLD.LOGGER.warn("[World] dimensions.json: difficulty in {} must be peaceful, easy, normal or hard, not {}.",
                        id(level), savedDifficulty);
            }
        }
        if (!Objects.equals(holder.eden_ld$difficulty(), difficulty)) {
            holder.eden_ld$setDifficulty(difficulty);
            updateSpawning(level);
            level.players().forEach(WorldRules::sendDifficulty);
        }
    }

    private static void applyGamerules(ServerLevel level, DimensionHolder holder, boolean report) {
        DimensionGameRules rules = holder.eden_ld$gameRules();
        JsonObject saved = gamerules(id(level), false);
        if (rules == null) {
            if (saved == null || saved.isEmpty()) {
                return;
            }
            rules = new DimensionGameRules(level.getServer(), level.getServer().getWorldData().getGameRules());
            holder.eden_ld$setGameRules(rules);
        }
        rules.clear();
        if (saved == null) {
            return;
        }
        for (Map.Entry<String, JsonElement> entry : saved.entrySet()) {
            GameRules.Key<?> key = RuleScope.key(entry.getKey());
            String text = key == null ? null : text(key, entry.getValue());
            if (key != null && RuleScope.of(key) == RuleScope.DIMENSION && text != null) {
                rules.set(key, text);
            } else if (report && key == null) {
                EdenLD.LOGGER.warn("[World] dimensions.json: no gamerule is called {} (a removed mod's?); {} keeps it unused.",
                        entry.getKey(), id(level));
            } else if (report && RuleScope.of(key) != RuleScope.DIMENSION) {
                EdenLD.LOGGER.warn("[World] dimensions.json: {} is server-wide and can't differ in {}; set it with /gamerule.",
                        key.getId(), id(level));
            } else if (report) {
                EdenLD.LOGGER.warn("[World] dimensions.json: {} in {} has a value that doesn't fit it: {}", key.getId(), id(level),
                        entry.getValue());
            }
        }
    }

    /** A saved value as the rule's text form, or null when it doesn't fit the rule (a word for a number…). */
    @Nullable
    private static String text(GameRules.Key<?> key, JsonElement element) {
        if (!element.isJsonPrimitive()) {
            return null;
        }
        JsonPrimitive value = element.getAsJsonPrimitive();
        GameRules.Value<?> sample = sample(key);
        if (sample instanceof GameRules.BooleanValue) {
            String text = value.getAsString().trim();
            return text.equals("true") || text.equals("false") ? text : null;
        }
        if (sample instanceof GameRules.IntegerValue) {
            try {
                return Integer.toString(Integer.parseInt(value.getAsString().trim()));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return value.getAsString();
    }

    /** How a value is written to the file: a switch as true/false, a number as a number, anything else as text. */
    private static JsonPrimitive json(GameRules.Key<?> key, String text) {
        GameRules.Value<?> sample = sample(key);
        if (sample instanceof GameRules.BooleanValue) {
            return new JsonPrimitive(Boolean.parseBoolean(text));
        }
        if (sample instanceof GameRules.IntegerValue) {
            try {
                return new JsonPrimitive(Integer.parseInt(text));
            } catch (NumberFormatException ignored) {
                // Falls through to text.
            }
        }
        return new JsonPrimitive(text);
    }

    /** A fresh value of the rule's type (its default), to tell switches from numbers. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static GameRules.Value<?> sample(GameRules.Key<?> key) {
        return RuleScope.type((GameRules.Key) key).createRule();
    }

    static String id(ServerLevel level) {
        return level.dimension().location().toString();
    }

    /** A dimension's settings object in the file; made when {@code create}, else null when missing. */
    @Nullable
    static JsonObject settings(String dimension, boolean create) {
        if (file.get(dimension) instanceof JsonObject found) {
            return found;
        }
        if (!create) {
            return null;
        }
        JsonObject made = new JsonObject();
        file.add(dimension, made);
        return made;
    }

    /** A dimension's {@code "gamerules"} object in the file; made when {@code create}, else null when missing. */
    @Nullable
    private static JsonObject gamerules(String dimension, boolean create) {
        JsonObject object = settings(dimension, create);
        if (object == null) {
            return null;
        }
        if (object.get("gamerules") instanceof JsonObject found) {
            return found;
        }
        if (!create) {
            return null;
        }
        JsonObject made = new JsonObject();
        object.add("gamerules", made);
        return made;
    }

    /** Drops a dimension's empty {@code "gamerules"} object, and the dimension when nothing is left. */
    static void prune(String dimension) {
        if (file.get(dimension) instanceof JsonObject object) {
            if (object.get("gamerules") instanceof JsonObject rules && rules.isEmpty()) {
                object.remove("gamerules");
            }
            if (object.isEmpty()) {
                file.remove(dimension);
            }
        }
    }

    private static void load() {
        broken = false;
        file = new JsonObject();
        if (!Files.isRegularFile(FILE)) {
            return;
        }
        try {
            JsonElement read = JsonParser.parseString(Files.readString(FILE, StandardCharsets.UTF_8));
            if (read instanceof JsonObject object) {
                file = object;
            } else if (!read.isJsonNull()) {
                throw new IllegalStateException("not a JSON object");
            }
        } catch (Exception e) {
            broken = true;
            EdenLD.LOGGER.error("[World] Can't read {}: dimensions follow the server's settings, and changes "
                    + "aren't saved until it's fixed and read again (/reload).", FILE, e);
        }
    }

    static void save() {
        if (broken) {
            return;
        }
        try {
            Files.createDirectories(FILE.getParent());
            Files.writeString(FILE, GSON.toJson(file) + "\n", StandardCharsets.UTF_8);
        } catch (Exception e) {
            EdenLD.LOGGER.error("[World] Couldn't save {}; the change holds until the server stops.", FILE, e);
        }
    }

    /** Settings for a dimension this server doesn't load wait in the file (a dimension from a mod not installed now). */
    private static void reportUnloaded(MinecraftServer server) {
        for (String dimension : file.keySet()) {
            boolean loaded = server.levelKeys().stream().anyMatch(key -> key.location().toString().equals(dimension));
            if (!loaded) {
                EdenLD.LOGGER.warn("[World] dimensions.json has settings for {}, which this server doesn't load; they wait.",
                        dimension);
            }
        }
    }
}
