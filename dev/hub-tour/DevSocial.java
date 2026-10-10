package net.ledok.eden_ld.command;

import com.mojang.brigadier.CommandDispatcher;
import net.ledok.eden_ld.hub.HubServer;
import net.ledok.eden_ld.hub.guild.Guild;
import net.ledok.eden_ld.hub.guild.GuildManager;
import net.ledok.eden_ld.hub.guild.GuildServer;
import net.ledok.eden_ld.hub.mail.MailHub;
import net.ledok.eden_ld.hub.party.PartyManager;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;

/**
 * TEMPORARY dev tool (remove after the hub critique): {@code /devsocial} — the player running it
 * leads a party and a guild with every other online player, and gets a gift mail from one of them,
 * so the hub's social screens show real data. Registered only with {@code -Deden_ld.devSocial=true}.
 */
final class DevSocial {

    private DevSocial() {
    }

    static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        if (!Boolean.getBoolean("eden_ld.devSocial")) {
            return;
        }
        if (!hooked) {
            hooked = true;
            // Runs by itself for "Player768" once three players are online (no typing in a headless client).
            net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
                    server.tell(new net.minecraft.server.TickTask(server.getTickCount() + 60, () -> {
                        ServerPlayer leader = server.getPlayerList().getPlayerByName("Player768");
                        if (!done && leader != null && server.getPlayerList().getPlayerCount() >= 3) {
                            done = true;
                            server.getPlayerList().broadcastSystemMessage(Component.literal(setup(server, leader)), false);
                        }
                    })));
        }
        dispatcher.register(Commands.literal("devsocial").requires(source -> source.hasPermission(2))
                .executes(context -> {
                    ServerPlayer leader = context.getSource().getPlayerOrException();
                    String report = setup(context.getSource().getServer(), leader);
                    context.getSource().sendSuccess(() -> Component.literal(report), false);
                    return 1;
                }));
    }

    private static boolean hooked;
    private static boolean done;

    private static String setup(MinecraftServer server, ServerPlayer leader) {
                    {
                    List<ServerPlayer> others = server.getPlayerList().getPlayers().stream()
                            .filter(p -> p != leader).toList();
                    StringBuilder report = new StringBuilder();

                    PartyManager parties = PartyManager.get(server);
                    for (ServerPlayer other : others) {
                        report.append(" party:").append(other.getGameProfile().getName()).append('=')
                                .append(text(parties.invite(leader, other))).append('/')
                                .append(text(parties.acceptInvite(other, leader.getUUID())));
                    }
                    HubServer.syncParties(server);

                    GuildManager guilds = GuildManager.get(server);
                    report.append(" guild=").append(text(guilds.create(leader, "Eden Wardens", "EDW", 0x3A8FE0)));
                    Guild guild = guilds.guildOf(leader.getUUID());
                    if (guild != null) {
                        for (ServerPlayer other : others) {
                            report.append(" g:").append(other.getGameProfile().getName()).append('=')
                                    .append(text(guilds.invite(leader, other))).append('/')
                                    .append(text(guilds.acceptInvite(server, other, guild.id)));
                        }
                    }
                    if (guild != null) {
                        // A bank to look at: two tabs (bought, so the log has lines) with a few stacks in the first.
                        guild.bank.balance = 1_000_000;
                        net.ledok.eden_ld.hub.guild.GuildBankService.buyTab(guilds, leader);
                        net.ledok.eden_ld.hub.guild.GuildBankService.buyTab(guilds, leader);
                        if (!guild.bank.tabs.isEmpty()) {
                            var tab = guild.bank.tabs.get(0);
                            tab.set(0, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND, 12));
                            tab.set(1, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.ENDER_PEARL, 16));
                            tab.set(9, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.IRON_SWORD));
                        }
                        report.append(" bank tabs=").append(guild.bank.tabs.size());
                    }
                    server.getPlayerList().getPlayers().forEach(GuildServer::send);
                    if (net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("waystones")) {
                        report.append(DevWaystones.setup(server, leader));
                    }

                    if (!others.isEmpty()) {
                        MailHub.giftMail(leader.getUUID(), others.get(0).getGameProfile().getName(), true,
                                "eden_ld:overgrown", "Happy testing!");
                    }
                    // Safe, bright screenshots: creative, peaceful, frozen midday; every theme unlocked.
                    for (String command : List.of("gamemode creative @a", "difficulty peaceful", "time set 6000",
                            "gamerule doDaylightCycle false", "weather clear", "hub theme unlock @a eden_ld:overgrown",
                            "hub theme unlock @a eden_ld:stone_tablet",
                            "give Player768 minecraft:oak_log 64", "give Player768 minecraft:torch 32")) {
                        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), command);
                    }
                    net.ledok.eden_ld.EdenLD.LOGGER.info("devsocial:{}", report);
                    return "devsocial:" + report;
                    }
    }

    /**
     * Test waystones when the Waystones mod is in the dev mods folders: seven named stones beside the leader
     * (four theirs, three global), all activated for them, the first three favorites. Placed once per world.
     */
    private static final class DevWaystones {
        static String setup(MinecraftServer server, ServerPlayer leader) {
            String[] names = {"Dev Home", "Dev Mine", "Dev Farm", "Dev Village", "Dev Market", "Dev Nether Gate", "Dev Spawn"};
            boolean placed = net.blay09.mods.waystones.api.WaystonesAPI.getAllWaystones(server)
                    .anyMatch(stone -> stone.getName().getString().startsWith("Dev "));
            if (!placed) {
                net.minecraft.core.BlockPos base = leader.blockPosition();
                for (int i = 0; i < names.length; i++) {
                    int index = i;
                    net.blay09.mods.waystones.api.WaystonesAPI.placeWaystone(leader.serverLevel(), base.offset(4 + i * 3, 0, 6),
                            net.blay09.mods.waystones.api.WaystoneStyles.DEFAULT).ifPresent(stone -> {
                        if (stone instanceof net.blay09.mods.waystones.api.MutableWaystone mutable) {
                            mutable.setName(Component.literal(names[index]));
                            mutable.setVisibility(index >= 4 ? net.blay09.mods.waystones.api.WaystoneVisibility.GLOBAL
                                    : net.blay09.mods.waystones.api.WaystoneVisibility.ACTIVATION);
                            if (index < 4) {
                                mutable.setOwnerUid(leader.getUUID());
                                mutable.setOwnerUsername(leader.getGameProfile().getName());
                            }
                        }
                    });
                }
            }
            List<net.blay09.mods.waystones.api.Waystone> dev = net.blay09.mods.waystones.api.WaystonesAPI.getAllWaystones(server)
                    .filter(stone -> stone.getName().getString().startsWith("Dev ")).toList();
            for (net.blay09.mods.waystones.api.Waystone stone : dev) {
                net.blay09.mods.waystones.api.WaystonesAPI.activateWaystone(leader, stone);
            }
            dev.stream().limit(3).forEach(stone ->
                    net.ledok.eden_ld.compat.waystones.WaystonesHook.setFavorite(leader, stone.getWaystoneUid(), true));
            return " waystones=" + dev.size();
        }
    }

    private static String text(Component error) {
        return error == null ? "ok" : error.getString();
    }
}
