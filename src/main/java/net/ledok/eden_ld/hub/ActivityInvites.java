package net.ledok.eden_ld.hub;

import net.ledok.eden_ld.hub.guild.Guild;
import net.ledok.eden_ld.hub.guild.GuildManager;
import net.ledok.eden_ld.hub.party.Party;
import net.ledok.eden_ld.hub.party.PartyManager;
import net.ledok.eden_ld.minestar.MinestarHook;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Who may invite a player to raids, dungeons and arenas (Arenas_LD asks through its invite filter): the player's
 * Settings switches ({@link HubSettings#ACTIVITY_INVITES_OFF}) for friends, party members, guildmates and everyone
 * else, plus their ignore list. Someone who is several of these (a friend in the same guild) may invite if any of
 * their switches is on; "everyone else" is only for people who are none of them.
 */
public final class ActivityInvites {

    private ActivityInvites() {
    }

    /** Why {@code inviter} can't invite {@code invitee} (for the inviter), or null when they may. */
    @Nullable
    public static Component refusal(MinecraftServer server, ServerPlayer inviter, UUID invitee) {
        UUID from = inviter.getUUID();
        if (from.equals(invitee)) {
            return null;
        }
        // Someone the invitee ignores reads the same as the privacy setting: not accepting invites from you.
        boolean allowed = !IgnoreList.ignores(server, invitee, from) && allowedBySettings(server, from, invitee);
        if (allowed) {
            return null;
        }
        ServerPlayer target = server.getPlayerList().getPlayer(invitee);
        Component name = target != null ? target.getDisplayName() : Component.literal(server.getProfileCache() == null
                ? invitee.toString()
                : server.getProfileCache().get(invitee).map(com.mojang.authlib.GameProfile::getName)
                        .orElse(invitee.toString()));
        return Component.translatable("message.eden_ld.activity_invites.blocked", name);
    }

    private static boolean allowedBySettings(MinecraftServer server, UUID from, UUID invitee) {
        int off = HubSettings.activityInvitesOff(server, invitee);
        if (off == 0) {
            return true;
        }
        boolean related = false;
        if (MinestarHook.friendUuids(invitee).contains(from)) {
            related = true;
            if ((off & HubSettings.ACTIVITY_INVITES_FRIENDS) == 0) {
                return true;
            }
        }
        Party party = PartyManager.get(server).partyOf(invitee);
        if (party != null && party.members.containsKey(from)) {
            related = true;
            if ((off & HubSettings.ACTIVITY_INVITES_PARTY) == 0) {
                return true;
            }
        }
        Guild guild = GuildManager.get(server).guildOf(invitee);
        if (guild != null && guild.isMember(from)) {
            related = true;
            if ((off & HubSettings.ACTIVITY_INVITES_GUILD) == 0) {
                return true;
            }
        }
        return !related && (off & HubSettings.ACTIVITY_INVITES_OTHERS) == 0;
    }
}
