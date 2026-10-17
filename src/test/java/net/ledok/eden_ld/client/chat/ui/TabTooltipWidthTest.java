package net.ledok.eden_ld.client.chat.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.ledok.eden_ld.chat.ChatChannels.Channel;
import net.ledok.eden_ld.client.chat.model.ChatTabSpec;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The tab strip's tooltip lines fit the reference screen (426 GUI units: 1280×720 at GUI scale 3) in every language:
 * tooltips don't wrap, and one pushed against the screen's left edge starts 4 in, its frame 3 past the text. Measured
 * as vanilla's Font does, with its own bitmap glyphs (on the test classpath with the game).
 */
class TabTooltipWidthTest {

    private static final int SCREEN = 426;
    private static final int MAX = SCREEN - 4 - 3;
    /** A glyph the bitmap fonts lack comes from Unifont: counted wide. */
    private static final float UNKNOWN = 9;
    private static final String[] LANGUAGES = {"en_us", "uk_ua"};
    /** The lines the tooltips show as they are (no argument). */
    private static final String[] PLAIN = {"chat.eden_ld.tab.hint", "chat.eden_ld.tab.view_hint",
            "chat.eden_ld.tab.keys", "chat.eden_ld.pip.typing", "chat.eden_ld.pip.unseen",
            "chat.eden_ld.pip.switched_off"};
    /** The lines naming the channel typed into. */
    private static final String[] NAMING = {"chat.eden_ld.tab.all.desc", "chat.eden_ld.tab.system.desc"};

    /** Advances by code point: the first provider with the glyph wins, as in vanilla's font. */
    private static final Map<Integer, Float> ADVANCE = new HashMap<>();

    @BeforeAll
    static void loadVanillaFont() throws IOException {
        // font/default.json: include/space, then include/default (the bitmaps), then Unifont.
        provide("assets/minecraft/font/include/space.json");
        provide("assets/minecraft/font/include/default.json");
    }

    @Test
    void everyTooltipLineFitsTheReferenceScreen() {
        for (String language : LANGUAGES) {
            JsonObject lang = read("assets/eden_ld/lang/" + language + ".json").getAsJsonObject();
            for (String key : PLAIN) {
                fits(language, key, text(lang, key));
            }
            for (Channel channel : Channel.values()) {
                String name = text(lang, ChatTabSpec.of(channel).labelKey());
                for (String key : NAMING) {
                    fits(language, key + " (" + name + ")", text(lang, key).replace("%s", name));
                }
                fits(language, "chat.eden_ld.tab.once", text(lang, "chat.eden_ld.tab.once").replace("%s", channel.prefix));
            }
        }
    }

    private static void fits(String language, String what, String line) {
        int width = width(line);
        assertTrue(width <= MAX, language + " " + what + " is " + width + " wide, over " + MAX + ": \"" + line + "\"");
    }

    private static String text(JsonObject lang, String key) {
        JsonElement value = lang.get(key);
        assertNotNull(value, key);
        return value.getAsString();
    }

    /** Font.width: the advances summed, rounded up. */
    private static int width(String line) {
        float width = 0;
        for (int codePoint : line.codePoints().toArray()) {
            width += ADVANCE.getOrDefault(codePoint, UNKNOWN);
        }
        return (int) Math.ceil(width);
    }

    // ---------------------------------------------------------------- vanilla's font

    private static void provide(String path) throws IOException {
        for (JsonElement element : read(path).getAsJsonObject().getAsJsonArray("providers")) {
            JsonObject provider = element.getAsJsonObject();
            switch (provider.get("type").getAsString()) {
                case "space" -> provider.getAsJsonObject("advances").entrySet().forEach(entry ->
                        ADVANCE.putIfAbsent(entry.getKey().codePointAt(0), entry.getValue().getAsFloat()));
                case "bitmap" -> bitmap(provider);
                default -> {
                }
            }
        }
    }

    /** BitmapProvider: a glyph's advance is its inked width, scaled to the provider's height, plus 1. */
    private static void bitmap(JsonObject provider) throws IOException {
        String file = provider.get("file").getAsString().replace("minecraft:", "assets/minecraft/textures/");
        BufferedImage image;
        try (InputStream in = resource(file)) {
            image = ImageIO.read(in);
        }
        JsonArray rows = provider.getAsJsonArray("chars");
        int columns = rows.get(0).getAsString().codePointCount(0, rows.get(0).getAsString().length());
        int glyphW = image.getWidth() / columns;
        int glyphH = image.getHeight() / rows.size();
        float scale = (provider.has("height") ? provider.get("height").getAsInt() : 8) / (float) glyphH;
        for (int row = 0; row < rows.size(); row++) {
            int[] chars = rows.get(row).getAsString().codePoints().toArray();
            for (int column = 0; column < chars.length; column++) {
                if (chars[column] != 0 && !ADVANCE.containsKey(chars[column])) {
                    int inked = inkedWidth(image, column * glyphW, row * glyphH, glyphW, glyphH);
                    ADVANCE.put(chars[column], (float) ((int) (0.5f + inked * scale) + 1));
                }
            }
        }
    }

    /** The rightmost column with any alpha, plus 1 (0 for an empty glyph). */
    private static int inkedWidth(BufferedImage image, int x0, int y0, int w, int h) {
        for (int x = w - 1; x >= 0; x--) {
            for (int y = 0; y < h; y++) {
                if (image.getRGB(x0 + x, y0 + y) >>> 24 != 0) {
                    return x + 1;
                }
            }
        }
        return 0;
    }

    private static JsonElement read(String path) {
        try (Reader reader = new InputStreamReader(resource(path), StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader);
        } catch (IOException e) {
            throw new IllegalStateException(path, e);
        }
    }

    private static InputStream resource(String path) {
        InputStream in = TabTooltipWidthTest.class.getClassLoader().getResourceAsStream(path);
        assertNotNull(in, path + " on the test classpath");
        return in;
    }
}
