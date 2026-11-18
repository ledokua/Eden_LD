package net.ledok.eden_ld.compat.arenas;

import net.ledok.arenas_ld.api.ArenasApi;
import net.ledok.eden_ld.hub.guild.Guild;
import net.ledok.eden_ld.hub.guild.GuildManager;
import net.ledok.eden_ld.hub.party.Party;
import net.ledok.eden_ld.hub.party.PartyManager;
import net.ledok.eden_ld.minestar.MinestarHook;

import java.util.UUID;

/**
 * Arenas_LD's lobby invite picker (4.40.3+) groups online players into friends, party and guild, and everyone else:
 * Eden says how each one stands to the viewer, the closest first (a friend in the same guild is a friend). Asked once
 * per online player each time the picker opens: in-memory lookups only. The only class touching the 4.40.3 types.
 */
final class ArenasRelations {

    private ArenasRelations() {
    }

    static void register() {
        ArenasApi.setPlayerRelations((viewer, other) -> {
            UUID self = viewer.getUUID();
            if (MinestarHook.friendUuids(self).contains(other)) {
                return ArenasApi.PlayerRelation.FRIEND;
            }
            Party party = PartyManager.get(viewer.server).partyOf(self);
            if (party != null && party.members.containsKey(other)) {
                return ArenasApi.PlayerRelation.PARTY;
            }
            Guild guild = GuildManager.get(viewer.server).guildOf(self);
            if (guild != null && guild.isMember(other)) {
                return ArenasApi.PlayerRelation.GUILD;
            }
            return ArenasApi.PlayerRelation.NONE;
        });
    }
}
