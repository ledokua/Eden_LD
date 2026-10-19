package net.ledok.eden_ld.client.hub;

import net.ledok.eden_ld.hub.HubPayloads;

/**
 * Client-side party and guild relation from the synced {@link HubState#partyData} and {@link HubState#guildData} —
 * the local player's own party and guild only. Used by {@link ClientPvp} for the combat mods' team matchers (target
 * selection and highlights); the server remains authoritative for damage.
 */
public final class ClientPartyRelation {

    private ClientPartyRelation() {
    }

    /**
     * Partymates or guildmates (from the synced tab data), unless either has friendly-fire protection suspended
     * (dueling): the client's side of {@code PartyManager.protects}.
     */
    public static boolean protects(java.util.UUID a, java.util.UUID b) {
        boolean inParty = false;
        HubPayloads.PartyData party = HubState.partyData;
        if (party != null && party.inParty()) {
            boolean hasA = false;
            boolean hasB = false;
            for (HubPayloads.PartyMember member : party.members()) {
                boolean involved = member.uuid().equals(a) || member.uuid().equals(b);
                if (involved && member.ffSuspended()) {
                    // Suspended (e.g. dueling): not a teammate for targeting purposes.
                    return false;
                }
                hasA |= member.uuid().equals(a);
                hasB |= member.uuid().equals(b);
            }
            inParty = hasA && hasB;
        }
        boolean inGuild = false;
        HubPayloads.GuildData guild = HubState.guildData;
        if (guild != null && guild.inGuild()) {
            boolean hasA = false;
            boolean hasB = false;
            for (HubPayloads.GuildMember member : guild.members()) {
                boolean involved = member.uuid().equals(a) || member.uuid().equals(b);
                if (involved && member.ffSuspended()) {
                    return false;
                }
                hasA |= member.uuid().equals(a);
                hasB |= member.uuid().equals(b);
            }
            inGuild = hasA && hasB;
        }
        return inParty || inGuild;
    }
}
