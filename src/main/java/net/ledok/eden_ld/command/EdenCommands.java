package net.ledok.eden_ld.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.resources.ResourceLocation;
import java.util.Collection;
import java.util.List;
import net.ledok.eden_ld.chat.ChatChannels;
import net.ledok.eden_ld.hub.HubServer;
import net.ledok.eden_ld.hub.party.Party;
import net.ledok.eden_ld.hub.party.PartyManager;
import net.ledok.eden_ld.minestar.MinestarHook;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

public class EdenCommands {

    private static final SuggestionProvider<CommandSourceStack> ACTIVITY_IDS = (context, builder) ->
            SharedSuggestionProvider.suggestResource(net.ledok.eden_ld.hub.activity.Activities
                    .all(context.getSource().getServer()).stream().map(net.ledok.eden_ld.hub.activity.Activity::id), builder);

    private static com.mojang.brigadier.builder.ArgumentBuilder<CommandSourceStack, ?> themeCommand(
            String name, boolean unlock) {
        return Commands.literal(name)
                .then(Commands.argument("players", EntityArgument.players())
                        .then(Commands.argument("theme", ResourceLocationArgument.id())
                                .suggests((context, builder) -> SharedSuggestionProvider.suggest(
                                        net.ledok.eden_ld.hub.HubSettings.BUILT_IN_THEMES, builder))
                                .executes(context -> {
                                    String theme = ResourceLocationArgument.getId(context, "theme").toString();
                                    Collection<ServerPlayer> players = EntityArgument.getPlayers(context, "players");
                                    var server = context.getSource().getServer();
                                    for (ServerPlayer target : players) {
                                        net.ledok.eden_ld.hub.HubSettings.setThemeUnlocked(
                                                server, target.getUUID(), theme, unlock);
                                        HubServer.pushSettings(server, target.getUUID());
                                    }
                                    context.getSource().sendSuccess(() -> Component.translatable(
                                            unlock ? "message.eden_ld.theme.admin_unlocked"
                                                    : "message.eden_ld.theme.admin_locked",
                                            theme, players.size()), true);
                                    return players.size();
                                })));
    }

    private static com.mojang.brigadier.builder.ArgumentBuilder<CommandSourceStack, ?> backgroundCommand(
            String name, boolean unlock) {
        return Commands.literal(name)
                .then(Commands.argument("players", EntityArgument.players())
                        .then(Commands.argument("background", ResourceLocationArgument.id())
                                .suggests((context, builder) -> SharedSuggestionProvider.suggest(
                                        net.ledok.eden_ld.hub.HubSettings.BUILT_IN_BACKGROUNDS, builder))
                                .executes(context -> {
                                    String background = ResourceLocationArgument.getId(context, "background").toString();
                                    Collection<ServerPlayer> players = EntityArgument.getPlayers(context, "players");
                                    var server = context.getSource().getServer();
                                    for (ServerPlayer target : players) {
                                        if (net.ledok.eden_ld.hub.HubSettings.setBackgroundUnlocked(
                                                server, target.getUUID(), background, unlock)) {
                                            // An open Profile tab shows the new choice at once.
                                            net.ledok.eden_ld.hub.profile.ProfileServer.send(target, target.getUUID());
                                        }
                                    }
                                    context.getSource().sendSuccess(() -> Component.translatable(
                                            unlock ? "message.eden_ld.background.admin_unlocked"
                                                    : "message.eden_ld.background.admin_locked",
                                            background, players.size()), true);
                                    return players.size();
                                })));
    }

    /** /activities complete: a won run for each player (stats, the complete_dungeon daily objective). */
    private static int complete(CommandContext<CommandSourceStack> context, @org.jetbrains.annotations.Nullable String difficulty,
                                int seconds) throws CommandSyntaxException {
        ResourceLocation id = ResourceLocationArgument.getId(context, "id");
        Collection<ServerPlayer> players = EntityArgument.getPlayers(context, "players");
        net.ledok.eden_ld.hub.activity.ActivityServer.recordRun(context.getSource().getServer(), id, difficulty, true,
                seconds * 1000L, 0, players.stream().map(ServerPlayer::getUUID).toList());
        context.getSource().sendSuccess(() -> Component.translatable(
                "message.eden_ld.activities.admin_completed", id.toString(), players.size()), true);
        return players.size();
    }

