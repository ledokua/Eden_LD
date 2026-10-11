package net.ledok.eden_ld.hub;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.ledok.eden_ld.EdenLD;
import net.ledok.eden_ld.compat.NpcsCompat;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.Locale;

/**
 * The hub tabs' tours: short NPCs_LD dialogs that walk through a tab, highlighting its parts ({@code HubSpotlights}).
 * Config {@code tutorial.tab_tours} maps a tab to its dialog; the help window shows ▶ on those tabs, and ▶ starts it
 * here. Any time, as often as wanted.
 */
public final class HubTours {

    private HubTours() {
    }

    public static void initialize() {
        ServerPlayNetworking.registerGlobalReceiver(HubPayloads.TourStart.TYPE,
                (payload, context) -> context.server().execute(() -> start(context.player(), payload.tab())));
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> server.execute(() -> send(handler.getPlayer())));
        // tutorial.json (and with it the tour list) is re-read on /reload.
        ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((server, resources, success) -> sendAll(server));
    }

    private static List<String> tabs() {
        return NpcsCompat.LOADED ? List.copyOf(EdenLD.CONFIG.tutorial.tab_tours.keySet()) : List.of();
    }

    static void send(ServerPlayer player) {
        if (!player.hasDisconnected() && ServerPlayNetworking.canSend(player, HubPayloads.HubTours.TYPE)) {
            ServerPlayNetworking.send(player, new HubPayloads.HubTours(tabs()));
        }
    }

    private static void sendAll(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            send(player);
        }
    }

    private static void start(ServerPlayer player, String tab) {
        String dialog = EdenLD.CONFIG.tutorial.tab_tours.get(tab.toLowerCase(Locale.ROOT));
        ResourceLocation id = dialog == null ? null : ResourceLocation.tryParse(dialog);
        if (id == null || !NpcsCompat.start(player, id)) {
            ServerPlayNetworking.send(player, new HubPayloads.HubNotice(Component.translatable("message.eden_ld.tour.unavailable")));
        }
    }
}
