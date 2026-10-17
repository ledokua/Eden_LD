package net.ledok.eden_ld.client.theme;

import com.google.gson.JsonObject;
import net.ledok.eden_ld.EdenLD;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * A hub look, from {@code assets/<ns>/textures/gui/themes/<name>/theme.json} (id {@code <ns>:<name>};
 * its textures default to that folder) — a
 * resource pack can add one without code:
 * <pre>{@code
 * {
 *   "name": { "translate": "hub_theme.mypack.frost" },   // or plain text
 *   "textures": "mypack:textures/gui/themes/frost/",     // panel.png (48×48), button_normal/
 *                                                         // button_hover/button_disabled.png (32×16)
 *   "text_shadow": true,
 *   "palette": { "panel": "#1E2A33", "ink": "#E6F0F5", ... },
 *   "background": { "texture": "pattern.png", "mode": "tile", "tile": 32, "opacity": 0.5 },
 *   "rows": { "texture": "row.png", "alt": "row_alt.png", "border": 4 },
 *   "tile_edges": true,                                    // panel/row edges repeat, not stretch
 *   "decorations": [ { "texture": "vines.png", "anchor": "top_left", "x": -14, "y": -6,
 *                      "width": 28, "height": 180, "layer": "front" } ],
 *   "chat": { "glass": "auto", "plate": "#1D272B", "hover": "#1CFFFFFF", ... }  // the chat's colors (ChatTheme)
 * }
 * }</pre>
 * Texture names without a namespace are files next to theme.json.
 * Everything is optional; missing values keep the parchment look.
 */