    private static int resetActivities(CommandContext<CommandSourceStack> context,
                                       @org.jetbrains.annotations.Nullable ResourceLocation id) throws CommandSyntaxException {
        Collection<ServerPlayer> players = EntityArgument.getPlayers(context, "players");
        for (ServerPlayer target : players) {
            net.ledok.eden_ld.hub.activity.ActivityProgress.reset(context.getSource().getServer(), target.getUUID(), id);
        }
        context.getSource().sendSuccess(() -> Component.translatable("message.eden_ld.activities.admin_reset",
                id == null ? "*" : id.toString(), players.size()), true);
        return players.size();
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("eden")
                .requires(EdenPermissions.require(EdenPermissions.COMMANDS).or(EdenPermissions.require(EdenPermissions.WORLD)))
                // A dimension's own gamerules, PvP and difficulty.
                .then(WorldRulesCommand.gamerule())
                .then(WorldRulesCommand.pvp())
                .then(WorldRulesCommand.difficulty())
                // Every command on the server and the op level it needs, to a file (deciding player permissions).
                .then(Commands.literal("commands").requires(EdenPermissions.require(EdenPermissions.COMMANDS))
                        .then(Commands.literal("dump").executes(context -> {
                            try {
                                int[] counts = CommandDump.write(context.getSource().getServer());
                                context.getSource().sendSuccess(() -> Component.translatable(
                                        "message.eden_ld.commands.dumped", counts[0], counts[1], CommandDump.FILE), false);
                                return counts[0];
                            } catch (java.io.IOException e) {
                                net.ledok.eden_ld.EdenLD.LOGGER.error("[Commands] Can't write {}.", CommandDump.FILE, e);
                                context.getSource().sendFailure(Component.translatable("message.eden_ld.commands.dump_failed",
                                        e.getMessage()));
                                return 0;
                            }
                        }))));

        dispatcher.register(Commands.literal("p")
                .then(Commands.argument("message", StringArgumentType.greedyString())
                        .executes(context -> {
                            ServerPlayer player = context.getSource().getPlayer();
                            if (player == null) {
                                context.getSource().sendFailure(Component.translatable("message.eden_ld.players_only"));
                                return 0;
                            }
                            Component error = ChatChannels.send(player, ChatChannels.Channel.PARTY,
                                    StringArgumentType.getString(context, "message"));
                            if (error != null) {
                                context.getSource().sendFailure(error);
                                return 0;
                            }
                            return 1;
                        })));

        // Guild chat.
        dispatcher.register(Commands.literal("g")
                .then(Commands.argument("message", StringArgumentType.greedyString())
                        .executes(context -> {
                            ServerPlayer player = context.getSource().getPlayer();
                            if (player == null) {
                                context.getSource().sendFailure(Component.translatable("message.eden_ld.players_only"));
                                return 0;
                            }
                            Component error = ChatChannels.send(player, ChatChannels.Channel.GUILD,
                                    StringArgumentType.getString(context, "message"));
                            if (error != null) {
                                context.getSource().sendFailure(error);
                                return 0;
                            }
                            return 1;
                        })));

        // Local chat (players nearby).
        dispatcher.register(Commands.literal("l")
                .then(Commands.argument("message", StringArgumentType.greedyString())
                        .executes(context -> {
                            ServerPlayer player = context.getSource().getPlayer();
                            if (player == null) {
                                context.getSource().sendFailure(Component.translatable("message.eden_ld.players_only"));
                                return 0;
                            }
                            ChatChannels.send(player, ChatChannels.Channel.LOCAL,
                                    StringArgumentType.getString(context, "message"));
                            return 1;
                        })));

