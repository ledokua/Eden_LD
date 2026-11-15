package net.ledok.eden_ld.hub.moderation;

import com.mojang.brigadier.CommandDispatcher;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.ledok.eden_ld.hub.HubPayloads;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The player's side of {@link ReportDesk}: the hub tab «Звернення» (their submissions with status and staff's reply,
 * and the form), and {@code /report [player]} opening it (a player named: a complaint about them, preselected).
 * Server thread.
 */
public final class ReportsHub {

    /** Players who left this recently can still be named in a complaint. */
    private static final long RECENT_MILLIS = 30 * 60_000L;
    private static final int RECENT_MAX = 100;
    private record Left(String name, long at) {
    }

    /** Recently left players, oldest first. */
    private static final Map<UUID, Left> RECENT = new LinkedHashMap<>();

    private ReportsHub() {
    }

    public static void initialize() {
        ServerPlayNetworking.registerGlobalReceiver(HubPayloads.ReportsRequest.TYPE,
                (payload, context) -> context.server().execute(() -> send(context.player())));
        ServerPlayNetworking.registerGlobalReceiver(HubPayloads.ReportSubmit.TYPE,
                (payload, context) -> context.server().execute(() -> submit(context.player(), payload)));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> net.ledok.eden_ld.util.OnServerThread.run(server,
                () -> remember(handler.getPlayer())));
        ServerLifecycleEvents.SERVER_STOPPED.register(stopped -> RECENT.clear());
        ReportDesk.onChanged(ReportsHub::changed);
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) -> register(dispatcher));
    }

    private static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("report")
                .executes(context -> open(context.getSource(), null))
                .then(Commands.argument("player", GameProfileArgument.gameProfile())
                        .executes(context -> {
                            var profiles = GameProfileArgument.getGameProfiles(context, "player");
                            return open(context.getSource(), profiles.isEmpty() ? null : profiles.iterator().next().getName());
                        })));
    }

    /** Opens the player's tab; with {@code target} (a name), the form as a complaint about them. */
    private static int open(CommandSourceStack source, String target) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            return 0;
        }
        if (!ReportDesk.maySubmit(player)) {
            source.sendFailure(Component.translatable("message.eden_ld.reports.not_allowed"));
            return 0;
        }
        send(player);
        ServerPlayNetworking.send(player, new HubPayloads.OpenHub(target == null ? ReportDesk.PLAYER_TAB
                : ReportDesk.PLAYER_TAB + ":" + target));
        return 1;
    }

    private static void remember(ServerPlayer player) {
        RECENT.remove(player.getUUID());
        RECENT.put(player.getUUID(), new Left(player.getGameProfile().getName(), System.currentTimeMillis()));
        while (RECENT.size() > RECENT_MAX) {
            Iterator<UUID> oldest = RECENT.keySet().iterator();
            oldest.next();
            oldest.remove();
        }
    }

    private static void submit(ServerPlayer player, HubPayloads.ReportSubmit submit) {
        Component refused = ReportDesk.submit(player, submit.kind(), submit.target().orElse(null), submit.text());
        if (refused != null) {
            ServerPlayNetworking.send(player, new HubPayloads.HubNotice(refused, false));
            return;
        }
        ServerPlayNetworking.send(player, new HubPayloads.HubNotice(
                Component.translatable("message.eden_ld.reports.sent"), true));
        send(player);
    }

    /** A report of this player changed (filed, status, reply): their tab follows when they're online. */
    private static void changed(UUID reporter) {
        MinecraftServer server = net.ledok.eden_ld.EdenLD.server();
        ServerPlayer online = server == null ? null : server.getPlayerList().getPlayer(reporter);
        if (online != null && ServerPlayNetworking.canSend(online, HubPayloads.ReportsData.TYPE)) {
            send(online);
        }
    }

    static void send(ServerPlayer player) {
        if (!ServerPlayNetworking.canSend(player, HubPayloads.ReportsData.TYPE)) {
            return;
        }
        MinecraftServer server = player.server;
        List<HubPayloads.ReportEntry> entries = new ArrayList<>();
        for (Report report : ReportDesk.mine(server, player.getUUID())) {
            entries.add(new HubPayloads.ReportEntry(report.id, report.category, report.reference, report.time,
                    report.status, report.text, report.reply, report.targetName));
        }
        List<HubPayloads.ReportCandidate> players = new ArrayList<>();
        for (ServerPlayer online : server.getPlayerList().getPlayers()) {
            if (online != player) {
                players.add(new HubPayloads.ReportCandidate(online.getUUID(), online.getGameProfile().getName(), true));
            }
        }
        long now = System.currentTimeMillis();
        RECENT.entrySet().removeIf(entry -> now - entry.getValue().at() > RECENT_MILLIS);
        RECENT.forEach((uuid, left) -> {
            if (server.getPlayerList().getPlayer(uuid) == null && !uuid.equals(player.getUUID())) {
                players.add(new HubPayloads.ReportCandidate(uuid, left.name(), false));
            }
        });
        players.sort((a, b) -> a.online() != b.online() ? (a.online() ? -1 : 1) : a.name().compareToIgnoreCase(b.name()));
        ServerPlayNetworking.send(player, new HubPayloads.ReportsData(ReportDesk.maySubmit(player), entries, players));
    }
}
