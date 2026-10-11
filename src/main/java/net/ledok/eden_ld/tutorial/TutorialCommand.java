package net.ledok.eden_ld.tutorial;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.ledok.eden_ld.command.EdenPermissions;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.Collection;
import java.util.Locale;

/**
 * {@code /eden tutorial start|finish <players>} and {@code status <player>} — staff and the guide dialog (which runs
 * {@code finish} last in its cleanup). Permission {@code eden_ld.admin.tutorial}.
 */
public final class TutorialCommand {

    private TutorialCommand() {
    }

    public static LiteralArgumentBuilder<CommandSourceStack> node() {
        return Commands.literal("tutorial").requires(EdenPermissions.require(EdenPermissions.TUTORIAL))
                .then(Commands.literal("start").then(Commands.argument("players", EntityArgument.players())
                        .executes(context -> start(context.getSource(), EntityArgument.getPlayers(context, "players")))))
                .then(Commands.literal("finish").then(Commands.argument("players", EntityArgument.players())
                        .executes(context -> finish(context.getSource(), EntityArgument.getPlayers(context, "players")))))
                .then(Commands.literal("status").then(Commands.argument("player", EntityArgument.player())
                        .executes(context -> {
                            ServerPlayer player = EntityArgument.getPlayer(context, "player");
                            Tutorial.State state = Tutorial.state(player);
                            context.getSource().sendSuccess(() -> Component.translatable("message.eden_ld.tutorial.status",
                                    player.getDisplayName(), Component.translatable("message.eden_ld.tutorial.state."
                                            + state.name().toLowerCase(Locale.ROOT))), false);
                            return state.ordinal();
                        })));
    }

    private static int start(CommandSourceStack source, Collection<ServerPlayer> players) {
        int started = 0;
        for (ServerPlayer player : players) {
            if (Tutorial.start(player)) {
                started++;
                source.sendSuccess(() -> Component.translatable("message.eden_ld.tutorial.started", player.getDisplayName()), true);
            } else {
                source.sendFailure(Component.translatable("message.eden_ld.tutorial.start_failed", player.getDisplayName()));
            }
        }
        return started;
    }

    private static int finish(CommandSourceStack source, Collection<ServerPlayer> players) {
        for (ServerPlayer player : players) {
            Tutorial.finish(player);
            source.sendSuccess(() -> Component.translatable("message.eden_ld.tutorial.finished", player.getDisplayName()), true);
        }
        return players.size();
    }
}
