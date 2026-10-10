package net.ledok.eden_ld.client.theme;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.resource.IdentifiableResourceReloadListener;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.ledok.eden_ld.EdenLD;
import net.ledok.eden_ld.client.EdenParchment;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

import java.io.Reader;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Hub themes known to this client (resource packs, reloaded with F3+T), and the one in use.
 * The server decides which themes a player has unlocked and which one they picked
 * ({@code SettingsData}); a picked theme this client doesn't have falls back to parchment.
 */
public final class HubThemes {

    /** Each theme is a folder here holding theme.json and its textures. */
    private static final String FOLDER = "textures/gui/themes";
    private static final String FILE = "theme.json";
    private static volatile Map<ResourceLocation, HubTheme> themes = Map.of(HubTheme.PARCHMENT_ID, HubTheme.PARCHMENT);
    private static ResourceLocation selected = HubTheme.PARCHMENT_ID;

    private HubThemes() {
    }

    public static void initialize() {
        ResourceManagerHelper.get(PackType.CLIENT_RESOURCES).registerReloadListener(new Listener());
    }

    public static List<HubTheme> all() {
        return List.copyOf(themes.values());
    }

    public static HubTheme get(ResourceLocation id) {
        return themes.get(id);
    }

    public static ResourceLocation selected() {
        return selected;
    }

    /** Applies the server-chosen theme (parchment when this client lacks it). True when the look changed. */
    public static boolean select(ResourceLocation id) {
        HubTheme theme = themes.getOrDefault(id, HubTheme.PARCHMENT);
        boolean changed = !theme.id().equals(selected);
        selected = theme.id();
        EdenParchment.apply(theme);
        return changed;
    }

