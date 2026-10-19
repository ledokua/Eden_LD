package net.ledok.eden_ld.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.ledok.eden_ld.combat.PlayerPvp;
import net.ledok.eden_ld.combat.PvpMode;
import net.ledok.eden_ld.world.DimensionGameRules;
import net.ledok.eden_ld.world.RuleScope;
import net.ledok.eden_ld.world.WorldRules;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.DimensionArgument;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/**
 * A dimension's own settings (docs/PLAN-world-rules.md): {@code /eden gamerule <dimension> [<rule> [<value> |
 * reset]]} — only the rules that can differ per dimension are offered, {@code /gamerule} stays server-wide,
 * and without a rule it lists what the dimension changes — plus {@code /eden pvp <dimension> [off | toggle | on |
 * reset]} (docs/PLAN-pvp-toggle.md) and {@code /eden difficulty <dimension> [peaceful | easy | normal | hard | reset]}.
 * Without a value each shows the setting and where it comes from. {@code /eden pvp player <players> [on | off]} shows
 * or sets players' own PvP switch, skipping their cooldown and combat check.
 */
final class WorldRulesCommand {

    private WorldRulesCommand() {
    }

    static LiteralArgumentBuilder<CommandSourceStack> gamerule() {
        RequiredArgumentBuilder<CommandSourceStack, ResourceLocation> dimension = Commands.argument("dimension",
                        DimensionArgument.dimension())
                .executes(context -> list(context.getSource(), DimensionArgument.getDimension(context, "dimension")));
        for (GameRules.Key<?> key : RuleScope.all().values()) {
            if (RuleScope.of(key) == RuleScope.DIMENSION) {
                dimension.then(rule(key));
            }
        }
        return Commands.literal("gamerule").requires(EdenPermissions.require(EdenPermissions.WORLD)).then(dimension);
    }

    static LiteralArgumentBuilder<CommandSourceStack> pvp() {
        RequiredArgumentBuilder<CommandSourceStack, ResourceLocation> dimension = Commands.argument("dimension",
                        DimensionArgument.dimension())
                .executes(context -> queryPvp(context.getSource(), level(context)))
                .then(Commands.literal("reset").executes(context -> setPvp(context, null)));
        for (PvpMode mode : PvpMode.values()) {
            dimension.then(Commands.literal(mode.key).executes(context -> setPvp(context, mode)));
        }
        // "player" wins over a dimension of that name: Brigadier takes a literal that matches before an argument.
        return Commands.literal("pvp").requires(EdenPermissions.require(EdenPermissions.WORLD))
                .then(Commands.literal("player")
                        .then(Commands.argument("players", EntityArgument.players())
                                .executes(WorldRulesCommand::queryPlayerPvp)
                                .then(Commands.literal("on").executes(context -> setPlayerPvp(context, true)))
                                .then(Commands.literal("off").executes(context -> setPlayerPvp(context, false)))))
                .then(dimension);
    }

    static LiteralArgumentBuilder<CommandSourceStack> difficulty() {
        RequiredArgumentBuilder<CommandSourceStack, ResourceLocation> dimension = Commands.argument("dimension",
                        DimensionArgument.dimension())
                .executes(context -> queryDifficulty(context.getSource(), level(context)))
                .then(Commands.literal("reset").executes(context -> setDifficulty(context, null)));
        for (Difficulty difficulty : Difficulty.values()) {
            dimension.then(Commands.literal(difficulty.getKey()).executes(context -> setDifficulty(context, difficulty)));
        }
        return Commands.literal("difficulty").requires(EdenPermissions.require(EdenPermissions.WORLD)).then(dimension);
    }

    private static <T extends GameRules.Value<T>> LiteralArgumentBuilder<CommandSourceStack> rule(GameRules.Key<T> key) {
        return Commands.literal(key.getId())
                .executes(context -> query(context.getSource(), level(context), key))
                .then(Commands.literal("reset").executes(context -> reset(context.getSource(), level(context), key)))
                .then(RuleScope.type(key).createArgument("value").executes(context -> set(context, key)));
    }

