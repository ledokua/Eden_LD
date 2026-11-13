package net.ledok.eden_ld.compat.arenas;

import net.ledok.arenas_ld.api.ArenasApi;
import net.ledok.eden_ld.hub.ActivityInvites;

/**
 * Arenas_LD's invite filter (4.28.0+): every raid, dungeon and arena invite asks Eden's {@link ActivityInvites}
 * first, so a refused inviter gets Eden's message and no invite is made. The only class touching the 4.28.0 type:
 * an older Arenas_LD just leaves the Settings switches hidden.
 */
final class ArenasInvites {

    private ArenasInvites() {
    }

    static void register() {
        ArenasApi.setInviteFilter((inviter, invitee, activityId) ->
                ActivityInvites.refusal(inviter.server, inviter, invitee));
        ArenasHook.invitesFiltered(true);
    }
}