        // Switch the chat channel (the chat screen's badge does the same).
        var channelCommand = Commands.literal("ch");
        for (ChatChannels.Channel channel : ChatChannels.Channel.values()) {
            channelCommand.then(Commands.literal(channel.name().toLowerCase(java.util.Locale.ROOT))
                    .executes(context -> {
                        ServerPlayer player = context.getSource().getPlayer();
                        if (player == null) {
                            context.getSource().sendFailure(Component.translatable("message.eden_ld.players_only"));
                            return 0;
                        }
                        if (!ChatChannels.select(player, channel)) {
                            context.getSource().sendFailure(Component.translatable("chat.eden_ld.channel_unavailable",
                                    Component.translatable("chat.eden_ld.channel." + channel.name().toLowerCase(java.util.Locale.ROOT))));
                            return 0;
                        }
                        context.getSource().sendSuccess(() -> Component.translatable("chat.eden_ld.channel_switched",
                                Component.translatable("chat.eden_ld.channel." + channel.name().toLowerCase(java.util.Locale.ROOT))), false);
                        return 1;
                    }));
        }
        dispatcher.register(channelCommand);

        // Discord emojis: re-read config/eden_ld/emoji.json, fetch new images, resend.
        dispatcher.register(Commands.literal("emojis")
                .requires(EdenPermissions.require(EdenPermissions.EMOJIS))
                .then(Commands.literal("reload").executes(context -> {
                    net.ledok.eden_ld.chat.emoji.EmojiRegistry.reload(context.getSource().getServer());
                    context.getSource().sendSuccess(() -> Component.translatable("message.eden_ld.emoji.reloading"), true);
                    return 1;
                }))
                // Extra favorite / saved-sticker slots (a shop item: "+20 for 30d"); grants stack.
                .then(Commands.literal("bonus")
                        .then(Commands.literal("add")
                                .then(Commands.argument("players", net.minecraft.commands.arguments.GameProfileArgument.gameProfile())
                                        .then(Commands.argument("kind", StringArgumentType.word()).suggests(BONUS_KINDS)
                                                .then(Commands.argument("amount", IntegerArgumentType.integer(-1000, 1000))
                                                        .executes(context -> bonusAdd(context, "permanent"))
                                                        .then(Commands.argument("duration", StringArgumentType.word())
                                                                .suggests((c, b) -> SharedSuggestionProvider.suggest(
                                                                        List.of("30d", "7d", "1d", "12h", "permanent"), b))
                                                                .executes(context -> bonusAdd(context,
                                                                        StringArgumentType.getString(context, "duration"))))))))
                        .then(Commands.literal("clear")
                                .then(Commands.argument("players", net.minecraft.commands.arguments.GameProfileArgument.gameProfile())
                                        .executes(context -> bonusClear(context, null))
                                        .then(Commands.argument("kind", StringArgumentType.word()).suggests(BONUS_KINDS)
                                                .executes(context -> bonusClear(context,
                                                        StringArgumentType.getString(context, "kind"))))))
                        .then(Commands.literal("list")
                                .then(Commands.argument("players", net.minecraft.commands.arguments.GameProfileArgument.gameProfile())
                                        .executes(EdenCommands::bonusList)))));

