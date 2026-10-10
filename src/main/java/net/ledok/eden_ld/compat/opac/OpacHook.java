package net.ledok.eden_ld.compat.opac;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xaero.pac.common.claims.player.api.IPlayerChunkClaimAPI;
import xaero.pac.common.claims.tracker.api.IClaimsManagerListenerAPI;
import xaero.pac.common.event.api.v3.OPACServerAddonRegister;

/**
 * Registers with OPAC (only loaded when OPAC is): the guild party system, and a claim listener
 * that timestamps every owner change for {@link ClaimTimes}.
 */
final class OpacHook {

    private OpacHook() {
    }

    static void register() {
        OPACServerAddonRegister.EVENT.register(context -> {
            MinecraftServer server = context.getServer();
            context.getPartySystemManagerAPI()
                    .register(OpacCompat.SYSTEM_NAME, new EdenGuildPartySystem(server));
            context.getClaimsManagerTrackerAPI().register(new IClaimsManagerListenerAPI() {
                @Override
                public void onWholeRegionChange(@NotNull ResourceLocation dimension, int regionX, int regionZ) {
                    // Server-side claims change chunk by chunk; region changes are client sync only.
                }

                @Override
                public void onDimensionChange(ResourceLocation dimension) {
                    // Whole-dimension changes are client-side resets (clear / recolor); nothing to time.
                }

                @Override
                public void onChunkChange(@NotNull ResourceLocation dimension, int chunkX, int chunkZ,
                                          @Nullable IPlayerChunkClaimAPI claim) {
                    ClaimTimes times = ClaimTimes.get(server);
                    if (times != null) {
                        times.onChange(dimension, chunkX, chunkZ, claim == null ? null : claim.getPlayerId());
                    }
                }
            });
        });
    }
}
