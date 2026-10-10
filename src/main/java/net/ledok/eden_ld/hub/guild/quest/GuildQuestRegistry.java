package net.ledok.eden_ld.hub.guild.quest;

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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The guild quest pool from {@code data/<ns>/guild_quests/*.json} plus
 * {@code config/eden_ld/guild_quests/*.json} (id {@code eden_ld:config/<file>}), reloaded with
 * {@code /reload}. Parsed with registry access so item rewards may carry components.
 */
public final class GuildQuestRegistry {

    private static final String DIRECTORY = "guild_quests";
    private static volatile Map<ResourceLocation, GuildQuestDefinition> quests = Map.of();
    private static volatile Set<ResourceLocation> objectives = Set.of();

    private GuildQuestRegistry() {
    }

    public static void initialize() {
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath(EdenLD.MOD_ID, DIRECTORY);
        ResourceManagerHelper.get(PackType.SERVER_DATA).registerReloadListener(id, registries -> new Listener(id, registries));
    }

    /** The pool in a stable order (by id) — the seeded draw depends on it. */
    public static List<GuildQuestDefinition> all() {
        return List.copyOf(quests.values());
    }

    public static GuildQuestDefinition get(ResourceLocation id) {
        return id == null ? null : quests.get(id);
    }

    /** Whether any guild quest counts this objective type (cheap guard for busy hooks). */
    public static boolean usesObjective(ResourceLocation type) {
        return objectives.contains(type);
    }

    private static void loadConfigFolder(RegistryOps<com.google.gson.JsonElement> ops, List<GuildQuestDefinition> loaded) {
        Path folder = net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir()
                .resolve(EdenLD.MOD_ID).resolve(DIRECTORY);
        try {
            Files.createDirectories(folder);
            writeExample(folder);
            try (var files = Files.list(folder)) {
                for (Path file : files.filter(p -> p.toString().endsWith(".json")).sorted().toList()) {
                    String name = file.getFileName().toString();
                    String path = "config/" + name.substring(0, name.length() - ".json".length())
                            .toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9_./-]", "_");
                    parse(ops, file.toString(), Files.newBufferedReader(file),
                            ResourceLocation.fromNamespaceAndPath(EdenLD.MOD_ID, path), loaded);
                }
            }
        } catch (java.io.IOException e) {
            EdenLD.LOGGER.error("[Guild quests] Can't read the config quest folder {}.", folder, e);
        }
    }

    private static void parse(RegistryOps<com.google.gson.JsonElement> ops, String source, Reader open,
                              ResourceLocation questId, List<GuildQuestDefinition> loaded) {
        try (Reader reader = open) {
            GuildQuestDefinition.Body.CODEC.parse(ops, JsonParser.parseReader(reader))
                    .resultOrPartial(error -> EdenLD.LOGGER.error("[Guild quests] Skipping {}: {}", source, error))
                    .ifPresent(body -> loaded.add(body.withId(questId)));
        } catch (Exception e) {
            EdenLD.LOGGER.error("[Guild quests] Failed to read {}.", source, e);
        }
    }

    /** A sample next to the real files; ".example" keeps it out of the pool. */
    private static void writeExample(Path folder) throws java.io.IOException {
        Path example = folder.resolve("frost_mages.json.example");
        if (!Files.exists(example)) {
            Files.writeString(example, """
                    {
                      "period": "weekly",
                      "objective": "cast_spells",
                      "target": "wizards:frostbolt",
                      "count": 100,
                      "count_per_member": 10,
                      "icon": "minecraft:blue_ice",
                      "min_level": 3,
                      "rewards": {
                        "guild_xp": 500,
                        "lc": 5000,
                        "items": [{ "id": "economy_ld:silver_coin", "count": 5 }]
                      }
                    }
                    """);
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
            List<GuildQuestDefinition> loaded = new ArrayList<>();
            for (Map.Entry<ResourceLocation, Resource> entry
                    : manager.listResources(DIRECTORY, path -> path.getPath().endsWith(".json")).entrySet()) {
                ResourceLocation file = entry.getKey();
                String path = file.getPath();
                ResourceLocation questId = ResourceLocation.fromNamespaceAndPath(file.getNamespace(),
                        path.substring(DIRECTORY.length() + 1, path.length() - ".json".length()));
                try {
                    parse(ops, file.toString(), entry.getValue().openAsReader(), questId, loaded);
                } catch (java.io.IOException e) {
                    EdenLD.LOGGER.error("[Guild quests] Failed to read {}.", file, e);
                }
            }
            loadConfigFolder(ops, loaded);
            loaded.sort(Comparator.comparing(quest -> quest.id().toString()));
            Map<ResourceLocation, GuildQuestDefinition> byId = new LinkedHashMap<>();
            loaded.forEach(quest -> byId.put(quest.id(), quest));
            quests = byId;
            objectives = byId.values().stream().map(GuildQuestDefinition::objective).collect(Collectors.toUnmodifiableSet());
            EdenLD.LOGGER.info("[Guild quests] Loaded {} guild quest(s).", byId.size());
        }
    }
}