    private static ServerLevel level(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        return DimensionArgument.getDimension(context, "dimension");
    }

    private static int list(CommandSourceStack source, ServerLevel level) {
        DimensionGameRules rules = WorldRules.rules(level);
        List<GameRules.Key<?>> changed = rules == null ? List.of()
                : rules.changed().stream().sorted(Comparator.comparing(GameRules.Key::getId)).toList();
        if (changed.isEmpty()) {
            source.sendSuccess(() -> Component.translatable("message.eden_ld.world.rules_none", name(level)), false);
            return 0;
        }
        MutableComponent text = Component.translatable("message.eden_ld.world.rules_header", name(level), changed.size());
        for (GameRules.Key<?> key : changed) {
            text.append("\n").append(Component.translatable("message.eden_ld.world.rules_line", key.getId(),
                    value(level.getGameRules(), key), value(source.getServer().getGameRules(), key)));
        }
        source.sendSuccess(() -> text, false);
        return changed.size();
    }

    private static int query(CommandSourceStack source, ServerLevel level, GameRules.Key<?> key) {
        DimensionGameRules rules = WorldRules.rules(level);
        String value = value(level.getGameRules(), key);
        if (rules != null && rules.isChanged(key)) {
            String server = value(source.getServer().getGameRules(), key);
            source.sendSuccess(() -> Component.translatable("message.eden_ld.world.rule_own", key.getId(), name(level),
                    value, server), false);
        } else {
            source.sendSuccess(() -> Component.translatable("message.eden_ld.world.rule_server", key.getId(), name(level),
                    value), false);
        }
        return level.getGameRules().getRule(key).getCommandResult();
    }

