package net.ledok.eden_ld.compat.xaero;

import net.ledok.eden_ld.EdenLD;
import net.ledok.eden_ld.hub.guild.Guild;
import net.ledok.eden_ld.hub.guild.GuildManager;
import net.ledok.eden_ld.hub.party.Party;
import net.ledok.eden_ld.hub.party.PartyManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.player.Player;
import xaero.common.server.MinecraftServerData;
import xaero.common.server.radar.tracker.ISyncedPlayerTrackerSystem;

/**
 * Eden's guilds and parties as one of Xaero's Minimap synced player tracker systems (like its own FTB Teams and
 * Argonauts support): the minimap's server side sends each player the positions of everyone in their guild or Eden
 * party, any distance and dimension, every quarter second, and the minimap and world map draw them as tracked players.
 * OPAC's parties are off here (Eden's guilds are its party system), so without this nobody shared a position.
 * <p>
 * It counts as a party system, so the minimap applies each player's Open Parties and Claims location settings ("Share
 * location with party", "Receive locations from party") to it — the privacy switches players already have.
 */
final class XaeroTracker implements ISyncedPlayerTrackerSystem {

    /** The minimap's tracking level for members of the same party (1 is a mutual ally party). */
    private static final int SAME_PARTY = 2;

    private XaeroTracker() {
    }

    /**
     * Registers with the minimap's server data. False while that data isn't there yet: the minimap creates it in its own
     * SERVER_STARTED listener, registered from its server entrypoint, so after Eden's ({@link XaeroHook} tries again).
     * True once registered, or when registering failed for good (logged).
     */
    static boolean register(MinecraftServer server) {
        try {
            MinecraftServerData data = MinecraftServerData.get(server);
            if (data == null) {
                return false;
            }
            data.getSyncedPlayerTrackerSystemManager().register(EdenLD.MOD_ID, new XaeroTracker());
            EdenLD.LOGGER.info("[Xaero] Guild and party members are tracked on each other's minimap and world map.");
        } catch (RuntimeException | LinkageError e) {
            EdenLD.LOGGER.warn("[Xaero] Couldn't register the guild and party tracker: members stay off the maps.", e);
        }
        return true;
    }

    /**
     * 2 when the two share a guild or an Eden party, else 0. For a party system the minimap reads 2 as "same party"
     * (checked against OPAC's "Share location with party" / "Receive locations from party", on by default) and 1 as
     * "mutual ally party" (the mutual-ally switches, off by default), as its own FTB Teams support returns 2 for
     * teammates. Until 1.3.78 / 1.3.73.2 this returned 1, so nobody's position was ever sent.
     */
    @Override
    public int getTrackingLevel(Player tracker, Player tracked) {
        MinecraftServer server = tracker.getServer();
        if (server == null || tracker == tracked) {
            return 0;
        }
        Guild guild = GuildManager.get(server).guildOf(tracker.getUUID());
        // The tracked player's own Settings switches decide (shown to both unless hidden).
        int hidden = net.ledok.eden_ld.hub.HubSettings.mapHidden(server, tracked.getUUID());
        if (guild != null && guild.members.containsKey(tracked.getUUID())
                && (hidden & net.ledok.eden_ld.hub.HubSettings.MAP_HIDDEN_GUILD) == 0) {
            return SAME_PARTY;
        }
        Party party = PartyManager.get(server).partyOf(tracker.getUUID());
        return party != null && party.members.containsKey(tracked.getUUID())
                && (hidden & net.ledok.eden_ld.hub.HubSettings.MAP_HIDDEN_PARTY) == 0 ? SAME_PARTY : 0;
    }

    @Override
    public boolean isPartySystem() {
        return true;
    }
}