        // Guild maintenance by tag (works with every member offline).
        dispatcher.register(Commands.literal("guild-admin")
                .requires(EdenPermissions.require(EdenPermissions.GUILDS))
                .then(Commands.literal("list").executes(context -> {
                    var manager = net.ledok.eden_ld.hub.guild.GuildManager.get(context.getSource().getServer());
                    var all = manager.all();
                    context.getSource().sendSuccess(() -> Component.translatable(
                            "message.eden_ld.guild.admin_list", all.size()), false);
                    for (var guild : all) {
                        context.getSource().sendSuccess(() -> Component.literal("  [" + guild.tag + "] " + guild.name
                                + " (" + guild.members.size() + ") — " + guild.memberNames.getOrDefault(guild.leader(), "?")),
                                false);
                    }
                    return all.size();
                }))
                .then(Commands.literal("disband")
                        .then(Commands.argument("tag", StringArgumentType.word())
                                .suggests((context, builder) -> SharedSuggestionProvider.suggest(
                                        net.ledok.eden_ld.hub.guild.GuildManager.get(context.getSource().getServer())
                                                .all().stream().map(guild -> guild.tag), builder))
                                .executes(context -> {
                                    var server = context.getSource().getServer();
                                    var manager = net.ledok.eden_ld.hub.guild.GuildManager.get(server);
                                    var guild = manager.byTag(StringArgumentType.getString(context, "tag"));
                                    if (guild == null) {
                                        context.getSource().sendFailure(Component.translatable("message.eden_ld.guild.unknown"));
                                        return 0;
                                    }
                                    String name = guild.name;
                                    manager.forceDisband(server, guild);
                                    net.ledok.eden_ld.hub.guild.GuildServer.syncAll(server);
                                    net.ledok.eden_ld.hub.HubServer.refreshWaystones(server);
                                    context.getSource().sendSuccess(() -> Component.translatable(
                                            "message.eden_ld.guild.admin_disbanded", name), true);
                                    return 1;
                                })))
                .then(Commands.literal("xp")
                        .then(guildTag()
                                .then(Commands.literal("add")
                                        .then(Commands.argument("amount", com.mojang.brigadier.arguments.LongArgumentType.longArg())
                                                .executes(context -> guildXp(context, false))))
                                .then(Commands.literal("set")
                                        .then(Commands.argument("amount", com.mojang.brigadier.arguments.LongArgumentType.longArg(0))
                                                .executes(context -> guildXp(context, true))))))
                .then(Commands.literal("quests")
                        .then(guildTag()
                                .then(Commands.literal("reset").executes(context -> {
                                    var server = context.getSource().getServer();
                                    var guild = guildByTag(context);
                                    if (guild == null) {
                                        return 0;
                                    }
                                    net.ledok.eden_ld.hub.guild.quest.GuildQuests.reset(server, guild);
                                    context.getSource().sendSuccess(() -> Component.translatable(
                                            "message.eden_ld.guild.admin_quests_reset", guild.name), true);
                                    return 1;
                                })))));

        // /ignore, /ignore <player>, /unignore <player>: chat, whispers, pings and invites from them.
        net.ledok.eden_ld.hub.IgnoreList.registerCommands(dispatcher);

        // Invite to your party — or, when solo, an invite whose acceptance creates one.
        dispatcher.register(Commands.literal("invite")
                .then(Commands.argument("player", EntityArgument.player())
                        .executes(context -> {
                            ServerPlayer player = context.getSource().getPlayer();
                            if (player == null) {
                                context.getSource().sendFailure(Component.translatable("message.eden_ld.players_only"));
                                return 0;
                            }
                            ServerPlayer target = EntityArgument.getPlayer(context, "player");
                            Component error = HubServer.invite(player, target);
                            if (error != null) {
                                context.getSource().sendFailure(error);
                                return 0;
                            }
                            return 1;
                        })));

        dispatcher.register(Commands.literal("hub")
                // Hub themes: unlock/lock per player (rewards, shop items, admins).
                .then(Commands.literal("theme")
                        .requires(EdenPermissions.require(EdenPermissions.THEMES))
                        .then(themeCommand("unlock", true))
                        .then(themeCommand("lock", false)))
                // Profile backgrounds, the same way.
                .then(Commands.literal("background")
                        .requires(EdenPermissions.require(EdenPermissions.BACKGROUNDS))
                        .then(backgroundCommand("unlock", true))
                        .then(backgroundCommand("lock", false)))
                // The hub on the Profile tab (the level-up message links here).
                .then(Commands.literal("profile").executes(context -> {
                    ServerPlayer player = context.getSource().getPlayer();
                    if (player == null) {
                        context.getSource().sendFailure(Component.translatable("message.eden_ld.players_only"));
                        return 0;
                    }
                    HubServer.openHub(player, "profile");
                    return 1;
                }))
                .executes(context -> {
                    ServerPlayer player = context.getSource().getPlayer();
                    if (player == null) {
                        context.getSource().sendFailure(Component.translatable("message.eden_ld.players_only"));
                        return 0;
                    }
                    HubServer.openHub(player);
                    return 1;
                }));

