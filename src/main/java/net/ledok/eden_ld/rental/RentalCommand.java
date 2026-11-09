package net.ledok.eden_ld.rental;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.ledok.eden_ld.command.EdenPermissions;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * {@code /eden rental}: a player's rental session (status), ending it by hand (a stuck session), and starting one with
 * a kit (to test loadouts).
 */
public final class RentalCommand {

    private RentalCommand() {
    }

    public static LiteralArgumentBuilder<CommandSourceStack> node() {
        return Commands.literal("rental").requires(EdenPermissions.require(EdenPermissions.COMMANDS))
                .then(Commands.literal("status").then(Commands.argument("player", EntityArgument.player())
                        .executes(context -> {
                            ServerPlayer player = EntityArgument.getPlayer(context, "player");
                            CompoundTag record = RentalStash.record(player);
                            if (record == null) {
                                context.getSource().sendSuccess(() -> Component.translatable(
                                        "message.eden_ld.rental.none", player.getDisplayName()), false);
                                return 0;
                            }
                            String kit = record.getString("kit");
                            long minutes = (System.currentTimeMillis() - record.getLong("started")) / 60_000L;
                            context.getSource().sendSuccess(() -> Component.translatable("message.eden_ld.rental.status",
                                    player.getDisplayName(), record.getString("session"), kit.isEmpty() ? "-" : kit,
                                    minutes, record.getList("inventory", Tag.TAG_COMPOUND).size(),
                                    RentalStash.isRenting(player) ? "" : " (!)"), false);
                            return 1;
                        })))
                .then(Commands.literal("end").then(Commands.argument("player", EntityArgument.player())
                        .executes(context -> {
                            ServerPlayer player = EntityArgument.getPlayer(context, "player");
                            boolean ended = RentalStash.end(player);
                            context.getSource().sendSuccess(() -> Component.translatable(ended
                                    ? "message.eden_ld.rental.ended" : "message.eden_ld.rental.none",
                                    player.getDisplayName()), true);
                            return ended ? 1 : 0;
                        })))
                .then(Commands.literal("begin").then(Commands.argument("player", EntityArgument.player())
                        .then(Commands.argument("kit", ResourceLocationArgument.id()).executes(context -> {
                            ServerPlayer player = EntityArgument.getPlayer(context, "player");
                            var kit = ResourceLocationArgument.getId(context, "kit");
                            boolean begun = RentalStash.begin(player, "test-" + context.getSource().getTextName(), kit);
                            if (!begun) {
                                context.getSource().sendFailure(Component.translatable("message.eden_ld.rental.not_begun",
                                        player.getDisplayName(), kit.toString()));
                                return 0;
                            }
                            context.getSource().sendSuccess(() -> Component.translatable("message.eden_ld.rental.begun",
                                    player.getDisplayName(), kit.toString()), true);
                            return 1;
                        }))));
    }
}
