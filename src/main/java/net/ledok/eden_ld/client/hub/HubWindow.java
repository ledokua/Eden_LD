package net.ledok.eden_ld.client.hub;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.ledok.eden_ld.EdenLD;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The hub's size on this computer: what the player dragged it to, and whether it's maximized. A display
 * setting (it depends on the game window and GUI scale), so it lives in this client's config folder rather
 * than in the server-side hub settings.
 */
final class HubWindow {

    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve(EdenLD.MOD_ID).resolve("hub_window.json");

    private static boolean loaded;
    private static float width = -1;
    private static float height = -1;
    private static boolean maximized;

    private HubWindow() {
    }

    /** The chosen width, or -1 when the player never resized the hub. */
    static float width() {
        load();
        return width;
    }

    static float height() {
        load();
        return height;
    }

    static boolean maximized() {
        load();
        return maximized;
    }

    static void save(float newWidth, float newHeight, boolean newMaximized) {
        load();
        if (newWidth == width && newHeight == height && newMaximized == maximized) {
            return;
        }
        width = newWidth;
        height = newHeight;
        maximized = newMaximized;
        JsonObject json = new JsonObject();
        json.addProperty("width", width);
        json.addProperty("height", height);
        json.addProperty("maximized", maximized);
        try {
            Files.createDirectories(FILE.getParent());
            Files.writeString(FILE, json.toString(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            EdenLD.LOGGER.warn("[Hub] Couldn't save the hub size to {}.", FILE, e);
        }
    }

    private static void load() {
        if (loaded) {
            return;
        }
        loaded = true;
        if (!Files.isRegularFile(FILE)) {
            return;
        }
        try {
            JsonObject json = JsonParser.parseString(Files.readString(FILE, StandardCharsets.UTF_8)).getAsJsonObject();
            width = json.has("width") ? json.get("width").getAsFloat() : -1;
            height = json.has("height") ? json.get("height").getAsFloat() : -1;
            maximized = json.has("maximized") && json.get("maximized").getAsBoolean();
        } catch (Exception e) {
            EdenLD.LOGGER.warn("[Hub] Couldn't read the hub size from {}; using the default.", FILE, e);
        }
    }
}
