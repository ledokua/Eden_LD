package net.ledok.eden_ld.compat.arenas;

import net.ledok.arenas_ld.api.ArenasApi;
import net.ledok.eden_ld.hub.party.Party;
import net.ledok.eden_ld.hub.party.PartyManager;

import java.util.List;

/**
 * The only class using ArenasApi 4.34.0's party provider: Arenas_LD's ally HP sidebar shows the player's Eden party
 * outside runs too (LeDok, 1.3.104). {@link ArenasIntegration} calls in here only after checking the running Arenas_LD
 * has it.
 */
final class ArenasParty {

    private ArenasParty() {
    }

    /** Asked a few times a second per player outside runs, on the server thread: an in-memory lookup and a copy. */
    static void register() {
        ArenasApi.setPartyProvider(player -> {
            Party party = PartyManager.get(player.server).partyOf(player.getUUID());
            return party == null ? List.of() : List.copyOf(party.members.keySet());
        });
    }
}
