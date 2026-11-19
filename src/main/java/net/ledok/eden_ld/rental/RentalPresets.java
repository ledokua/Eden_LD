package net.ledok.eden_ld.rental;

import net.ledok.eden_ld.compat.KitsCompat;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The rental loadouts: Kits_LD's hidden kits {@code falleneden:rental/<level>/<preset>} (LeDok, 2026-11-09). A
 * preset's class is its name up to the first "_" (paladin_holy, paladin_fire: paladin). For each class, a player may
 * pick all of that class's presets in the highest level folder at or below their level that has any: level 12 with
 * paladins in 10, a rogue in 5 and a warrior in 0 gets both paladins (10), the rogue (5) and the warrior (0). Server
 * thread.
 */
public final class RentalPresets {

    public static final String PREFIX = "falleneden:rental/";
    /** Lobbies ask about once a second per member: the kit list is read at most this often. */
    private static final long TTL_MS = 10_000L;

    /** Kit ids by level folder, ascending; each list sorted by id. */
    private static TreeMap<Integer, List<String>> byLevel = new TreeMap<>();
    private static long read;

    private RentalPresets() {
    }

    /** The kit ids a player of this level may pick, sorted by id; empty when there's none that low. */
    public static List<String> forLevel(int level) {
        refresh();
        java.util.Set<String> classes = new java.util.HashSet<>();
        List<String> picks = new ArrayList<>();
        // From the highest folder down: each class from the first folder that has it.
        for (Map.Entry<Integer, List<String>> folder : byLevel.headMap(level, true).descendingMap().entrySet()) {
            java.util.Set<String> here = new java.util.HashSet<>();
            for (String id : folder.getValue()) {
                String cls = classOf(id);
                if (!classes.contains(cls)) {
                    picks.add(id);
                    here.add(cls);
                }
            }
            classes.addAll(here);
        }
        picks.sort(null);
        return List.copyOf(picks);
    }

    /** A preset's class: its name up to the first "_". */
    public static String classOf(String kitId) {
        String preset = kitId.substring(kitId.lastIndexOf('/') + 1);
        int cut = preset.indexOf('_');
        return cut < 0 ? preset : preset.substring(0, cut);
    }

    /** The preset part of a kit id ("…/25/paladin_holy" → "paladin_holy"): what spell picks are saved under. */
    public static String presetName(String kitId) {
        return kitId.substring(Math.max(kitId.lastIndexOf('/'), kitId.lastIndexOf(':')) + 1);
    }

    /** A preset's level: its folder ("falleneden:rental/25/paladin_holy" → 25), or -1. */
    public static int kitLevel(@org.jetbrains.annotations.Nullable String kitId) {
        String[] parts = kitId == null ? new String[0] : kitId.split("/");
        if (parts.length < 2) {
            return -1;
        }
        try {
            return Integer.parseInt(parts[parts.length - 2]);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** The kit's own display name (/kit name), else its preset id. */
    public static Component displayName(String kitId) {
        ResourceLocation id = ResourceLocation.tryParse(kitId);
        Component name = id == null ? null : KitsCompat.displayName(id);
        // Kits_LD shows a kit without a name by its id: show the preset alone then.
        if (name == null || name.getString().contains("/")) {
            return Component.literal(kitId.substring(kitId.lastIndexOf('/') + 1));
        }
        return name;
    }

    private static void refresh() {
        long now = System.currentTimeMillis();
        if (now - read <= TTL_MS) {
            return;
        }
        TreeMap<Integer, List<String>> found = new TreeMap<>();
        for (ResourceLocation id : KitsCompat.hiddenKitIds(PREFIX)) {
            // falleneden:rental/<level>/<preset>
            String[] parts = id.getPath().split("/");
            if (parts.length != 3) {
                continue;
            }
            try {
                found.computeIfAbsent(Integer.parseInt(parts[1]), key -> new ArrayList<>()).add(id.toString());
            } catch (NumberFormatException ignored) {
                // Not a level folder: not a rental loadout.
            }
        }
        found.values().forEach(list -> list.sort(null));
        found.replaceAll((level, list) -> List.copyOf(list));
        byLevel = found;
        read = now;
    }
}
