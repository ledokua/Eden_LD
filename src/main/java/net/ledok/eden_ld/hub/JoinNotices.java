package net.ledok.eden_ld.hub;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.ledok.eden_ld.hub.mail.MailHub;
import net.ledok.eden_ld.hub.moderation.ReportDesk;
import net.ledok.eden_ld.tutorial.Tutorial;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Chat lines a player gets a few seconds after joining, so they aren't lost in the join spam: open reports for
 * staff who handle them, and unread mail (unless the player is in the tutorial or turned the mail notification off,
 * Settings → Notifications → Unread mail). Server thread.
 */
public final class JoinNotices {

    /** About 3 s after joining. */
    private static final int DELAY_TICKS = 60;
    /** Players waiting for their lines, and the server tick they're due at. */
    private static final Map<UUID, Integer> DUE = new HashMap<>();

    private JoinNotices() {
    }

    public static void initialize() {
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
                server.execute(() -> DUE.put(handler.getPlayer().getUUID(), server.getTickCount() + DELAY_TICKS)));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                server.execute(() -> DUE.remove(handler.getPlayer().getUUID())));
        ServerLifecycleEvents.SERVER_STOPPED.register(stopped -> DUE.clear());
        ServerTickEvents.END_SERVER_TICK.register(JoinNotices::tick);
    }

    private static void tick(MinecraftServer server) {
        if (DUE.isEmpty()) {
            return;
        }
        int now = server.getTickCount();
        DUE.entrySet().removeIf(entry -> {
            if (entry.getValue() > now) {
                return false;
            }
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player != null) {
                send(server, player);
            }
            return true;
        });
    }

    private static void send(MinecraftServer server, ServerPlayer player) {
        ReportDesk.joinLine(player);
        boolean mailOff = (HubSettings.intSetting(server, player.getUUID(), HubSettings.NOTIFY_OFF)
                & (1 << HubSettings.NOTIFY_MAIL_BIT)) != 0;
        if (!mailOff && !Tutorial.isActive(player)) {
            MailHub.unreadLine(player);
        }
    }
}
