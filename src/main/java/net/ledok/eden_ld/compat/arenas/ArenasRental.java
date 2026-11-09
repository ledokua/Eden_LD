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
import java.util.List;
import java.util.UUID;

/**
 * Rental builds in Arenas runs (Arenas 4.26.0+): Eden's stash behind Arenas' rental provider. Loadouts are Kits_LD's
 * hidden kits {@code falleneden:rental/<class>/<band>}. The only class touching the 4.26.0 types; registered only
 * when the running Arenas_LD has them.
 */
final class ArenasRental implements ArenasApi.RentalProvider {

    private static final String PREFIX = "falleneden:rental/";
    /** Arenas asks for the classes once a second per lobby member: the kit list is read at most this often. */
    private static final long CLASSES_TTL_MS = 10_000L;

    private List<String> classes = List.of();
    private long classesRead;

    private ArenasRental() {
    }

    static void register() {
        ArenasApi.setRentalProvider(new ArenasRental());
    }

    @Override
    public List<String> classes() {
        long now = System.currentTimeMillis();
        if (now - classesRead > CLASSES_TTL_MS) {
            List<String> found = new ArrayList<>();
            for (ResourceLocation id : KitsCompat.hiddenKitIds(PREFIX)) {
                // falleneden:rental/<class>/<band>
                String[] parts = id.getPath().split("/");
                if (parts.length == 3 && !found.contains(parts[1])) {
                    found.add(parts[1]);
                }
            }
            classes = List.copyOf(found);
            classesRead = now;
        }
        return classes;
    }

    @Nullable
    @Override
    public String kitFor(String playerClass, int band) {
        ResourceLocation id = ResourceLocation.tryParse(PREFIX + playerClass + "/" + band);
        return id != null && KitsCompat.exists(id) ? id.toString() : null;
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

    /** The kit's contents for the lobby preview (part of the provider from Arenas 4.26.0's final build). */
    public List<ItemStack> preview(String kitId) {
        ResourceLocation kit = ResourceLocation.tryParse(kitId);
        return kit == null ? List.of() : KitsCompat.contents(kit);
    }
}
