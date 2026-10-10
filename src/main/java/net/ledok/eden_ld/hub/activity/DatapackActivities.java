package net.ledok.eden_ld.hub.activity;

import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.ledok.eden_ld.EdenLD;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

import java.io.Reader;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Activities written as datapack files, {@code data/<ns>/hub_activities/<path>.json} (the path is
 * the id) — for content outside Arenas_LD and for testing. "Going there" runs the file's
 * {@code open_command} as the server ({@code {player}} = the name); without one it only says so.
 * <pre>{
 *   "type": "dungeon",
 *   "name": "Sunken Crypt",
 *   "difficulties": [{ "id": "easy", "level": 5 }, { "id": "normal", "level": 10 }],
 *   "card_art": "eden_ld:aurora",
 *   "open_command": "warp crypt {player}"
 * }</pre>
 */
public final class DatapackActivities implements ActivitySource {

    public static final DatapackActivities INSTANCE = new DatapackActivities();
    private static final String DIRECTORY = "hub_activities";

    private record Entry(Activity activity, Optional<String> openCommand) {
    }

    private record Body(Activity.Type type, Component name, List<Activity.Difficulty> difficulties, int level,
                        Optional<ResourceLocation> cardArt, Optional<String> openCommand) {
        private static final Codec<Activity.Difficulty> DIFFICULTY = RecordCodecBuilder.create(instance -> instance.group(
                Codec.STRING.fieldOf("id").forGetter(Activity.Difficulty::id),
                Codec.intRange(0, 100_000).optionalFieldOf("level", 0).forGetter(Activity.Difficulty::requiredLevel)
        ).apply(instance, Activity.Difficulty::new));

        static final Codec<Body> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Activity.Type.CODEC.optionalFieldOf("type", Activity.Type.DUNGEON).forGetter(Body::type),
                ComponentSerialization.CODEC.fieldOf("name").forGetter(Body::name),
                DIFFICULTY.listOf().optionalFieldOf("difficulties", List.of()).forGetter(Body::difficulties),
                Codec.intRange(0, 100_000).optionalFieldOf("level", 0).forGetter(Body::level),
                ResourceLocation.CODEC.optionalFieldOf("card_art").forGetter(Body::cardArt),
                Codec.STRING.optionalFieldOf("open_command").forGetter(Body::openCommand)
        ).apply(instance, Body::new));
    }

    private volatile List<Entry> entries = List.of();

    private DatapackActivities() {
    }

    public static void initialize() {
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath(EdenLD.MOD_ID, DIRECTORY);
        ResourceManagerHelper.get(PackType.SERVER_DATA).registerReloadListener(new SimpleSynchronousResourceReloadListener() {
            @Override
            public ResourceLocation getFabricId() {
                return id;
            }

            @Override
            public void onResourceManagerReload(ResourceManager manager) {
                INSTANCE.load(manager);
            }
        });
    }

    private void load(ResourceManager manager) {
        List<Entry> loaded = new ArrayList<>();
        for (Map.Entry<ResourceLocation, Resource> file
                : manager.listResources(DIRECTORY, path -> path.getPath().endsWith(".json")).entrySet()) {
            String path = file.getKey().getPath();
            ResourceLocation activityId = ResourceLocation.fromNamespaceAndPath(file.getKey().getNamespace(),
                    path.substring(DIRECTORY.length() + 1, path.length() - ".json".length()));
            try (Reader reader = file.getValue().openAsReader()) {
                Body.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseReader(reader))
                        .resultOrPartial(error -> EdenLD.LOGGER.error("[Activities] Skipping {}: {}", file.getKey(), error))
                        .ifPresent(body -> loaded.add(new Entry(new Activity(activityId, body.type(), body.name(),
                                List.copyOf(body.difficulties()), body.level(), body.cardArt().orElse(null)),
                                body.openCommand())));
            } catch (Exception e) {
                EdenLD.LOGGER.error("[Activities] Failed to read {}.", file.getKey(), e);
            }
        }
        loaded.sort(Comparator.comparing(entry -> entry.activity().id().toString()));
        entries = List.copyOf(loaded);
        EdenLD.LOGGER.info("[Activities] Loaded {} datapack activit{}.", loaded.size(), loaded.size() == 1 ? "y" : "ies");
        Activities.changed();
    }

    @Override
    public List<Activity> activities(MinecraftServer server) {
        return entries.stream().map(Entry::activity).toList();
    }

    @Override
    public String name() {
        return "datapack";
    }

    @Override
    public boolean open(ServerPlayer player, ResourceLocation activity) {
        for (Entry entry : entries) {
            if (entry.activity().id().equals(activity)) {
                if (entry.openCommand().isEmpty()) {
                    player.sendSystemMessage(Component.translatable("message.eden_ld.activities.no_lobby",
                            entry.activity().name()));
                    return true;
                }
                MinecraftServer server = player.getServer();
                if (server != null) {
                    server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),
                            entry.openCommand().get().replace("{player}", player.getGameProfile().getName()));
                }
                return true;
            }
        }
        return false;
    }
}
