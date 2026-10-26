package net.ledok.eden_ld.api.hub;

import net.ledok.eden_ld.hub.HubSettings;
import net.minecraft.server.MinecraftServer;

import java.util.UUID;

/**
 * Read (and, for the keys listed here, change) a player's hub settings (source-stable). Server thread; works for offline players too.
 *
 * <p>Settings other mods read live in Eden's per-player store under the other mod's own keys — the player
 * changes them on the hub's Settings tab, Eden keeps them, the mod reads them here. Today: Duels_LD's
 * {@link #DUELS_SKIP_LOSS_WARNING} and {@link #DUELS_PERSONAL_CHALLENGES}.
 */
public final class EdenSettingsApi {

    /** Skip the "you can lose your stake" popup before a wager duel (Duels' ready check always stays). Default off. */
    public static final String DUELS_SKIP_LOSS_WARNING = HubSettings.DUELS_SKIP_LOSS_WARNING;
    /** Accept personal wager challenges ({@code /duel <name>}). Default on. */
    public static final String DUELS_PERSONAL_CHALLENGES = HubSettings.DUELS_PERSONAL_CHALLENGES;

    private EdenSettingsApi() {
    }

    /**
     * The player's yes/no setting under {@code key}, or {@code def} while they never changed it. Pass the
     * setting's real default — the Settings tab shows the same one.
     */
    public static boolean getBoolean(MinecraftServer server, UUID player, String key, boolean def) {
        return HubSettings.bool(server, player, key, def);
    }

    /**
     * Changes one of the yes/no settings listed here (another mod's "Don't ask again" box), as if the player
     * flipped it on the Settings tab; an online player's open Settings tab follows. False for a key Eden
     * doesn't know (since 1.3.12).
     */
    public static boolean setBoolean(MinecraftServer server, UUID player, String key, boolean value) {
        if (!HubSettings.isDuelsFlag(key) || !HubSettings.set(server, player, key, value ? 1 : 0)) {
            return false;
        }
        net.minecraft.server.level.ServerPlayer online = server.getPlayerList().getPlayer(player);
        if (online != null) {
            net.ledok.eden_ld.hub.HubServer.sendSettings(online);
        }
        return true;
    }
}
