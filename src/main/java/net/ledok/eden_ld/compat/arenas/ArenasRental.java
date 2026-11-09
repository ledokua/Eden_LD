package net.ledok.eden_ld.compat.arenas;

import net.ledok.arenas_ld.api.ArenasApi;
import net.ledok.eden_ld.api.rental.EdenRentalApi;
import net.ledok.eden_ld.compat.KitsCompat;
import net.ledok.eden_ld.rental.RentalPresets;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.UUID;

/**
 * Rental builds in Arenas runs (Arenas 4.26.1+): Eden's stash behind Arenas' rental provider. Loadouts are Kits_LD's
 * hidden kits {@code falleneden:rental/<level>/<preset>} ({@link RentalPresets}). The only class touching the 4.26.1
 * types; registered only when the running Arenas_LD has them.
 */
final class ArenasRental implements ArenasApi.RentalProvider {

    private ArenasRental() {
    }

    static void register() {
        ArenasApi.setRentalProvider(new ArenasRental());
    }

    @Override
    public List<String> presets(int level) {
        return RentalPresets.forLevel(level);
    }

    @Override
    public String displayName(String kitId) {
        return RentalPresets.displayName(kitId).getString();
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
