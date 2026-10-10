package net.ledok.eden_ld.client.hub;

import net.ledok.eden_ld.compat.CombatTeamsCompat;
import net.ledok.eden_ld.hub.HubPayloads;
import org.jetbrains.annotations.Nullable;

/**
 * Client-side party relation from the synced {@link HubState#partyData} — the
 * local player's own party only. Used by combat-mod team matchers for target
 * selection and highlights; the server remains authoritative for damage.
 */
public final class ClientPartyRelation {

    private ClientPartyRelation() {
    }

    /** Partymates or guildmates (from the synced tab data), unless either is suspended. */
    @Nullable
    public static CombatTeamsCompat.Relation resolve(java.util.UUID a, java.util.UUID b) {
        boolean inParty = false;
        HubPayloads.PartyData party = HubState.partyData;
        if (party != null && party.inParty()) {
            boolean hasA = false;
            boolean hasB = false;
            for (HubPayloads.PartyMember member : party.members()) {
                boolean involved = member.uuid().equals(a) || member.uuid().equals(b);
                if (involved && member.ffSuspended()) {
                    // Suspended (e.g. dueling): not a teammate for targeting purposes.
                    return null;
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
                    return null;
                }
                hasA |= member.uuid().equals(a);
                hasB |= member.uuid().equals(b);
            }
            inGuild = hasA && hasB;
        }
        return inParty || inGuild ? new CombatTeamsCompat.Relation(true, false) : null;
    }
}