        // Activities: record a clear (testing, rewards), forget stats, see a player's level.
        dispatcher.register(Commands.literal("activities")
                .requires(EdenPermissions.require(EdenPermissions.DUNGEONS))
                .then(Commands.literal("complete")
                        .then(Commands.argument("players", EntityArgument.players())
                                .then(Commands.argument("id", ResourceLocationArgument.id())
                                        .suggests(ACTIVITY_IDS)
                                        .executes(context -> complete(context, null, 0))
                                        .then(Commands.argument("difficulty", StringArgumentType.word())
                                                .executes(context -> complete(context,
                                                        StringArgumentType.getString(context, "difficulty"), 0))
                                                .then(Commands.argument("seconds", IntegerArgumentType.integer(0))
                                                        .executes(context -> complete(context,
                                                                StringArgumentType.getString(context, "difficulty"),
                                                                IntegerArgumentType.getInteger(context, "seconds"))))))))
                .then(Commands.literal("reset")
                        .then(Commands.argument("players", EntityArgument.players())
                                .executes(context -> resetActivities(context, null))
                                .then(Commands.argument("id", ResourceLocationArgument.id())
                                        .suggests(ACTIVITY_IDS)
                                        .executes(context -> resetActivities(context,
                                                ResourceLocationArgument.getId(context, "id"))))))
                .then(Commands.literal("level")
                        .then(Commands.argument("player", EntityArgument.player())
                                .executes(context -> {
                                    ServerPlayer target = EntityArgument.getPlayer(context, "player");
                                    boolean gated = net.ledok.eden_ld.hub.activity.ActivityLevels.gated();
                                    int level = net.ledok.eden_ld.hub.activity.ActivityLevels.level(target);
                                    context.getSource().sendSuccess(() -> gated
                                            ? Component.translatable("message.eden_ld.activities.admin_level",
                                                    target.getGameProfile().getName(), level)
                                            : Component.translatable("message.eden_ld.activities.admin_not_gated"), false);
                                    return level;
                                }))));

        // Daily quests: extra free rerolls for today (quest/shop rewards) and an admin reset.
        dispatcher.register(Commands.literal("dailies")
                .requires(EdenPermissions.require(EdenPermissions.DAILIES))
                .then(Commands.literal("give-rerolls")
                        .then(Commands.argument("players", EntityArgument.players())
                                .then(Commands.argument("amount", IntegerArgumentType.integer(1, 100))
                                        .executes(context -> {
                                            Collection<ServerPlayer> players = EntityArgument.getPlayers(context, "players");
                                            int amount = IntegerArgumentType.getInteger(context, "amount");
                                            for (ServerPlayer target : players) {
                                                net.ledok.eden_ld.api.hub.EdenDailyApi.grantExtraRerolls(
                                                        context.getSource().getServer(), target.getUUID(), amount);
                                            }
                                            context.getSource().sendSuccess(() -> Component.translatable(
                                                    "message.eden_ld.dailies.admin_rerolls", amount, players.size()), true);
                                            return players.size();
                                        }))))
                .then(Commands.literal("reset")
                        .then(Commands.argument("players", EntityArgument.players())
                                .executes(context -> {
                                    Collection<ServerPlayer> players = EntityArgument.getPlayers(context, "players");
                                    for (ServerPlayer target : players) {
                                        net.ledok.eden_ld.hub.daily.DailyQuests.resetToday(
                                                context.getSource().getServer(), target.getUUID());
                                        HubServer.pushDailies(target);
                                    }
                                    context.getSource().sendSuccess(() -> Component.translatable(
                                            "message.eden_ld.dailies.admin_reset", players.size()), true);
                                    return players.size();
                                }))));

