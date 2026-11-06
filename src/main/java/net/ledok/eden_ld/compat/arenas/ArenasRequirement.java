package net.ledok.eden_ld.compat.arenas;

import net.ledok.arenas_ld.api.ArenasApi;
import net.ledok.eden_ld.hub.activity.ActivityGate;

/**
 * The only class using ArenasApi 4.16.0's {@code EntryRequirement}: lobbies show the level a difficulty needs in green
 * once the player has it (where {@link ActivityGate#check} refuses, Arenas_LD shows that refusal instead).
 * {@link ArenasIntegration} calls in here only after checking the running Arenas_LD has it.
 */
final class ArenasRequirement {

    private ArenasRequirement() {
    }

    static void register() {
        ArenasApi.registerEntryRequirement(ActivityGate::requirement);
    }
}
