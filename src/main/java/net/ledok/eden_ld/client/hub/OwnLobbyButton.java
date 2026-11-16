package net.ledok.eden_ld.client.hub;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.ledok.eden_ld.client.EdenParchment;
import net.ledok.eden_ld.hub.HubPayloads;
import net.ledok.vectorlib.client.canvas.layout.Align;
import net.ledok.vectorlib.client.canvas.layout.Flex;
import net.ledok.vectorlib.client.canvas.layout.Sizing;
import net.ledok.vectorlib.client.canvas.widget.Button;
import net.minecraft.network.chat.Component;

/**
 * The Activities tab's "My lobby" button: while the player is in an Arenas_LD lobby (4.21.0+ on the server), it says
 * which one and how full it is, and opens that lobby's screen from anywhere; during the lobby's run it only says so.
 * Asked every 2 seconds while the tab shows, so joining, leaving and filling up show without reopening the hub.
 */
public final class OwnLobbyButton {

    private static final int POLL_TICKS = 40;

    private static HubPayloads.OwnLobbyData data = HubPayloads.OwnLobbyData.NONE;
    private static boolean polling;
    private static int ticks;

    private OwnLobbyButton() {
    }

    private static Component tr(String key, Object... args) {
        return Component.translatable("gui.eden_ld.hub.activities." + key, args);
    }

    public static void initialize() {
        // The cards' lobby badges (1.3.98): with the tab's data, then when they change (answering this poll).
        ClientPlayNetworking.registerGlobalReceiver(HubPayloads.ActivityLobbies.TYPE, (payload, context) -> {
            java.util.Map<String, java.util.List<HubPayloads.LobbyLine>> lobbies = new java.util.HashMap<>();
            for (HubPayloads.LobbyBadge badge : payload.badges()) {
                lobbies.put(badge.activity().toString(), java.util.List.copyOf(badge.lobbies()));
            }
            if (lobbies.equals(HubState.activityLobbies) && payload.available() == HubState.lobbiesAvailable
                    && payload.creates() == HubState.lobbiesCreate) {
                return;
            }
            HubState.activityLobbies = lobbies;
            HubState.lobbiesAvailable = payload.available();
            HubState.lobbiesCreate = payload.creates();
            if (context.client().screen instanceof HubScreen screen && HubState.lastTab == HubTab.ACTIVITIES) {
                screen.onActivitiesData();
            }
        });
        // The «Створити лобі» panel's options for an activity (1.3.103).
        ClientPlayNetworking.registerGlobalReceiver(HubPayloads.ActivityInfoData.TYPE, (payload, context) -> {
            if (LobbyBrowser.onInfo(payload) && context.client().screen instanceof HubScreen screen
                    && HubState.lastTab == HubTab.ACTIVITIES) {
                screen.onActivitiesData();
            }
        });
        ClientPlayNetworking.registerGlobalReceiver(HubPayloads.OwnLobbyData.TYPE, (payload, context) -> {
            if (payload.equals(data)) {
                return;
            }
            data = payload;
            LobbyBrowser.onOwnLobby(payload);
            if (context.client().screen instanceof HubScreen screen) {
                screen.onActivitiesData();
            }
        });
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            boolean showing = client.screen instanceof HubScreen && HubState.lastTab == HubTab.ACTIVITIES
                    && ClientPlayNetworking.canSend(HubPayloads.OwnLobbyRequest.TYPE);
            if (!showing) {
                polling = false;
                return;
            }
            // At once when the tab opens, then every 2 seconds.
            if (!polling || ++ticks >= POLL_TICKS) {
                polling = true;
                ticks = 0;
                ClientPlayNetworking.send(HubPayloads.OwnLobbyRequest.INSTANCE);
            }
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            data = HubPayloads.OwnLobbyData.NONE;
            LobbyBrowser.reset();
        });
    }

    /** The lobby the player is in, as the server last said. */
    static HubPayloads.OwnLobbyData data() {
        return data;
    }

    /** The row at the top of the Activities tab; nothing when the player isn't in a lobby. */
    static void build(Flex content) {
        Flex row = row();
        if (row != null) {
            row.sizing(Sizing.fill(), Sizing.content());
            content.item(row);
        }
    }

    /** The own lobby's button (or "in a run" line), content-wide; null when the player isn't in a lobby. */
    @org.jetbrains.annotations.Nullable
    static Flex row() {
        HubPayloads.OwnLobbyData lobby = data;
        if (!lobby.present()) {
            return null;
        }
        Flex row = Flex.row().gap(4).alignItems(Align.CENTER);
        if (lobby.inRun()) {
            row.item(EdenParchment.text(tr("own_lobby.in_run", lobby.name()), EdenParchment.INK_MID));
        } else {
            Component label = tr("own_lobby", lobby.name(), lobby.members(), lobby.maxMembers());
            Button button = EdenParchment.button(label, EdenParchment.fitWidth(label, 80), 14,
                    () -> ClientPlayNetworking.send(HubPayloads.OwnLobbyOpen.INSTANCE));
            button.tooltip(tr("own_lobby.hint"));
            row.item(button);
        }
        return row;
    }
}
