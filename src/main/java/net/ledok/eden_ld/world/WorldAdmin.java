package net.ledok.eden_ld.world;

import net.ledok.eden_ld.EdenLD;
import net.ledok.eden_ld.combat.PvpMode;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.border.WorldBorder;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * The server side of Moderation → World settings (the {@code world} area of {@code AdminTools}): every
 * gamerule the window offers with where it can be set, the server's PvP and difficulty, and each
 * dimension's own settings — and the changes the window sends.
 */
public final class WorldAdmin {

    private WorldAdmin() {
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static CompoundTag data(MinecraftServer server) {
        CompoundTag tag = new CompoundTag();
        tag.putBoolean("writable", WorldRules.writable());
        tag.putBoolean("borders", WorldBorders.MANAGED);
        tag.putBoolean("pvp", server.isPvpAllowed());
        tag.putString("difficulty", server.getWorldData().getDifficulty().getKey());
        GameRules serverRules = server.getGameRules();
        ListTag rules = new ListTag();
        for (GameRules.Key<?> key : RuleScope.all().values()) {
            RuleScope scope = RuleScope.of(key);
            if (scope == RuleScope.HIDDEN) {
                continue;
            }
            GameRules.Value<?> sample = RuleScope.type((GameRules.Key) key).createRule();
            CompoundTag rule = new CompoundTag();
            rule.putString("id", key.getId());
            rule.putString("category", key.getCategory().name());
            rule.putString("scope", scope.name());
            rule.putBoolean("modded", RuleScope.modded(key));
            rule.putString("type", sample instanceof GameRules.BooleanValue ? "bool"
                    : sample instanceof GameRules.IntegerValue ? "int" : "text");
            rule.putString("default", sample.serialize());
            rule.putString("value", serverRules.getRule(key).serialize());
            rules.add(rule);
        }
        tag.put("rules", rules);
        ListTag dimensions = new ListTag();
        for (ServerLevel level : server.getAllLevels()) {
            CompoundTag dimension = new CompoundTag();
            String id = WorldRules.id(level);
            dimension.putString("id", id);
            CompoundTag own = new CompoundTag();
            DimensionGameRules dimensionRules = WorldRules.rules(level);
            if (dimensionRules != null) {
                for (GameRules.Key<?> key : dimensionRules.changed()) {
                    own.putString(key.getId(), level.getGameRules().getRule(key).serialize());
                }
            }
            dimension.put("rules", own);
            PvpMode pvp = WorldRules.ownPvp(level);
            if (pvp != null) {
                // "pvp_mode" for 1.3.3+ windows; the old switch too, for an older one (toggle reads as the server's).
                dimension.putString("pvp_mode", pvp.key);
                if (pvp != PvpMode.TOGGLE) {
                    dimension.putBoolean("pvp", pvp == PvpMode.ON);
                }
            }
            Difficulty difficulty = WorldRules.ownDifficulty(level);
            if (difficulty != null) {
                dimension.putString("difficulty", difficulty.getKey());
            }
            dimension.putInt("flight", flight(id));
            WorldBorders.Border border = WorldBorders.live(level);
            CompoundTag borderTag = new CompoundTag();
            borderTag.putBoolean("on", !border.isNone());
            borderTag.putDouble("center_x", border.centerX());
            borderTag.putDouble("center_z", border.centerZ());
            borderTag.putDouble("size", border.size());
            borderTag.putDouble("damage_per_block", border.damagePerBlock());
            borderTag.putDouble("safe_zone", border.safeZone());
            borderTag.putInt("warning_blocks", border.warningBlocks());
            borderTag.putInt("warning_time", border.warningTime());
            dimension.put("border", borderTag);
            dimensions.add(dimension);
        }
        tag.put("dimensions", dimensions);
        return tag;
    }

    /** 0 = flight allowed, 1 = no rocket boosts, 2 = blocked (the {@code flight.json} lists). */
    private static int flight(String dimension) {
        if (EdenLD.CONFIG.flight.flight_disabled_dimensions.contains(dimension)) {
            return 2;
        }
        return EdenLD.CONFIG.flight.boost_disabled_dimensions.contains(dimension) ? 1 : 0;
    }

    /** A change from the window. Null when done (the window shows it), else why it wasn't. */
    @Nullable
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static Component act(ServerPlayer admin, String action, CompoundTag args) {
        MinecraftServer server = admin.server;
        String who = admin.getGameProfile().getName();
        if (action.equals("flight")) {
            ServerLevel level = level(server, args.getString("dimension"));
            if (level != null) {
                setFlight(WorldRules.id(level), args.getInt("mode"), who);
            }
            return null;
        }
        GameRules.Key<?> key = RuleScope.key(args.getString("rule"));
        if (action.equals("global_rule")) {
            // The world save's value, as /gamerule sets it: dimensions.json isn't involved.
            if (key != null && RuleScope.of(key) != RuleScope.HIDDEN) {
                WorldRules.setServerWide(server, (GameRules.Key) key, args.getString("value"), who);
            }
            return null;
        }
        if (!WorldRules.writable()) {
            return Component.translatable("message.eden_ld.world.file_broken");
        }
        ServerLevel level = level(server, args.getString("dimension"));
        switch (action) {
            case "rule" -> {
                if (key != null && level != null && RuleScope.of(key) == RuleScope.DIMENSION) {
                    WorldRules.set(level, (GameRules.Key) key, args.getString("value"), who);
                }
            }
            case "pvp" -> {
                // "server", or a mode ("on", "off", "toggle").
                PvpMode mode = PvpMode.parse(args.getString("value"));
                if (level != null && (mode != null || args.getString("value").equals("server"))) {
                    WorldRules.setPvp(level, mode, who);
                }
            }
            case "difficulty" -> {
                if (level != null) {
                    String value = args.getString("value");
                    WorldRules.setDifficulty(level, value.equals("server") ? null : Difficulty.byName(value), who);
                }
            }
            case "border" -> {
                if (level == null) {
                    break;
                }
                if (!WorldBorders.MANAGED) {
                    return Component.translatable("gui.eden_ld.moderation.world.border.not_managed");
                }
                WorldBorders.Border none = WorldBorders.Border.NONE;
                WorldBorders.set(level, !args.getBoolean("on") ? null : new WorldBorders.Border(
                        clamp(args.getDouble("center_x"), -2.9999984E7, 2.9999984E7),
                        clamp(args.getDouble("center_z"), -2.9999984E7, 2.9999984E7),
                        clamp(args.getDouble("size"), 1, WorldBorder.MAX_SIZE),
                        clamp(args.contains("damage_per_block") ? args.getDouble("damage_per_block") : none.damagePerBlock(), 0, 1000),
                        clamp(args.contains("safe_zone") ? args.getDouble("safe_zone") : none.safeZone(), 0, 1.0E6),
                        (int) clamp(args.contains("warning_blocks") ? args.getInt("warning_blocks") : none.warningBlocks(), 0, 1.0E6),
                        (int) clamp(args.contains("warning_time") ? args.getInt("warning_time") : none.warningTime(), 0, 1.0E6)), who);
            }
            default -> {
            }
        }
        return null;
    }

    private static void setFlight(String dimension, int mode, String who) {
        List<String> blocked = EdenLD.CONFIG.flight.flight_disabled_dimensions;
        List<String> noBoosts = EdenLD.CONFIG.flight.boost_disabled_dimensions;
        blocked.remove(dimension);
        noBoosts.remove(dimension);
        if (mode == 2) {
            blocked.add(dimension);
        } else if (mode == 1) {
            noBoosts.add(dimension);
        }
        EdenLD.CONFIG.saveFlight();
        EdenLD.LOGGER.info("[World] {} set flight in {} to {}.", who, dimension,
                mode == 2 ? "blocked" : mode == 1 ? "no rocket boosts" : "allowed");
    }

    @Nullable
    private static ServerLevel level(MinecraftServer server, String id) {
        ResourceLocation location = ResourceLocation.tryParse(id);
        return location == null ? null : server.getLevel(ResourceKey.create(Registries.DIMENSION, location));
    }

    private static double clamp(double value, double min, double max) {
        return Double.isNaN(value) ? min : Math.max(min, Math.min(max, value));
    }
}
