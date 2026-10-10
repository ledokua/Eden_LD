package net.ledok.eden_ld.hub.lang;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.resource.IdentifiableResourceReloadListener;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.fabricmc.loader.api.FabricLoader;
import net.ledok.eden_ld.EdenLD;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import org.jetbrains.annotations.Nullable;

import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Server-held translations for server-authored text (daily quest names, reward notes, …), so
 * a two-language server needs no client resource pack — the same idea as NPCs_LD's npc_lang
 * and FTB Quests' server translations. Tables per locale, merged:
 * <ul>
 *   <li>datapacks: {@code data/<ns>/eden_lang/<locale>.json};</li>
 *   <li>{@code config/eden_ld/lang/<locale>.json} (wins over datapacks), re-read on {@code /reload}.</li>
 * </ul>
 * Authored text uses ordinary translate components ({@code {"translate": "my.key"}});
 * {@link #localize} fills in the value for each player's own client language (falling back to
 * en_us) as the component's fallback text — a client resource pack that knows the key still
 * wins, everyone else reads the server's table.
 */
public final class ServerLang {

    private static final String FOLDER = "eden_lang";
    private static final String FALLBACK = "en_us";

    private static volatile Map<String, Map<String, String>> tables = Map.of();

    private ServerLang() {
    }

    public static void initialize() {
        ResourceManagerHelper.get(PackType.SERVER_DATA).registerReloadListener(new Listener());
    }

    /**
     * The value for {@code key} in the player's language — their hub language when they picked
     * one (Settings → Visual), else their client's — else en_us, else null.
     */
    @Nullable
    public static String lookup(ServerPlayer player, String key) {
        Map<String, Map<String, String>> current = tables;
        String hub = net.ledok.eden_ld.hub.HubSettings.hubLanguage(player.server, player.getUUID());
        String locale = (hub.isEmpty() ? player.clientInformation().language() : hub).toLowerCase(Locale.ROOT);
        Map<String, String> table = current.get(locale);
        String value = table == null ? null : table.get(key);
        if (value == null) {
            Map<String, String> fallback = current.get(FALLBACK);
            value = fallback == null ? null : fallback.get(key);
        }
        return value;
    }

    /**
     * A copy of {@code text} where every translate component the server knows carries this
     * player's translation as its fallback (arguments and siblings localized too).
     */
    public static Component localize(ServerPlayer player, Component text) {
        if (tables.isEmpty()) {
            return text;
        }
        MutableComponent copy;
        if (text.getContents() instanceof TranslatableContents translatable) {
            Object[] args = translatable.getArgs().clone();
            for (int i = 0; i < args.length; i++) {
                if (args[i] instanceof Component component) {
                    args[i] = localize(player, component);
                }
            }
            String value = lookup(player, translatable.getKey());
            String fallback = value != null ? value : translatable.getFallback();
            copy = Component.translatableWithFallback(translatable.getKey(), fallback, args);
        } else {
            copy = text.plainCopy();
        }
        copy.setStyle(text.getStyle());
        for (Component sibling : text.getSiblings()) {
            copy.append(localize(player, sibling));
        }
        return copy;
    }

    private static final class Listener implements SimpleSynchronousResourceReloadListener,
            IdentifiableResourceReloadListener {

        @Override
        public ResourceLocation getFabricId() {
            return ResourceLocation.fromNamespaceAndPath(EdenLD.MOD_ID, FOLDER);
        }

        @Override
        public void onResourceManagerReload(ResourceManager manager) {
            Map<String, Map<String, String>> loaded = new HashMap<>();
            for (Map.Entry<ResourceLocation, Resource> entry
                    : manager.listResources(FOLDER, path -> path.getPath().endsWith(".json")).entrySet()) {
                String path = entry.getKey().getPath();
                String locale = path.substring(path.lastIndexOf('/') + 1, path.length() - ".json".length());
                try (Reader reader = entry.getValue().openAsReader()) {
                    readInto(loaded, locale, JsonParser.parseReader(reader));
                } catch (Exception e) {
                    EdenLD.LOGGER.error("[Lang] Failed to read {}.", entry.getKey(), e);
                }
            }
            Path folder = FabricLoader.getInstance().getConfigDir().resolve(EdenLD.MOD_ID).resolve("lang");
            try {
                Files.createDirectories(folder);
                try (var files = Files.list(folder)) {
                    for (Path file : files.filter(p -> p.toString().endsWith(".json")).toList()) {
                        String name = file.getFileName().toString();
                        try (Reader reader = Files.newBufferedReader(file)) {
                            readInto(loaded, name.substring(0, name.length() - ".json".length()),
                                    JsonParser.parseReader(reader));
                        } catch (Exception e) {
                            EdenLD.LOGGER.error("[Lang] Failed to read {}.", file, e);
                        }
                    }
                }
            } catch (java.io.IOException e) {
                EdenLD.LOGGER.error("[Lang] Can't read {}.", folder, e);
            }
            tables = Map.copyOf(loaded);
            if (!loaded.isEmpty()) {
                EdenLD.LOGGER.info("[Lang] Server translations for {} locale(s).", loaded.size());
            }
        }

        private static void readInto(Map<String, Map<String, String>> out, String locale, JsonElement json) {
            if (!(json instanceof JsonObject object)) {
                return;
            }
            Map<String, String> table = out.computeIfAbsent(locale.toLowerCase(Locale.ROOT), l -> new HashMap<>());
            object.entrySet().forEach(e -> {
                if (e.getValue().isJsonPrimitive()) {
                    table.put(e.getKey(), e.getValue().getAsString());
                }
            });
        }
    }
}
