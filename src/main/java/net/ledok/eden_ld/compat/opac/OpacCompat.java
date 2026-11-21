package net.ledok.eden_ld.compat.opac;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.ledok.eden_ld.EdenLD;
import net.ledok.eden_ld.hub.guild.Guild;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
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

    /** Land freed (unclaimed) when a guild ended: how many chunks, and which. */
    public record Released(int chunks, java.util.List<FreedChunk> freed) {
    }

    /** One freed chunk, in chunk coordinates. */
    public record FreedChunk(net.minecraft.resources.ResourceLocation dimension, int x, int z) {
    }

    private OpacCompat() {
    }

    public static void initialize() {
        if (!LOADED) {
            return;
        }
        OpacHook.register();
        // OPAC picks its primary party system while the server starts; settle guilds after that. First, claim times for
        // chunks OPAC loaded from disk and OPAC bonuses put back in line with Eden's saved records (review findings 12, 13).
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            guarded(() -> OpacGuildClaims.startup(server));
            reconcile(server);
        });
        // Bonus changes made outside Eden (admins, NPCs_LD) are adopted once a minute and at a clean stop, so a restore
        // after a hard stop reverts at most a minute of them (finding 12).
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (server.getTickCount() % OpacGuildClaims.PERIODIC_TICKS == 0) {
                guarded(() -> OpacGuildClaims.periodic(server));
            }
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> guarded(() -> OpacGuildClaims.stopping(server)));
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

    /**
     * How many of the leader's chunks disbanding the guild now would free (land over their own limit); 0 when none
     * or without guild claims.
     */
    public static int disbandExcess(MinecraftServer server, Guild guild) {
        if (!LOADED) {
            return 0;
        }
        try {
            return OpacGuildClaims.disbandExcess(server, guild);
        } catch (RuntimeException | LinkageError e) {
            EdenLD.LOGGER.warn("Open Parties and Claims compat failed", e);
            return 0;
        }
    }

    /**
     * Claims {@code target} would hold, and their claim limit, if they led the guild now (a leadership move takes every
     * chunk of the current claim owner along); null without guild claims or when the check fails. Review finding 11:
     * transfers that would put the new leader over their limit are refused.
     */
    @Nullable
    public static ClaimsInfo afterTransfer(MinecraftServer server, Guild guild, java.util.UUID target) {
        if (!LOADED) {
            return null;
        }
        try {
            return OpacGuildClaims.afterTransfer(server, guild, target);
        } catch (RuntimeException | LinkageError e) {
            EdenLD.LOGGER.warn("Open Parties and Claims compat failed", e);
            return null;
        }
    }

    /** The leader's own claim limit after the guild (for the disband warning), or -1 without guild claims. */
    public static int ownLimit(MinecraftServer server, Guild guild) {
        if (!LOADED) {
            return -1;
        }
        try {
            ClaimsInfo land = OpacGuildClaims.afterGuild(server, guild);
            return land == null ? -1 : land.limit();
        } catch (RuntimeException | LinkageError e) {
            EdenLD.LOGGER.warn("Open Parties and Claims compat failed", e);
            return -1;
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

    /**
     * Whether OPAC protects {@code target} from {@code attacker} where it stands — the answer OPAC gives when the
     * attacker hurts it (a claim the attacker has no access to, a protected mob). False without OPAC, on the client, or
     * when the check fails.
     */
    public static boolean protects(Entity attacker, Entity target) {
        if (!LOADED || !(attacker.level() instanceof ServerLevel)) {
            return false;
        }
        try {
            return OpacProtection.protects(attacker, target);
        } catch (RuntimeException | LinkageError e) {
            if (!protectionFailed) {
                protectionFailed = true;
                EdenLD.LOGGER.warn("Open Parties and Claims protection check failed", e);
            }
            return false;
        }
    }

    private static boolean protectionFailed;

    private static void guarded(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException | LinkageError e) {
            EdenLD.LOGGER.warn("Open Parties and Claims compat failed", e);
        }
    }
}