public record HubTheme(ResourceLocation id, Component name, ResourceLocation textures, boolean textShadow,
                       Palette palette, @Nullable Background background, @Nullable Rows rows,
                       List<Decoration> decorations, boolean tileEdges, Chat chat) {

    /** A texture and its pixel size. */
    public record Image(ResourceLocation texture, int width, int height) {
    }

    /**
     * A picture over the panel's inside: {@code tile} repeats it every {@code tileWidth} ×
     * {@code tileHeight} hub units (patterns), {@code stretch} fills the panel with it once.
     * {@code opacity} 0..1 lets the panel color through.
     */
    public record Background(Image image, boolean tile, float tileWidth, float tileHeight, float opacity) {
    }

    /** Nine-slice textures for list rows and cards instead of the flat {@code row_bg}/{@code row_bg_alt} fills. */
    public record Rows(Image image, @Nullable Image alt, int border) {
    }

    /**
     * A picture pinned to the hub panel (the area under the tabs): its {@code anchor} point —
     * {@code top_left}, {@code top}, {@code top_right}, {@code left}, {@code center}, {@code right},
     * {@code bottom_left}, {@code bottom}, {@code bottom_right} — sits on the panel's same point,
     * moved by {@code x}/{@code y}; it may reach outside the panel. {@code front}: over the hub
     * (never clickable), else behind it.
     */
    public record Decoration(Image image, String anchor, float x, float y, float width, float height, boolean front) {
    }

    public static final ResourceLocation PARCHMENT_ID = ResourceLocation.fromNamespaceAndPath(EdenLD.MOD_ID, "parchment");

    /** Every color the hub draws with; keys in JSON are the snake_case names. */
    public record Palette(int panel, int panel2, int hairline, int hairlineHi, int rowBg, int rowBgAlt,
                          int ink, int inkMid, int inkDim, int accent, int good, int warn, int danger,
                          int barTrack, int barFill, int buttonText, int buttonTextHover,
                          int buttonTextDisabled, int panelTint, int notification) {

        public static final Palette PARCHMENT = new Palette(
                0xFFE5D8B9, 0xFFDACAA5, 0xFF5A4632, 0xFF3B2612, 0xFFD8C69E, 0xFFD2BF94,
                0xFF2B1B0E, 0xFF4A2E16, 0xFF8A7A60, 0xFF7A4A1E, 0xFF3E6B2F, 0xFF9A6A10, 0xFF8B2F23,
                0xFFBBA47A, 0xFF6E9A4A, 0xFF3B2612, 0xFF000000, 0xFF7A7060, 0xFFE4E4E4, 0xFFD8392B);

        static Palette read(JsonObject json) {
            Palette d = PARCHMENT;
            return new Palette(
                    color(json, "panel", d.panel), color(json, "panel_2", d.panel2),
                    color(json, "hairline", d.hairline), color(json, "hairline_hi", d.hairlineHi),
                    color(json, "row_bg", d.rowBg), color(json, "row_bg_alt", d.rowBgAlt),
                    color(json, "ink", d.ink), color(json, "ink_mid", d.inkMid), color(json, "ink_dim", d.inkDim),
                    color(json, "accent", d.accent), color(json, "good", d.good),
                    color(json, "warn", d.warn), color(json, "danger", d.danger),
                    color(json, "bar_track", d.barTrack), color(json, "bar_fill", d.barFill),
                    color(json, "button_text", d.buttonText), color(json, "button_text_hover", d.buttonTextHover),
                    color(json, "button_text_disabled", d.buttonTextDisabled),
                    color(json, "panel_tint", d.panelTint), color(json, "notification", d.notification));
        }

        /** "#RRGGBB" or "#AARRGGBB"; anything unreadable keeps the default. */
        private static int color(JsonObject json, String key, int fallback) {
            Integer value = parse(json, key);
            return value != null ? value : fallback;
        }

        /** {@code key}'s color ({@code #RRGGBB} opaque, {@code #AARRGGBB}), or null: missing, or not a color (logged). */
        @org.jetbrains.annotations.Nullable
        static Integer parse(JsonObject json, String key) {
            if (!json.has(key)) {
                return null;
            }
            String hex = json.get(key).getAsString().trim().replace("#", "");
            try {
                long value = Long.parseLong(hex, 16);
                return hex.length() <= 6 ? (int) (0xFF000000L | value) : (int) value;
            } catch (NumberFormatException e) {
                EdenLD.LOGGER.warn("[Themes] Bad color '{}' for '{}'.", hex, key);
                return null;
            }
        }
    }

    /**
     * The chat's own colors ({@code ChatTheme}), each optional: {@code plate}, {@code plate_input}, {@code border},
     * {@code focus_border}, {@code ink}, {@code ink_dim}, {@code hover}, {@code unread}, {@code accent}. Missing ones are
     * derived from the palette. {@code glass}: {@code auto} (a light panel gets dark glass so server colors stay
     * readable), {@code on}, {@code off}.
     */
    public record Chat(java.util.Map<String, Integer> colors, Glass glass) {

        public enum Glass { AUTO, ON, OFF }

        public static final Chat NONE = new Chat(java.util.Map.of(), Glass.AUTO);
        private static final List<String> KEYS = List.of("plate", "plate_input", "border", "focus_border", "ink",
                "ink_dim", "hover", "unread", "accent");

        static Chat read(JsonObject json) {
            java.util.Map<String, Integer> colors = new java.util.HashMap<>();
            for (String key : KEYS) {
                // A bad value keeps the derived color (it's logged).
                Integer value = Palette.parse(json, key);
                if (value != null) {
                    colors.put(key, value);
                }
            }
            Glass glass = switch (json.has("glass") ? json.get("glass").getAsString() : "auto") {
                case "on", "dark" -> Glass.ON;
                case "off" -> Glass.OFF;
                default -> Glass.AUTO;
            };
            return new Chat(java.util.Map.copyOf(colors), glass);
        }

        /** The theme's own value for a chat color, or {@code fallback}. */
        public int color(String key, int fallback) {
            Integer value = colors.get(key);
            return value != null ? value : fallback;
        }
    }

    public static final HubTheme PARCHMENT = new HubTheme(PARCHMENT_ID,
            Component.translatable("hub_theme.eden_ld.parchment"),
            ResourceLocation.fromNamespaceAndPath(EdenLD.MOD_ID, "textures/gui/themes/parchment/"), false, Palette.PARCHMENT,
            null, null, List.of(), false, Chat.NONE);
}
