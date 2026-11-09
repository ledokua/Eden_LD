package net.ledok.eden_ld.compat.arenas;

import net.ledok.arenas_ld.api.ArenasApi;
import net.ledok.eden_ld.api.rental.EdenRentalApi;
import net.ledok.eden_ld.compat.KitsCompat;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Rental builds in Arenas runs (Arenas 4.26.0+): Eden's stash behind Arenas' rental provider. Loadouts are Kits_LD's
 * hidden kits {@code falleneden:rental/<class>/<level>}. The only class touching the 4.26.0 types; registered only
 * when the running Arenas_LD has them.
 */
final class ArenasRental implements ArenasApi.RentalProvider {

    private static final String PREFIX = "falleneden:rental/";
    /** Arenas asks for the classes once a second per lobby member: the kit list is read at most this often. */
    private static final long CLASSES_TTL_MS = 10_000L;

    /** Each class's kit levels, ascending (from the kit ids). */
    private Map<String, int[]> levels = Map.of();
    private List<String> classes = List.of();
    private long read;

    private ArenasRental() {
    }

    static void register() {
        ArenasApi.setRentalProvider(new ArenasRental());
    }

    /** Re-reads the kit list at most every {@link #CLASSES_TTL_MS}. */
    private void refresh() {
        long now = System.currentTimeMillis();
        if (now - read <= CLASSES_TTL_MS) {
            return;
        }
        Map<String, List<Integer>> found = new LinkedHashMap<>();
        for (ResourceLocation id : KitsCompat.hiddenKitIds(PREFIX)) {
            // falleneden:rental/<class>/<level>
            String[] parts = id.getPath().split("/");
            if (parts.length != 3) {
                continue;
            }
            try {
                found.computeIfAbsent(parts[1], key -> new ArrayList<>()).add(Integer.parseInt(parts[2]));
            } catch (NumberFormatException ignored) {
                // Not a level: not a rental loadout.
            }
        }
        Map<String, int[]> sorted = new LinkedHashMap<>();
        found.forEach((cls, list) -> sorted.put(cls, list.stream().mapToInt(Integer::intValue).sorted().toArray()));
        levels = sorted;
        classes = List.copyOf(sorted.keySet());
        read = now;
    }

    @Override
    public List<String> classes() {
        refresh();
        return classes;
    }

    /**
     * The class's kit for a player of this level: the highest {@code falleneden:rental/<class>/<N>} with N at or below
     * the level (LeDok, 2026-11-09: kits 0/5/10/15 → level 12 gets 10, level 50 gets 15, level 0 gets 0). Null when
     * the class has none that low.
     */
    @Nullable
    @Override
    public String kitFor(String playerClass, int level) {
        refresh();
        int[] kits = levels.get(playerClass);
        if (kits == null) {
            return null;
        }
        int best = -1;
        for (int kit : kits) {
            if (kit <= level) {
                best = kit;
            }
        }
        return best < 0 ? null : PREFIX + playerClass + "/" + best;
    }

    @Override
    public boolean begin(ServerPlayer player, String sessionId, String kitId) {
        ResourceLocation kit = ResourceLocation.tryParse(kitId);
        return kit != null && EdenRentalApi.begin(player, sessionId, kit);
    }

    @Override
    public void end(MinecraftServer server, UUID player) {
        EdenRentalApi.end(server, player);
    }

    @Override
    public void reequip(ServerPlayer player) {
        EdenRentalApi.reequip(player);
    }

    @Override
    public boolean isRenting(ServerPlayer player) {
        return EdenRentalApi.isRenting(player);
    }

    @Override
    public List<ItemStack> preview(String kitId) {
        ResourceLocation kit = ResourceLocation.tryParse(kitId);
        return kit == null ? List.of() : KitsCompat.contents(kit);
    }
}
