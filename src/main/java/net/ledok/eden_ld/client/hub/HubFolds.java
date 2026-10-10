package net.ledok.eden_ld.client.hub;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.ledok.eden_ld.EdenLD;
import net.ledok.eden_ld.client.EdenParchment;
import net.ledok.vectorlib.client.canvas.widget.Collapsible;
import net.minecraft.network.chat.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Which hub list sections the player folded away (Friends' Offline, Waystones' Global…), kept on this
 * computer like the hub's size ({@link HubWindow}) — a display preference, not a server setting.
 */
final class HubFolds {

    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve(EdenLD.MOD_ID).resolve("hub_folds.json");

    private static boolean loaded;
    private static final Set<String> COLLAPSED = new LinkedHashSet<>();

    private HubFolds() {
    }

    /** A foldable section in the hub's heading look, opening as the player last left it. */
    static Collapsible section(Component title, String key) {
        return new Collapsible(title)
                .titleColor(EdenParchment.INK_DIM)
                .expanded(!collapsed(key))
                .onToggle(open -> set(key, !open));
    }

    static boolean collapsed(String key) {
        load();
        return COLLAPSED.contains(key);
    }

    static void set(String key, boolean collapsed) {
        load();
        boolean changed = collapsed ? COLLAPSED.add(key) : COLLAPSED.remove(key);
        if (!changed) {
            return;
        }
        JsonObject json = new JsonObject();
        JsonArray list = new JsonArray();
        COLLAPSED.forEach(list::add);
        json.add("collapsed", list);
        try {
            Files.createDirectories(FILE.getParent());
            Files.writeString(FILE, json.toString(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            EdenLD.LOGGER.warn("[Hub] Couldn't save the folded hub sections to {}.", FILE, e);
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
            if (json.has("collapsed") && json.get("collapsed").isJsonArray()) {
                for (JsonElement key : json.getAsJsonArray("collapsed")) {
                    COLLAPSED.add(key.getAsString());
                }
            }
        } catch (Exception e) {
            EdenLD.LOGGER.warn("[Hub] Couldn't read the folded hub sections from {}.", FILE, e);
        }
    }
}
