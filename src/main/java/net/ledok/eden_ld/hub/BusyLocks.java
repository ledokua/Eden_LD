package net.ledok.eden_ld.hub;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.ledok.busylib.BusyState;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * What a busy player (BusyLib: a duel, a dungeon run, the tutorial) can't do in Eden — join or found a guild, use
 * the guild bank, claim daily rewards or reroll, buy in the Wardrobe, collect website purchases — so nothing comes in
 * or goes out mid-run and the tutorial can't be broken ({@code docs/PLAN-tutorial.md}). Party invites, waystone
 * teleports and mail claims check busy where they are handled.
 */
public final class BusyLocks {

    /** The busy reason of a player in the tutorial. */
    public static final String TUTORIAL = "eden_ld:tutorial";

    private BusyLocks() {
    }

    /** True when the player is busy — they are told why, in chat and on the hub. */
    public static boolean refuse(ServerPlayer player) {
        if (!BusyState.isBusy(player)) {
            return false;
        }
        Component message = reason(player);
        player.sendSystemMessage(message.copy().withStyle(ChatFormatting.RED));
        ServerPlayNetworking.send(player, new HubPayloads.HubNotice(message));
        return true;
    }

    /** Why a busy player can't: the tutorial, or something else (a duel, a dungeon…). */
    public static Component reason(ServerPlayer player) {
        return Component.translatable(BusyState.getReasons(player.getUUID()).contains(TUTORIAL)
                ? "message.eden_ld.busy.tutorial" : "message.eden_ld.busy.other");
    }
}
