package net.ledok.eden_ld.client.profile;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.NativeImage;
import net.fabricmc.fabric.api.resource.IdentifiableResourceReloadListener;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.ledok.eden_ld.EdenLD;
import net.ledok.eden_ld.hub.HubSettings;
import net.ledok.vectorlib.client.canvas.CanvasGroup;
import net.ledok.vectorlib.client.canvas.CanvasNode;
import net.ledok.vectorlib.client.canvas.ImageNode;
import net.ledok.vectorlib.client.canvas.Shapes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import org.jetbrains.annotations.Nullable;

import java.io.InputStream;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Profile backgrounds known to this client, defined like hub themes: one
 * {@code assets/<ns>/textures/gui/profile_backgrounds/<name>/background.json} each, next to its
 * pictures (id {@code <ns>:<name>}; resource packs add
 * more, F3+T reloads) —
 * <pre>{@code
 * {
 *   "name": { "translate": "profile_background.mypack.sakura" },   // or plain text
 *   "texture": "mypack:textures/gui/profile_backgrounds/sakura/background.png", // the picture (2:1)
 *   "card": "mypack:textures/gui/profile_backgrounds/sakura/card.png",       // Friends cards (4:1)
 *   "home_card": "mypack:textures/gui/profile_backgrounds/sakura/home.png",  // the Home card (8:1)
 *   "text_shadow": true,
 *   "page_darken": 0.22,   // over the picture on the profile page, 0..1
 *   "card_darken": 0.4,    // on the cards
 *   "palette": { "text": "#FFFFFF", "text_dim": "#C4BFD4", "text_faint": "#8E889E",
 *                "good": "#6EE07A", "accent": "#F0C070" }
 * }
 * }</pre>
 * All optional: the three images default to {@code textures/gui/profile_backgrounds/<name>/}
 * {@code background.png} (required), {@code card.png} and {@code home.png}. Without a card image the Friends cards show the picture's
 * middle, without {@code home_card} the Home card shows {@code card}'s (else the picture's), and
 * {@code name} defaults to the lang key {@code profile_background.<ns>.<name>}. The palette is for
 * text over the picture: light by default, dark text for a light picture. The server decides which
 * ones a player may pick; "" is no background, and one this client lacks shows as none.
 */
public final class ProfileBackgrounds {

    /** Each background is a folder here holding background.json and its pictures. */
    private static final String FOLDER = "textures/gui/profile_backgrounds";
    private static final String FILE = "background.json";

    /** Text colors over a background. */
    public record Palette(int text, int textDim, int textFaint, int good, int accent, boolean shadow) {
        static final Palette LIGHT = new Palette(0xFFFFFFFF, 0xFFC4BFD4, 0xFF8E889E, 0xFF6EE07A, 0xFFF0C070, true);
    }

    public record Background(ResourceLocation id, Component name, Image picture, @Nullable Image card,
                             @Nullable Image homeCard, float pageDarken, float cardDarken, Palette palette) {
    }

    /** A texture and its size (for cropping). */
    public record Image(ResourceLocation texture, int width, int height) {
    }

    private static volatile Map<ResourceLocation, Background> backgrounds = Map.of();

    private ProfileBackgrounds() {
    }

    public static void initialize() {
        ResourceManagerHelper.get(PackType.CLIENT_RESOURCES).registerReloadListener(new Listener());
    }

    /** Built-in ones in their order first, then the rest by id. */
    public static List<Background> all() {
        List<Background> list = new ArrayList<>(backgrounds.values());
        list.sort(Comparator.comparingInt((Background b) -> {
            int index = HubSettings.BUILT_IN_BACKGROUNDS.indexOf(b.id().toString());
            return index < 0 ? Integer.MAX_VALUE : index;
        }).thenComparing(b -> b.id().toString()));
        return list;
    }

    /** Whether there's a picture to draw: a background was picked and this client has it. */
    public static boolean shown(String id) {
        return !id.isEmpty() && get(id) != null;
    }

    @Nullable
    public static Background get(String id) {
        ResourceLocation location = ResourceLocation.tryParse(id);
        return location == null ? null : backgrounds.get(location);
    }

    /** Text colors over the background (light ones when it's unknown). */
    public static Palette palette(String id) {
        Background background = get(id);
        return background == null ? Palette.LIGHT : background.palette();
    }

    /** The profile page's picture over {@code w}×{@code h}, darkened by the background's {@code page_darken}. */
    public static CanvasNode page(String id, float w, float h) {
        Background background = get(id);
        return background == null ? new CanvasGroup()
                : node(background.picture(), w, h, background.pageDarken());
    }

    /** A Friends card's picture (the {@code card} image, else the picture's middle), darkened by {@code card_darken}. */
    public static CanvasNode card(String id, float w, float h) {
        Background background = get(id);
        return background == null ? new CanvasGroup()
                : node(background.card() != null ? background.card() : background.picture(), w, h, background.cardDarken());
    }

    /** The Home card's picture: {@code home_card}, else {@code card}, else the picture — darkened by {@code card_darken}. */
    public static CanvasNode homeCard(String id, float w, float h) {
        Background background = get(id);
        if (background == null) {
            return new CanvasGroup();
        }
        Image image = background.homeCard() != null ? background.homeCard()
                : background.card() != null ? background.card() : background.picture();
        return node(image, w, h, background.cardDarken());
    }