        // Admin party maintenance: works on parties whose members are all offline
        // (tags identify parties; display names are not unique).
        dispatcher.register(Commands.literal("party-admin")
                .requires(EdenPermissions.require(EdenPermissions.PARTIES))
                .then(Commands.literal("list")
                        .executes(context -> {
                            PartyManager manager = PartyManager.get(context.getSource().getServer());
                            var all = manager.all();
                            context.getSource().sendSuccess(() -> Component.translatable(
                                    "message.eden_ld.party.admin_list", all.size()), false);
                            for (Party party : all) {
                                context.getSource().sendSuccess(() -> Component.literal(
                                        "  " + party.tag + " — \"" + party.name + "\" ("
                                                + party.members.size() + ")"), false);
                            }
                            return all.size();
                        }))
                .then(Commands.literal("disband")
                        .then(Commands.argument("tag", StringArgumentType.word())
                                .suggests((context, builder) -> {
                                    PartyManager manager =
                                            PartyManager.get(context.getSource().getServer());
                                    manager.all().forEach(party -> builder.suggest(party.tag));
                                    return builder.buildFuture();
                                })
                                .executes(context -> {
                                    var server = context.getSource().getServer();
                                    PartyManager manager = PartyManager.get(server);
                                    String tag = StringArgumentType.getString(context, "tag");
                                    Party party = manager.byTag(tag);
                                    if (party == null) {
                                        context.getSource().sendFailure(Component.translatable(
                                                "message.eden_ld.party.no_such_party"));
                                        return 0;
                                    }
                                    manager.forceDisband(server, party);
                                    HubServer.syncParties(server);
                                    context.getSource().sendSuccess(() -> Component.translatable(
                                            "message.eden_ld.party.admin_disbanded",
                                            party.name, party.tag), true);
                                    return 1;
                                }))));

