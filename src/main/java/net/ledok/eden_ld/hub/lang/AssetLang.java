package net.ledok.eden_ld.hub.lang;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.ledok.eden_ld.EdenLD;
import org.jetbrains.annotations.Nullable;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Eden's own client lang files ({@code assets/eden_ld/lang/<locale>.json}), read on the server
 * from the mod jar — for text the server writes once in every language, like a mail to someone
 * offline (whose language isn't known). Loaded on first use, kept.
 */
public final class AssetLang {

    /** The languages Eden ships, the first one the default. */
    public static final String[] LOCALES = {"en_us", "uk_ua"};

    private static final Map<String, Map<String, String>> TABLES = new HashMap<>();

    private AssetLang() {
    }

    /** The value of {@code key} in {@code locale} (else en_us), or null. */
    @Nullable
    public static String get(String locale, String key) {
        String value = table(locale).get(key);
        return value != null ? value : table(LOCALES[0]).get(key);
    }

    /** {@code key} filled with {@code args} ({@code %s} in order), or the key itself when unknown. */
    public static String format(String locale, String key, Object... args) {
        String value = get(locale, key);
        if (value == null) {
            return key;
        }
        try {
            return String.format(value, args);
        } catch (java.util.IllegalFormatException e) {
            return value;
        }
    }

    private static synchronized Map<String, String> table(String locale) {
        return TABLES.computeIfAbsent(locale, AssetLang::load);
    }

    private static Map<String, String> load(String locale) {
        Map<String, String> table = new HashMap<>();
        Optional<Path> file = FabricLoader.getInstance().getModContainer(EdenLD.MOD_ID)
                .flatMap(container -> container.findPath("assets/" + EdenLD.MOD_ID + "/lang/" + locale + ".json"));
        if (file.isEmpty()) {
            return table;
        }
        try (Reader reader = Files.newBufferedReader(file.get(), StandardCharsets.UTF_8)) {
            JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
            json.entrySet().forEach(entry -> table.put(entry.getKey(), entry.getValue().getAsString()));
        } catch (Exception e) {
            EdenLD.LOGGER.warn("[Lang] Couldn't read {} from the mod jar.", locale, e);
        }
        return table;
    }
}