    /** The picture as it is (the picker's thumbnails), optionally dimmed. */
    public static CanvasNode thumbnail(String id, float w, float h, float dim) {
        Background background = get(id);
        return background == null ? new CanvasGroup() : node(background.picture(), w, h, dim);
    }

    /** The image scaled to cover {@code w}×{@code h} and centered (the overflow is cut), then darkened. */
    private static CanvasNode node(Image image, float w, float h, float darken) {
        CanvasGroup group = new CanvasGroup();
        if (w <= 0 || h <= 0) {
            return group;
        }
        float target = w / h;
        float source = (float) image.width() / image.height();
        float u = 0;
        float v = 0;
        float regionW = image.width();
        float regionH = image.height();
        if (source > target) {
            regionW = regionH * target;
            u = (image.width() - regionW) / 2;
        } else {
            regionH = regionW / target;
            v = (image.height() - regionH) / 2;
        }
        group.add(ImageNode.of(image.texture(), 0, 0, w, h, image.width(), image.height())
                .region(u, v, regionW, regionH));
        int alpha = Math.round(Math.max(0f, Math.min(1f, darken)) * 255);
        if (alpha > 0) {
            group.add(Shapes.rect(0, 0, w, h).fill(alpha << 24));
        }
        return group;
    }

    private static final class Listener implements SimpleSynchronousResourceReloadListener,
            IdentifiableResourceReloadListener {

        @Override
        public ResourceLocation getFabricId() {
            return ResourceLocation.fromNamespaceAndPath(EdenLD.MOD_ID, "profile_backgrounds");
        }

        @Override
        public void onResourceManagerReload(ResourceManager manager) {
            Map<ResourceLocation, Background> loaded = new LinkedHashMap<>();
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
                        Background background = read(manager, id, object);
                        if (background != null) {
                            loaded.put(id, background);
                        }
                    }
                } catch (Exception e) {
                    EdenLD.LOGGER.error("[Profile] Failed to read background {}.", file, e);
                }
            }
            backgrounds = Map.copyOf(loaded);
        }

        @Nullable
        private static Background read(ResourceManager manager, ResourceLocation id, JsonObject json) {
            // Default layout: textures/gui/profile_backgrounds/<name>/{background,card,home}.png.
            String folder = id.getNamespace() + ":" + FOLDER + "/" + id.getPath() + "/";
            Image picture = image(manager, json.has("texture") ? json.get("texture").getAsString()
                    : folder + "background.png", true);
            if (picture == null) {
                EdenLD.LOGGER.warn("[Profile] Background {} has no readable picture.", id);
                return null;
            }
            Image card = json.has("card") ? image(manager, json.get("card").getAsString(), true)
                    : image(manager, folder + "card.png", false);
            Image homeCard = json.has("home_card") ? image(manager, json.get("home_card").getAsString(), true)
                    : image(manager, folder + "home.png", false);
            Component name = json.has("name")
                    ? ComponentSerialization.CODEC.parse(com.mojang.serialization.JsonOps.INSTANCE, json.get("name"))
                            .result().orElse(Component.literal(id.getPath()))
                    : Component.translatable("profile_background." + id.getNamespace() + "." + id.getPath());
            Palette d = Palette.LIGHT;
            JsonObject colors = json.has("palette") && json.get("palette").isJsonObject()
                    ? json.getAsJsonObject("palette") : new JsonObject();
            Palette palette = new Palette(color(colors, "text", d.text()), color(colors, "text_dim", d.textDim()),
                    color(colors, "text_faint", d.textFaint()), color(colors, "good", d.good()),
                    color(colors, "accent", d.accent()),
                    !json.has("text_shadow") || json.get("text_shadow").getAsBoolean());
            float pageDarken = json.has("page_darken") ? json.get("page_darken").getAsFloat() : 0.22f;
            float cardDarken = json.has("card_darken") ? json.get("card_darken").getAsFloat() : 0.4f;
            return new Background(id, name, picture, card, homeCard, pageDarken, cardDarken, palette);
        }

        /** The texture's size, read from the resource; null when it's missing ({@code required}: say so) or unreadable. */
        @Nullable
        private static Image image(ResourceManager manager, String location, boolean required) {
            ResourceLocation texture = ResourceLocation.tryParse(location);
            Optional<Resource> resource = texture == null ? Optional.empty() : manager.getResource(texture);
            if (resource.isEmpty()) {
                if (required) {
                    EdenLD.LOGGER.warn("[Profile] Background texture {} not found.", location);
                }
                return null;
            }
            try (InputStream in = resource.get().open(); NativeImage image = NativeImage.read(in)) {
                return new Image(texture, image.getWidth(), image.getHeight());
            } catch (Exception e) {
                EdenLD.LOGGER.warn("[Profile] Can't read background texture {}: {}", location, e.toString());
                return null;
            }
        }

        /** "#RRGGBB" or "#AARRGGBB"; anything unreadable keeps the default. */
        private static int color(JsonObject json, String key, int fallback) {
            if (!json.has(key)) {
                return fallback;
            }
            String hex = json.get(key).getAsString().trim().replace("#", "");
            try {
                long value = Long.parseLong(hex, 16);
                return hex.length() <= 6 ? (int) (0xFF000000L | value) : (int) value;
            } catch (NumberFormatException e) {
                EdenLD.LOGGER.warn("[Profile] Bad color '{}' for '{}'.", hex, key);
                return fallback;
            }
        }
    }
}