        if (MinestarHook.LOADED) {
            dispatcher.register(Commands.literal("shop-receive")
                    .executes(context -> {
                        ServerPlayer player = context.getSource().getPlayer();
                        if (player == null) {
                            context.getSource().sendFailure(Component.translatable("message.eden_ld.players_only"));
                            return 0;
                        }
                        MinestarHook.claimPurchases(player);
                        return 1;
                    }));
        }
    }

    // ---------------------------------------------------------------- /emojis bonus

    private static final SuggestionProvider<CommandSourceStack> BONUS_KINDS = (context, builder) ->
            SharedSuggestionProvider.suggest(List.of("emojis", "stickers"), builder);

    private static int bonusAdd(CommandContext<CommandSourceStack> context, String durationText) throws CommandSyntaxException {
        var kind = net.ledok.eden_ld.chat.emoji.FavoriteBonus.Kind.of(StringArgumentType.getString(context, "kind"));
        int amount = IntegerArgumentType.getInteger(context, "amount");
        long duration = net.ledok.eden_ld.chat.emoji.FavoriteBonus.parseDuration(durationText);
        if (kind == null || amount == 0 || duration < 0) {
            context.getSource().sendFailure(Component.translatable("message.eden_ld.emoji.bonus.usage"));
            return 0;
        }
        var server = context.getSource().getServer();
        var profiles = net.minecraft.commands.arguments.GameProfileArgument.getGameProfiles(context, "players");
        String signed = (amount > 0 ? "+" : "") + amount;
        Component time = duration == 0 ? Component.translatable("message.eden_ld.emoji.bonus.permanent")
                : Component.literal(durationText);
        Component what = Component.translatable("message.eden_ld.emoji.bonus.kind." + kind.id());
        for (var profile : profiles) {
            net.ledok.eden_ld.chat.emoji.FavoriteBonus.grant(server, profile.getId(), kind, amount, duration);
            net.ledok.eden_ld.chat.emoji.EmojiRegistry.limitsChanged(server, profile.getId());
            ServerPlayer online = server.getPlayerList().getPlayer(profile.getId());
            if (online != null) {
                int limit = kind == net.ledok.eden_ld.chat.emoji.FavoriteBonus.Kind.EMOJIS
                        ? net.ledok.eden_ld.chat.emoji.EmojiRegistry.favoritesLimit(online)
                        : net.ledok.eden_ld.chat.emoji.EmojiRegistry.stickersLimit(online);
                online.sendSystemMessage(Component.translatable("message.eden_ld.emoji.bonus.received",
                        signed, what, time, limit).withStyle(net.minecraft.ChatFormatting.GOLD));
            }
        }
        context.getSource().sendSuccess(() -> Component.translatable("message.eden_ld.emoji.bonus.added",
                signed, what, time, profiles.size()), true);
        return profiles.size();
    }

    private static int bonusClear(CommandContext<CommandSourceStack> context, String kindText) throws CommandSyntaxException {
        var kind = kindText == null ? null : net.ledok.eden_ld.chat.emoji.FavoriteBonus.Kind.of(kindText);
        if (kindText != null && kind == null) {
            context.getSource().sendFailure(Component.translatable("message.eden_ld.emoji.bonus.usage"));
            return 0;
        }
        var server = context.getSource().getServer();
        int removed = 0;
        for (var profile : net.minecraft.commands.arguments.GameProfileArgument.getGameProfiles(context, "players")) {
            removed += net.ledok.eden_ld.chat.emoji.FavoriteBonus.clear(server, profile.getId(), kind);
            net.ledok.eden_ld.chat.emoji.EmojiRegistry.limitsChanged(server, profile.getId());
        }
        int count = removed;
        context.getSource().sendSuccess(() -> Component.translatable("message.eden_ld.emoji.bonus.cleared", count), true);
        return removed;
    }

    private static int bonusList(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        var server = context.getSource().getServer();
        int shown = 0;
        for (var profile : net.minecraft.commands.arguments.GameProfileArgument.getGameProfiles(context, "players")) {
            var grants = net.ledok.eden_ld.chat.emoji.FavoriteBonus.grants(server, profile.getId());
            context.getSource().sendSuccess(() -> Component.translatable("message.eden_ld.emoji.bonus.list",
                    profile.getName(), grants.size()), false);
            for (var grant : grants) {
                String signed = (grant.amount() > 0 ? "+" : "") + grant.amount();
                String left = net.ledok.eden_ld.chat.emoji.FavoriteBonus.remaining(grant);
                context.getSource().sendSuccess(() -> Component.literal("  " + signed + " ").append(
                        Component.translatable("message.eden_ld.emoji.bonus.kind." + grant.kind().id())).append(" — ")
                        .append(left.isEmpty() ? Component.translatable("message.eden_ld.emoji.bonus.permanent")
                                : Component.translatable("message.eden_ld.emoji.bonus.left", left)), false);
            }
            shown += grants.size();
        }
        return shown;
    }

    /** {@code <tag>} argument with the guilds' tags suggested. */
    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<net.minecraft.commands.CommandSourceStack, String> guildTag() {
        return Commands.argument("tag", StringArgumentType.word())
                .suggests((context, builder) -> SharedSuggestionProvider.suggest(
                        net.ledok.eden_ld.hub.guild.GuildManager.get(context.getSource().getServer())
                                .all().stream().map(guild -> guild.tag), builder));
    }

    @org.jetbrains.annotations.Nullable
    private static net.ledok.eden_ld.hub.guild.Guild guildByTag(
            com.mojang.brigadier.context.CommandContext<net.minecraft.commands.CommandSourceStack> context) {
        var guild = net.ledok.eden_ld.hub.guild.GuildManager.get(context.getSource().getServer())
                .byTag(StringArgumentType.getString(context, "tag"));
        if (guild == null) {
            context.getSource().sendFailure(Component.translatable("message.eden_ld.guild.unknown"));
        }
        return guild;
    }

    private static int guildXp(com.mojang.brigadier.context.CommandContext<net.minecraft.commands.CommandSourceStack> context,
                               boolean set) {
        var server = context.getSource().getServer();
        var guild = guildByTag(context);
        if (guild == null) {
            return 0;
        }
        long amount = com.mojang.brigadier.arguments.LongArgumentType.getLong(context, "amount");
        if (set) {
            net.ledok.eden_ld.hub.guild.GuildLevels.setXp(server, guild, amount);
        } else {
            net.ledok.eden_ld.hub.guild.GuildLevels.addXp(server, guild, amount);
        }
        var progress = net.ledok.eden_ld.hub.guild.GuildLevels.progress(guild.xp);
        context.getSource().sendSuccess(() -> Component.translatable("message.eden_ld.guild.admin_xp",
                guild.name, guild.xp, progress.level()), true);
        return 1;
    }
}
