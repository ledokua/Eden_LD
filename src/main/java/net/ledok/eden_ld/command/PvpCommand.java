package net.ledok.eden_ld.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import net.ledok.eden_ld.combat.PlayerPvp;
import net.ledok.eden_ld.combat.PvpSwitch;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * {@code /pvp} — the player's own PvP switch (docs/PLAN-pvp-toggle.md), for everyone: without an argument it shows the
 * state, the cooldown left and what the dimension makes of it; {@code on} / {@code off} switch it (a minute's cooldown
 * after any switch, none while combat-tagged), as the inventory button does. Admins: {@code /eden pvp player}.
 */
final class PvpCommand {

    private PvpCommand() {
    }

    static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("pvp")
                .executes(PvpCommand::status)
                .then(Commands.literal("on").executes(context -> set(context, true)))
                .then(Commands.literal("off").executes(context -> set(context, false))));
    }

    private static int status(CommandContext<CommandSourceStack> context) {
        ServerPlayer player = context.getSource().getPlayer();
        if (player == null) {
            context.getSource().sendFailure(Component.translatable("message.eden_ld.players_only"));
            return 0;
        }
        context.getSource().sendSuccess(() -> PlayerPvp.status(player), false);
        return PlayerPvp.flag(player.server, player.getUUID()) ? 1 : 0;
    }

    private static int set(CommandContext<CommandSourceStack> context, boolean on) {
        ServerPlayer player = context.getSource().getPlayer();
        if (player == null) {
            context.getSource().sendFailure(Component.translatable("message.eden_ld.players_only"));
            return 0;
        }
        PvpSwitch.Result result = PlayerPvp.request(player, on);
        Component message = PlayerPvp.message(player, result, on);
        if (result == PvpSwitch.Result.SWITCHED || result == PvpSwitch.Result.ALREADY) {
            context.getSource().sendSuccess(() -> message, false);
            return 1;
        }
        context.getSource().sendFailure(message);
        return 0;
    }
}