    private static final class Listener implements SimpleSynchronousResourceReloadListener,
            IdentifiableResourceReloadListener {

        @Override
        public ResourceLocation getFabricId() {
            return ResourceLocation.fromNamespaceAndPath(EdenLD.MOD_ID, "hub_themes");
        }

        @Override
        public void onResourceManagerReload(ResourceManager manager) {
            Map<ResourceLocation, HubTheme> loaded = new LinkedHashMap<>();
            loaded.put(HubTheme.PARCHMENT_ID, HubTheme.PARCHMENT);
            for (Map.Entry<ResourceLocation, Resource> entry
                    : manager.listResources(FOLDER, path -> path.getPath().endsWith("/" + FILE)).entrySet()) {
                ResourceLocation file = entry.getKey();
                String path = file.getPath();
                String name = path.substring(FOLDER.length() + 1, path.length() - FILE.length() - 1);
                if (name.contains("/")) {
                    continue;
                }
                ResourceLocation id = ResourceLocation.fromNamespaceAndPath(file.getNamespace(), name);
                try (Reader reader = entry.getValue().openAsReader()) {
                    JsonElement json = JsonParser.parseReader(reader);
                    if (json instanceof JsonObject object) {
                        loaded.put(id, read(manager, id, object));
                    }
                } catch (Exception e) {
                    EdenLD.LOGGER.error("[Themes] Failed to read {}.", file, e);
                }
            }
            themes = Map.copyOf(loaded);
            // Re-apply: a reload may have changed the active theme's file.
            select(selected);
        }

        private static HubTheme read(ResourceManager manager, ResourceLocation id, JsonObject json) {
            Component name = json.has("name")
                    ? ComponentSerialization.CODEC.parse(com.mojang.serialization.JsonOps.INSTANCE, json.get("name"))
                            .result().orElse(Component.literal(id.getPath()))
                    : Component.translatable("hub_theme." + id.getNamespace() + "." + id.getPath());
            // Textures sit next to theme.json unless it points elsewhere.
            ResourceLocation textures = json.has("textures")
                    ? ResourceLocation.parse(json.get("textures").getAsString())
                    : ResourceLocation.fromNamespaceAndPath(id.getNamespace(), FOLDER + "/" + id.getPath() + "/");
            boolean shadow = json.has("text_shadow") && json.get("text_shadow").getAsBoolean();
            HubTheme.Palette palette = json.has("palette") && json.get("palette").isJsonObject()
                    ? HubTheme.Palette.read(json.getAsJsonObject("palette"))
                    : HubTheme.Palette.PARCHMENT;
            HubTheme.Background background = null;
            if (json.has("background") && json.get("background").isJsonObject()) {
                JsonObject b = json.getAsJsonObject("background");
                HubTheme.Image image = image(manager, textures, b, "texture");
                if (image != null) {
                    float tile = b.has("tile") ? b.get("tile").getAsFloat() : image.width();
                    float tileHeight = b.has("tile_height") ? b.get("tile_height").getAsFloat() : tile * image.height() / image.width();
                    background = new HubTheme.Background(image, !"stretch".equals(string(b, "mode", "tile")),
                            Math.max(1, tile), Math.max(1, tileHeight),
                            b.has("opacity") ? Math.max(0, Math.min(1, b.get("opacity").getAsFloat())) : 1f);
                }
            }
            HubTheme.Rows rows = null;
            if (json.has("rows") && json.get("rows").isJsonObject()) {
                JsonObject r = json.getAsJsonObject("rows");
                HubTheme.Image image = image(manager, textures, r, "texture");
                if (image != null) {
                    rows = new HubTheme.Rows(image, image(manager, textures, r, "alt"),
                            r.has("border") ? Math.max(0, r.get("border").getAsInt()) : 4);
                }
            }
            List<HubTheme.Decoration> decorations = new java.util.ArrayList<>();
            if (json.has("decorations") && json.get("decorations").isJsonArray()) {
                for (JsonElement element : json.getAsJsonArray("decorations")) {
                    if (!(element instanceof JsonObject d)) {
                        continue;
                    }
                    HubTheme.Image image = image(manager, textures, d, "texture");
                    if (image == null) {
                        continue;
                    }
                    float w = d.has("width") ? d.get("width").getAsFloat() : image.width();
                    float h = d.has("height") ? d.get("height").getAsFloat() : w * image.height() / image.width();
                    decorations.add(new HubTheme.Decoration(image, string(d, "anchor", "top_left"),
                            d.has("x") ? d.get("x").getAsFloat() : 0, d.has("y") ? d.get("y").getAsFloat() : 0,
                            w, h, !"behind".equals(string(d, "layer", "front"))));
                }
            }
            boolean tileEdges = json.has("tile_edges") && json.get("tile_edges").getAsBoolean();
            return new HubTheme(id, name, textures, shadow, palette, background, rows, List.copyOf(decorations), tileEdges);
        }

        private static String string(JsonObject json, String key, String fallback) {
            return json.has(key) ? json.get(key).getAsString() : fallback;
        }

        /**
         * A texture named in the theme ("pattern.png" next to theme.json, or a full "ns:path"), with
         * its pixel size read from the file; null (and a warning) when it's missing.
         */
        @org.jetbrains.annotations.Nullable
        private static HubTheme.Image image(ResourceManager manager, ResourceLocation folder, JsonObject json, String key) {
            if (!json.has(key)) {
                return null;
            }
            String name = json.get(key).getAsString();
            ResourceLocation texture = name.contains(":") ? ResourceLocation.tryParse(name)
                    : ResourceLocation.fromNamespaceAndPath(folder.getNamespace(), folder.getPath() + name);
            if (texture == null) {
                return null;
            }
            var resource = manager.getResource(texture);
            if (resource.isEmpty()) {
                EdenLD.LOGGER.warn("[Themes] Missing texture {}.", texture);
                return null;
            }
            try (java.io.InputStream in = resource.get().open();
                 com.mojang.blaze3d.platform.NativeImage image = com.mojang.blaze3d.platform.NativeImage.read(in)) {
                return new HubTheme.Image(texture, image.getWidth(), image.getHeight());
            } catch (java.io.IOException e) {
                EdenLD.LOGGER.warn("[Themes] Can't read {}.", texture, e);
                return null;
            }
        }
    }
}
