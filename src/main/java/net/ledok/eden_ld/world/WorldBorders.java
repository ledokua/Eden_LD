package net.ledok.eden_ld.world;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import net.fabricmc.loader.api.FabricLoader;
import net.ledok.eden_ld.EdenLD;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundSetBorderCenterPacket;
import net.minecraft.network.protocol.game.ClientboundSetBorderLerpSizePacket;
import net.minecraft.network.protocol.game.ClientboundSetBorderSizePacket;
import net.minecraft.network.protocol.game.ClientboundSetBorderWarningDelayPacket;
import net.minecraft.network.protocol.game.ClientboundSetBorderWarningDistancePacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.border.BorderChangeListener;
import net.minecraft.world.level.border.WorldBorder;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * Per-dimension world borders (docs/PLAN-world-rules.md), replacing Multi World Borders. Each dimension's
 * border lives in {@code dimensions.json} ({@code "border"}: its center in the dimension's own
 * coordinates, its size, and vanilla's damage and warning settings when they differ from the defaults)
 * and is applied at every start, so it survives world resets; a dimension without one has no border.
 * <p>
 * Vanilla ties every dimension's border to the Overworld's and always sends players the Overworld's;
 * here each dimension has its own, players are sent the one of the dimension they're in, updates go only
 * to players in that dimension, and {@code /worldborder} changes the dimension it runs in (mixins on
 * {@code MinecraftServer}, {@code PlayerList}, {@code WorldBorderCommand} and the border packets). Every
 * change — the Moderation window, {@code /worldborder}, other mods — is written back to the file.
 * <p>
 * Vanilla keeps a dimension's border center in Overworld units and divides it by the dimension's
 * coordinate scale (the Nether: 8) when asked; clients do the same, so packets carry the stored value
 * and everything Eden shows or saves is in the dimension's own coordinates.
 * <p>
 * Only one may run: while Multi World Borders is installed all of this stays off.
 */
public final class WorldBorders {

    /** Eden runs the borders (Multi World Borders isn't installed). */
    public static final boolean MANAGED = !FabricLoader.getInstance().isModLoaded("multiworldborders");

    /** Eden is setting borders from the file: their change events aren't written back. */
    private static boolean applying;
    /** The borders were set from the file once this run: from then on, changes are written back. */
    private static boolean ready;

    private WorldBorders() {
    }

    /** A dimension's border, center in its own coordinates. */
    public record Border(double centerX, double centerZ, double size, double damagePerBlock, double safeZone,
                         int warningBlocks, int warningTime) {

        /** Vanilla's border: as big as a world gets, centered on 0, 0 — none, in effect. */
        public static final Border NONE = new Border(0, 0, WorldBorder.MAX_SIZE, 0.2, 5.0, 5, 15);

        public boolean isNone() {
            return size >= WorldBorder.MAX_SIZE;
        }
    }

    // ---------------------------------------------------------------- lifecycle (called by WorldRules)

    /** After start: each dimension's own border from the file (the Overworld's from the world save when the file has none). */
    static void start(MinecraftServer server) {
        ready = false;
        if (!MANAGED) {
            EdenLD.LOGGER.warn("[World] Multi World Borders is installed, so Eden leaves world borders to it; "
                    + "remove it to have each dimension's border in dimensions.json.");
            return;
        }
        for (ServerLevel level : server.getAllLevels()) {
            level.getWorldBorder().addListener(new Listener(level));
        }
        ServerLevel overworld = server.overworld();
        if (read(overworld, false) == null && !live(overworld).isNone()) {
            write(overworld);
            EdenLD.LOGGER.info("[World] Took the Overworld's border from the world save into dimensions.json ({} wide).",
                    number(live(overworld).size()));
        }
        for (ServerLevel level : server.getAllLevels()) {
            apply(level, true);
        }
        ready = true;
    }

    /** A dimension created after start (some mods make them on the fly). */
    static void loaded(ServerLevel level) {
        if (MANAGED && ready) {
            level.getWorldBorder().addListener(new Listener(level));
            apply(level, true);
        }
    }

    /** After {@code /reload}: the file may have been edited by hand. */
    static void reload(MinecraftServer server) {
        if (MANAGED && ready) {
            for (ServerLevel level : server.getAllLevels()) {
                apply(level, true);
            }
        }
    }

    static void stopped() {
        ready = false;
    }

    // ---------------------------------------------------------------- reading and changing

    /** A dimension's border as it is now. */
    public static Border live(ServerLevel level) {
        WorldBorder border = level.getWorldBorder();
        return new Border(border.getCenterX(), border.getCenterZ(), border.getLerpTarget(), border.getDamagePerBlock(),
                border.getDamageSafeZone(), border.getWarningBlocks(), border.getWarningTime());
    }

    /** Sets a dimension's border (null or {@link Border#NONE}: none), saves it, and tells its players. */
    public static void set(ServerLevel level, @Nullable Border border, String who) {
        Border wanted = border == null ? Border.NONE : border;
        applying = true;
        try {
            setLive(level, wanted);
        } finally {
            applying = false;
        }
        write(level);
        EdenLD.LOGGER.info("[World] {} set the border of {}: {}.", who, WorldRules.id(level), describe(wanted));
    }

    /** Sets the file's border (or none) on the live one, changing only what differs. */
    private static void apply(ServerLevel level, boolean report) {
        Border wanted = read(level, report);
        applying = true;
        try {
            setLive(level, wanted == null ? Border.NONE : wanted);
        } finally {
            applying = false;
        }
    }

    private static void setLive(ServerLevel level, Border wanted) {
        WorldBorder border = level.getWorldBorder();
        double scale = level.dimensionType().coordinateScale();
        if (!same(border.getCenterX(), wanted.centerX()) || !same(border.getCenterZ(), wanted.centerZ())) {
            border.setCenter(wanted.centerX() * scale, wanted.centerZ() * scale);
        }
        if (!same(border.getDamagePerBlock(), wanted.damagePerBlock())) {
            border.setDamagePerBlock(wanted.damagePerBlock());
        }
        if (!same(border.getDamageSafeZone(), wanted.safeZone())) {
            border.setDamageSafeZone(wanted.safeZone());
        }
        if (border.getWarningBlocks() != wanted.warningBlocks()) {
            border.setWarningBlocks(wanted.warningBlocks());
        }
        if (border.getWarningTime() != wanted.warningTime()) {
            border.setWarningTime(wanted.warningTime());
        }
        // A border moving toward the size already wanted keeps moving.
        if (!same(border.getLerpTarget(), wanted.size())) {
            border.setSize(Math.max(1, Math.min(WorldBorder.MAX_SIZE, wanted.size())));
        }
    }

    private static boolean same(double a, double b) {
        return Math.abs(a - b) < 1.0E-6;
    }

    // ---------------------------------------------------------------- the file

    /** A dimension's border from the file, or null when it has none (or it can't be read: {@code report} logs why). */
    @Nullable
    private static Border read(ServerLevel level, boolean report) {
        JsonObject settings = WorldRules.settings(WorldRules.id(level), false);
        JsonElement element = settings == null ? null : settings.get("border");
        if (element == null) {
            return null;
        }
        try {
            JsonObject json = element.getAsJsonObject();
            if (!json.has("size")) {
                throw new IllegalStateException("no size");
            }
            Border none = Border.NONE;
            return new Border(
                    json.has("center_x") ? json.get("center_x").getAsDouble() : 0,
                    json.has("center_z") ? json.get("center_z").getAsDouble() : 0,
                    json.get("size").getAsDouble(),
                    json.has("damage_per_block") ? json.get("damage_per_block").getAsDouble() : none.damagePerBlock(),
                    json.has("safe_zone") ? json.get("safe_zone").getAsDouble() : none.safeZone(),
                    json.has("warning_blocks") ? json.get("warning_blocks").getAsInt() : none.warningBlocks(),
                    json.has("warning_time") ? json.get("warning_time").getAsInt() : none.warningTime());
        } catch (Exception e) {
            if (report) {
                EdenLD.LOGGER.warn("[World] dimensions.json: the border of {} can't be read ({}): {}", WorldRules.id(level),
                        e.getMessage(), element);
            }
            return null;
        }
    }

    /** Writes a dimension's live border to the file (removing it when there is none). */
    private static void write(ServerLevel level) {
        if (!WorldRules.writable()) {
            return;
        }
        Border border = live(level);
        String dimension = WorldRules.id(level);
        if (border.isNone()) {
            JsonObject settings = WorldRules.settings(dimension, false);
            if (settings != null) {
                settings.remove("border");
                WorldRules.prune(dimension);
            }
        } else {
            JsonObject json = new JsonObject();
            json.add("center_x", number(border.centerX()));
            json.add("center_z", number(border.centerZ()));
            json.add("size", number(border.size()));
            Border none = Border.NONE;
            if (!same(border.damagePerBlock(), none.damagePerBlock())) {
                json.add("damage_per_block", number(border.damagePerBlock()));
            }
            if (!same(border.safeZone(), none.safeZone())) {
                json.add("safe_zone", number(border.safeZone()));
            }
            if (border.warningBlocks() != none.warningBlocks()) {
                json.addProperty("warning_blocks", border.warningBlocks());
            }
            if (border.warningTime() != none.warningTime()) {
                json.addProperty("warning_time", border.warningTime());
            }
            WorldRules.settings(dimension, true).add("border", json);
        }
        WorldRules.save();
    }

    /** A whole number as one (20000, not 20000.0). */
    private static JsonPrimitive number(double value) {
        return value == Math.rint(value) && Math.abs(value) < 1.0E15 ? new JsonPrimitive((long) value) : new JsonPrimitive(value);
    }

    private static String describe(Border border) {
        return border.isNone() ? "none"
                : String.format(Locale.ROOT, "%s wide, centered on %s, %s", number(border.size()), number(border.centerX()),
                number(border.centerZ()));
    }

    // ---------------------------------------------------------------- one dimension's listener

    /** Tells the dimension's players about every change (only them) and writes it to the file. */
    private record Listener(ServerLevel level) implements BorderChangeListener {

        private void send(Packet<?> packet) {
            level.getServer().getPlayerList().broadcastAll(packet, level.dimension());
        }

        private void changed() {
            if (ready && !applying) {
                write(level);
                EdenLD.LOGGER.info("[World] The border of {} changed: {}.", WorldRules.id(level), describe(live(level)));
            }
        }

        @Override
        public void onBorderSizeSet(WorldBorder border, double size) {
            send(new ClientboundSetBorderSizePacket(border));
            changed();
        }

        @Override
        public void onBorderSizeLerping(WorldBorder border, double from, double to, long time) {
            send(new ClientboundSetBorderLerpSizePacket(border));
            changed();
        }

        @Override
        public void onBorderCenterSet(WorldBorder border, double x, double z) {
            send(new ClientboundSetBorderCenterPacket(border));
            changed();
        }

        @Override
        public void onBorderSetWarningTime(WorldBorder border, int time) {
            send(new ClientboundSetBorderWarningDelayPacket(border));
            changed();
        }

        @Override
        public void onBorderSetWarningBlocks(WorldBorder border, int blocks) {
            send(new ClientboundSetBorderWarningDistancePacket(border));
            changed();
        }

        @Override
        public void onBorderSetDamagePerBlock(WorldBorder border, double damage) {
            changed();
        }

        @Override
        public void onBorderSetDamageSafeZOne(WorldBorder border, double distance) {
            changed();
        }
    }
}
