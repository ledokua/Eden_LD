package net.ledok.eden_ld.hub.daily;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import net.fabricmc.fabric.api.resource.IdentifiableResourceReloadListener;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.ledok.eden_ld.EdenLD;
import net.minecraft.core.HolderLookup;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

import java.io.Reader;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The daily quest pool from {@code data/<ns>/daily_quests/*.json}, reloaded with
 * {@code /reload}. Parsed with registry access so item rewards may carry components
 * (enchantments, custom names).
 */
public final class DailyQuestRegistry {

    private static final String DIRECTORY = "daily_quests";
    private static volatile Map<ResourceLocation, DailyQuestDefinition> quests = Map.of();
    private static volatile java.util.Set<ResourceLocation> objectives = java.util.Set.of();

    private DailyQuestRegistry() {
    }

    public static void initialize() {
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath(EdenLD.MOD_ID, DIRECTORY);
        ResourceManagerHelper.get(PackType.SERVER_DATA).registerReloadListener(id, registries -> new Listener(id, registries));
    }

    /** The pool in a stable order (by id) — the daily draw depends on it. */
    public static List<DailyQuestDefinition> all() {
        return List.copyOf(quests.values());
    }

    /** Whether any quest in the pool counts this objective type (cheap guard for busy hooks). */
    public static boolean usesObjective(ResourceLocation type) {
        return objectives.contains(type);
    }

    public static DailyQuestDefinition get(ResourceLocation id) {
        return quests.get(id);
    }

    /**
     * Quests from {@code config/eden_ld/daily_quests/*.json} — same format as the datapack
     * files, for admins who'd rather not build a datapack. Id: {@code eden_ld:config/<file>}.
     * Also re-read on {@code /reload}.
     */
    private static void loadConfigFolder(RegistryOps<com.google.gson.JsonElement> ops, List<DailyQuestDefinition> loaded) {
        java.nio.file.Path folder = net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir()
                .resolve(EdenLD.MOD_ID).resolve(DIRECTORY);
        try {
            java.nio.file.Files.createDirectories(folder);
            writeExample(folder);
            try (var files = java.nio.file.Files.list(folder)) {
                for (java.nio.file.Path file : files.filter(p -> p.toString().endsWith(".json")).sorted().toList()) {
                    String name = file.getFileName().toString();
                    String path = "config/" + name.substring(0, name.length() - ".json".length())
                            .toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9_./-]", "_");
                    ResourceLocation questId = ResourceLocation.fromNamespaceAndPath(EdenLD.MOD_ID, path);
                    try (Reader reader = java.nio.file.Files.newBufferedReader(file)) {
                        DailyQuestDefinition.Body.CODEC.parse(ops, JsonParser.parseReader(reader))
                                .resultOrPartial(error -> EdenLD.LOGGER.error("[Dailies] Skipping {}: {}", file, error))
                                .ifPresent(body -> loaded.add(body.withId(questId)));
                    } catch (Exception e) {
                        EdenLD.LOGGER.error("[Dailies] Failed to read {}.", file, e);
                    }
                }
            }
        } catch (java.io.IOException e) {
            EdenLD.LOGGER.error("[Dailies] Can't read the config quest folder {}.", folder, e);
        }
    }

    /**
     * Samples next to the real files; ".example" keeps them out of the pool. One quest with
     * no name (the card shows the generated, auto-translated objective line) and one with a
     * server-translated name and reward note, plus matching lang tables.
     */
    private static void writeExample(java.nio.file.Path folder) throws java.io.IOException {
        writeIfMissing(folder.resolve("zombie_hunter.json.example"), """
                {
                  "objective": "kill_mobs",
                  "target": "minecraft:zombie",
                  "count": 20,
                  "icon": "minecraft:rotten_flesh",
                  "rewards": {
                    "items": [
                      { "id": "minecraft:iron_ingot", "count": 4 },
                      { "id": "economy_ld:bronze_coin", "count": 50 }
                    ]
                  }
                }
                """);
        writeIfMissing(folder.resolve("baker.json.example"), """
                {
                  "name": { "translate": "daily.example.baker" },
                  "objective": "craft_items",
                  "target": "minecraft:bread",
                  "count": 16,
                  "icon": "minecraft:bread",
                  "rewards": {
                    "items": [{ "id": "economy_ld:bronze_coin", "count": 38 }],
                    "commands": ["effect give {player} minecraft:saturation 60"],
                    "text": { "translate": "daily.example.baker.reward" }
                  }
                }
                """);
        java.nio.file.Path lang = folder.getParent().resolve("lang");
        java.nio.file.Files.createDirectories(lang);
        writeIfMissing(lang.resolve("en_us.json.example"), """
                {
                  "daily.example.baker": "The Village Baker",
                  "daily.example.baker.reward": "a minute of Saturation"
                }
                """);
        writeIfMissing(lang.resolve("uk_ua.json.example"), """
                {
                  "daily.example.baker": "Сільський пекар",
                  "daily.example.baker.reward": "хвилина Насичення"
                }
                """);
    }

    private static void writeIfMissing(java.nio.file.Path file, String content) throws java.io.IOException {
        if (!java.nio.file.Files.exists(file)) {
            java.nio.file.Files.writeString(file, content);
        }
    }

    private record Listener(ResourceLocation id, HolderLookup.Provider registries)
            implements SimpleSynchronousResourceReloadListener, IdentifiableResourceReloadListener {

        @Override
        public ResourceLocation getFabricId() {
            return id;
        }

        @Override
        public void onResourceManagerReload(ResourceManager manager) {
            RegistryOps<com.google.gson.JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, registries);
            List<DailyQuestDefinition> loaded = new ArrayList<>();
            for (Map.Entry<ResourceLocation, Resource> entry
                    : manager.listResources(DIRECTORY, path -> path.getPath().endsWith(".json")).entrySet()) {
                ResourceLocation file = entry.getKey();
                String path = file.getPath();
                ResourceLocation questId = ResourceLocation.fromNamespaceAndPath(file.getNamespace(),
                        path.substring(DIRECTORY.length() + 1, path.length() - ".json".length()));
                try (Reader reader = entry.getValue().openAsReader()) {
                    DailyQuestDefinition.Body.CODEC.parse(ops, JsonParser.parseReader(reader))
                            .resultOrPartial(error -> EdenLD.LOGGER.error("[Dailies] Skipping {}: {}", file, error))
                            .ifPresent(body -> loaded.add(body.withId(questId)));
                } catch (Exception e) {
                    EdenLD.LOGGER.error("[Dailies] Failed to read {}.", file, e);
                }
            }
            loadConfigFolder(ops, loaded);
            loaded.sort(Comparator.comparing(quest -> quest.id().toString()));
            Map<ResourceLocation, DailyQuestDefinition> byId = new LinkedHashMap<>();
            loaded.forEach(quest -> byId.put(quest.id(), quest));
            quests = byId;
            objectives = byId.values().stream().map(DailyQuestDefinition::objective)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            EdenLD.LOGGER.info("[Dailies] Loaded {} daily quest(s).", byId.size());
        }
    }
}
