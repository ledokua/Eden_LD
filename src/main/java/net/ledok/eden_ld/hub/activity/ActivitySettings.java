package net.ledok.eden_ld.hub.activity;

import net.ledok.eden_ld.EdenData;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * How each activity shows in the hub, set in the Moderation window's Activities section (with
 * {@code eden_ld.admin.dungeons}): shown or hidden, its card art (a profile background id), and the
 * level each difficulty needs ({@code level} for activities without difficulties, like arenas).
 * An activity with no settings is hidden — new dungeons stay out of the hub until staff set them
 * up. Arenas_LD only reports what exists; everything hub-related lives here. Kept in the world's data.
 */
public final class ActivitySettings extends SavedData {

    private static final Factory<ActivitySettings> FACTORY = new Factory<>(ActivitySettings::new, ActivitySettings::load, null);
    public static final int MAX_LEVEL = 10_000;

    /**
     * {@code levels}: difficulty id → required level; a difficulty not in it falls back to what its
     * source reported (datapack files), else 0.
     */
    public record Setting(boolean visible, String cardArt, int level, Map<String, Integer> levels) {
        public static final Setting DEFAULT = new Setting(false, "", -1, Map.of());

        public Setting {
            cardArt = cardArt == null ? "" : cardArt;
            levels = Map.copyOf(levels);
        }
    }

    private final Map<String, Setting> settings = new LinkedHashMap<>();

    public static ActivitySettings get(MinecraftServer server) {
        return EdenData.get(server, FACTORY, "activity_settings");
    }

    /** The activity's settings, or null when staff never set it up (hidden, source defaults). */
    @Nullable
    public Setting setting(String id) {
        return settings.get(id);
    }

    public void put(String id, Setting setting) {
        settings.put(id, setting);
        setDirty();
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        CompoundTag all = new CompoundTag();
        settings.forEach((id, setting) -> {
            CompoundTag entry = new CompoundTag();
            entry.putBoolean("visible", setting.visible());
            entry.putString("card_art", setting.cardArt());
            entry.putInt("level", setting.level());
            CompoundTag levels = new CompoundTag();
            setting.levels().forEach(levels::putInt);
            entry.put("levels", levels);
            all.put(id, entry);
        });
        tag.put("activities", all);
        return tag;
    }

    private static ActivitySettings load(CompoundTag tag, HolderLookup.Provider registries) {
        ActivitySettings data = new ActivitySettings();
        CompoundTag all = tag.getCompound("activities");
        for (String id : all.getAllKeys()) {
            CompoundTag entry = all.getCompound(id);
            Map<String, Integer> levels = new HashMap<>();
            CompoundTag stored = entry.getCompound("levels");
            for (String difficulty : stored.getAllKeys()) {
                levels.put(difficulty, stored.getInt(difficulty));
            }
            data.settings.put(id, new Setting(entry.getBoolean("visible"), entry.getString("card_art"),
                    entry.contains("level") ? entry.getInt("level") : -1, levels));
        }
        return data;
    }
}
