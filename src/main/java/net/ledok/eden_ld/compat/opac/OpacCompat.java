package net.ledok.eden_ld.compat.opac;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.ledok.eden_ld.EdenLD;
import net.ledok.eden_ld.hub.guild.Guild;
import net.minecraft.server.MinecraftServer;
import org.jetbrains.annotations.Nullable;

/**
 * Open Parties and Claims compat — the only class the rest of Eden calls. Every method is a
 * no-op without OPAC; OPAC classes live behind it in {@link OpacHook} / {@link OpacGuildClaims}
 * so they are never loaded when the mod is missing.
 * <p>
 * Eden registers its guilds as an OPAC party system named {@value #SYSTEM_NAME}. With
 * {@code primaryPartySystem = "eden_ld_guilds"} and {@code partyOwnedClaims = true} in OPAC's
 * server config, a guild owns claims (through its leader), and the claim upgrades bought from the
 * guild bank raise that limit.
 */
public final class OpacCompat {

    public static final boolean LOADED = FabricLoader.getInstance().isModLoaded("openpartiesandclaims");
    public static final String SYSTEM_NAME = "eden_ld_guilds";

    /** Claims of one guild for the bank screen. */
    public record ClaimsInfo(int used, int limit) {
    }

    /** Land released when a guild ended: how many chunks, and whether they became expired claims. */
    public record Released(int chunks, boolean expired) {
    }

    private OpacCompat() {
    }

    public static void initialize() {
        if (!LOADED) {
            return;
        }
        OpacHook.register();
        // OPAC picks its primary party system while the server starts; settle guilds after that.
        ServerLifecycleEvents.SERVER_STARTED.register(OpacCompat::reconcile);
    }

    /**
     * Brings OPAC in line with the guilds: the bought bonus claims sit on each current leader,
     * and after a leadership change the old leader's claims move over. Cheap when nothing
     * changed; called after every guild change.
     */
    public static void reconcile(MinecraftServer server) {
        if (LOADED) {
            guarded(() -> OpacGuildClaims.reconcile(server));
        }
    }

    /**
     * A guild is being deleted (members still in it): its land stays with the leader up to their
     * own limit; the bought bonus goes and the rest is released. Null when nothing was released.
     */
    @Nullable
    public static Released onGuildDeleted(MinecraftServer server, Guild guild) {
        if (!LOADED) {
            return null;
        }
        try {
            return OpacGuildClaims.end(server, guild);
        } catch (RuntimeException | LinkageError e) {
            EdenLD.LOGGER.warn("Open Parties and Claims compat failed", e);
            return null;
        }
    }

    /** Used and total claims of the guild, or null when guild claims aren't active. */
    @Nullable
    public static ClaimsInfo claims(MinecraftServer server, Guild guild) {
        if (!LOADED) {
            return null;
        }
        try {
            return OpacGuildClaims.claims(server, guild);
        } catch (RuntimeException | LinkageError e) {
            EdenLD.LOGGER.warn("Open Parties and Claims compat failed", e);
            return null;
        }
    }

    /**
     * Adds {@code delta} to a player's own OPAC bonus claims (Minestar level perks); false when
     * OPAC is missing or refused, so the caller keeps its record of what's applied unchanged.
     */
    public static boolean addPlayerBonus(MinecraftServer server, java.util.UUID player, int delta) {
        if (!LOADED) {
            return false;
        }
        try {
            return OpacGuildClaims.addPlayerBonus(server, player, delta);
        } catch (RuntimeException | LinkageError e) {
            EdenLD.LOGGER.warn("Open Parties and Claims compat failed", e);
            return false;
        }
    }

    private static void guarded(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException | LinkageError e) {
            EdenLD.LOGGER.warn("Open Parties and Claims compat failed", e);
        }
    }
}
