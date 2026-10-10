package net.ledok.eden_ld.hub.activity;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Every activity source (Arenas_LD when present, datapack files) behind one list. The first source
 * to report an id wins. What sources report is only what exists (id, type, name, difficulties);
 * whether it's shown, its card art and its levels come from {@link ActivitySettings} (the
 * Moderation window). Server thread.
 */
public final class Activities {

    private static final List<ActivitySource> SOURCES = new CopyOnWriteArrayList<>();
    private static volatile boolean changed = true;

    private Activities() {
    }

    public static void register(ActivitySource source) {
        SOURCES.add(source);
        changed();
    }

    /** A source's list changed: online players' unlocks are checked again soon. */
    public static void changed() {
        changed = true;
    }

    /** Whether something changed since the last call (and clears the flag). */
    static boolean takeChanged() {
        boolean was = changed;
        changed = false;
        return was;
    }

    /** Every activity, shown or hidden, with its settings applied (the Moderation window, the entry gate). */
    public static List<Activity> all(MinecraftServer server) {
        ActivitySettings settings = ActivitySettings.get(server);
        List<Activity> all = new ArrayList<>();
        Set<ResourceLocation> ids = new HashSet<>();
        for (ActivitySource source : SOURCES) {
            for (Activity activity : source.activities(server)) {
                if (ids.add(activity.id())) {
                    all.add(apply(activity, settings.setting(activity.id().toString())));
                }
            }
        }
        return all;
    }

    /** The activities shown in the hub (staff turned them on). */
    public static List<Activity> visible(MinecraftServer server) {
        ActivitySettings settings = ActivitySettings.get(server);
        List<Activity> visible = new ArrayList<>();
        for (Activity activity : all(server)) {
            ActivitySettings.Setting setting = settings.setting(activity.id().toString());
            if (setting != null && setting.visible()) {
                visible.add(activity);
            }
        }
        return visible;
    }

    @Nullable
    public static Activity get(MinecraftServer server, ResourceLocation id) {
        for (ActivitySource source : SOURCES) {
            for (Activity activity : source.activities(server)) {
                if (activity.id().equals(id)) {
                    return apply(activity, ActivitySettings.get(server).setting(id.toString()));
                }
            }
        }
        return null;
    }

    /** Which source has the activity ("Arenas_LD", "datapack"), for the Moderation window. */
    public static String sourceOf(MinecraftServer server, ResourceLocation id) {
        for (ActivitySource source : SOURCES) {
            if (source.activities(server).stream().anyMatch(activity -> activity.id().equals(id))) {
                return source.name();
            }
        }
        return "";
    }

    /** The source's activity with the staff's card art and levels over it. */
    private static Activity apply(Activity activity, @Nullable ActivitySettings.Setting setting) {
        if (setting == null) {
            return activity;
        }
        List<Activity.Difficulty> difficulties = new ArrayList<>();
        for (Activity.Difficulty difficulty : activity.difficulties()) {
            Integer level = setting.levels().get(difficulty.id());
            difficulties.add(level == null ? difficulty : new Activity.Difficulty(difficulty.id(), level));
        }
        ResourceLocation art = setting.cardArt().isBlank() ? activity.cardArt() : ResourceLocation.tryParse(setting.cardArt());
        return new Activity(activity.id(), activity.type(), activity.name(), List.copyOf(difficulties),
                setting.level() >= 0 ? setting.level() : activity.requiredLevel(), art);
    }

    /** Opens the activity through the source that has it. */
    static boolean open(ServerPlayer player, ResourceLocation id) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return false;
        }
        for (ActivitySource source : SOURCES) {
            if (source.activities(server).stream().anyMatch(activity -> activity.id().equals(id))) {
                return source.open(player, id);
            }
        }
        return false;
    }
}