    private static <T extends GameRules.Value<T>> int set(CommandContext<CommandSourceStack> context, GameRules.Key<T> key)
            throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        ServerLevel level = level(context);
        if (!WorldRules.writable()) {
            source.sendFailure(Component.translatable("message.eden_ld.world.file_broken"));
            return 0;
        }
        // The rule's own argument parsed it (true/false, a number, a mod's type); its text form is what's saved.
        Object parsed = context.getArgument("value", Object.class);
        String text = parsed instanceof Enum<?> constant ? constant.name() : String.valueOf(parsed);
        boolean own = WorldRules.set(level, key, text, source.getTextName());
        String server = value(source.getServer().getGameRules(), key);
        if (own) {
            String value = value(level.getGameRules(), key);
            source.sendSuccess(() -> Component.translatable("message.eden_ld.world.rule_set", key.getId(), name(level),
                    value, server), true);
        } else {
            source.sendSuccess(() -> Component.translatable("message.eden_ld.world.rule_follows", key.getId(), name(level),
                    server), true);
        }
        return 1;
    }

    private static int reset(CommandSourceStack source, ServerLevel level, GameRules.Key<?> key) {
        if (!WorldRules.writable()) {
            source.sendFailure(Component.translatable("message.eden_ld.world.file_broken"));
            return 0;
        }
        boolean was = WorldRules.reset(level, key, source.getTextName());
        String server = value(source.getServer().getGameRules(), key);
        source.sendSuccess(() -> Component.translatable(was ? "message.eden_ld.world.rule_follows"
                : "message.eden_ld.world.rule_already", key.getId(), name(level), server), was);
        return was ? 1 : 0;
    }

    private static int queryPvp(CommandSourceStack source, ServerLevel level) {
        PvpMode own = WorldRules.ownPvp(level);
        Component server = mode(PvpMode.serverDefault(source.getServer().isPvpAllowed()));
        if (own != null) {
            source.sendSuccess(() -> Component.translatable("message.eden_ld.world.pvp_own", name(level), mode(own), server), false);
        } else {
            source.sendSuccess(() -> Component.translatable("message.eden_ld.world.pvp_server", name(level), server), false);
        }
        return WorldRules.pvpMode(level).ordinal();
    }

    private static int setPvp(CommandContext<CommandSourceStack> context, @Nullable PvpMode mode) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        ServerLevel level = level(context);
        if (!WorldRules.writable()) {
            source.sendFailure(Component.translatable("message.eden_ld.world.file_broken"));
            return 0;
        }
        boolean own = WorldRules.setPvp(level, mode, source.getTextName());
        Component server = mode(PvpMode.serverDefault(source.getServer().isPvpAllowed()));
        if (own) {
            source.sendSuccess(() -> Component.translatable("message.eden_ld.world.pvp_set", name(level), mode(mode), server), true);
        } else {
            source.sendSuccess(() -> Component.translatable("message.eden_ld.world.pvp_follows", name(level), server), true);
        }
        return 1;
    }

    /** A PvP mode's name: "off", "each player's choice", "on". */
    private static Component mode(PvpMode mode) {
        return Component.translatable("message.eden_ld.world.pvp_mode." + mode.key);
    }

    private static int queryPlayerPvp(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        Collection<ServerPlayer> players = EntityArgument.getPlayers(context, "players");
        int on = 0;
        for (ServerPlayer player : players) {
            boolean flag = PlayerPvp.flag(source.getServer(), player.getUUID());
            on += flag ? 1 : 0;
            long cooldown = PlayerPvp.cooldownLeft(source.getServer(), player.getUUID());
            source.sendSuccess(() -> Component.translatable(cooldown > 0 ? "message.eden_ld.pvp.admin_status_cooldown"
                            : "message.eden_ld.pvp.admin_status", player.getGameProfile().getName(), onOff(flag),
                    mode(WorldRules.pvpMode(player.serverLevel())), cooldown), false);
        }
        return on;
    }

    private static int setPlayerPvp(CommandContext<CommandSourceStack> context, boolean on) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        Collection<ServerPlayer> players = EntityArgument.getPlayers(context, "players");
        int changed = 0;
        for (ServerPlayer player : players) {
            if (PlayerPvp.adminSet(source.getServer(), player.getUUID(), on, source.getTextName())) {
                changed++;
            }
        }
        int count = changed;
        source.sendSuccess(() -> Component.translatable("message.eden_ld.pvp.admin_set", onOff(on), count, players.size()), true);
        return changed;
    }

    private static int queryDifficulty(CommandSourceStack source, ServerLevel level) {
        Difficulty own = WorldRules.ownDifficulty(level);
        Component server = source.getServer().getWorldData().getDifficulty().getDisplayName();
        if (own != null) {
            source.sendSuccess(() -> Component.translatable("message.eden_ld.world.difficulty_own", name(level),
                    own.getDisplayName(), server), false);
        } else {
            source.sendSuccess(() -> Component.translatable("message.eden_ld.world.difficulty_server", name(level), server), false);
        }
        return level.getDifficulty().getId();
    }

    private static int setDifficulty(CommandContext<CommandSourceStack> context, @Nullable Difficulty difficulty)
            throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        ServerLevel level = level(context);
        if (!WorldRules.writable()) {
            source.sendFailure(Component.translatable("message.eden_ld.world.file_broken"));
            return 0;
        }
        boolean own = WorldRules.setDifficulty(level, difficulty, source.getTextName());
        Component server = source.getServer().getWorldData().getDifficulty().getDisplayName();
        if (own) {
            source.sendSuccess(() -> Component.translatable("message.eden_ld.world.difficulty_set", name(level),
                    difficulty.getDisplayName(), server), true);
        } else {
            source.sendSuccess(() -> Component.translatable("message.eden_ld.world.difficulty_follows", name(level), server), true);
        }
        return 1;
    }

    private static Component onOff(boolean on) {
        return Component.translatable(on ? "message.eden_ld.world.on" : "message.eden_ld.world.off");
    }

    private static String value(GameRules rules, GameRules.Key<?> key) {
        return rules.getRule(key).serialize();
    }

    private static String name(ServerLevel level) {
        return level.dimension().location().toString();
    }
}
