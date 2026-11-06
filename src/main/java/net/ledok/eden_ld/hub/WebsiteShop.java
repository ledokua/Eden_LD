package net.ledok.eden_ld.hub;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.ledok.eden_ld.EdenLD;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * What the Minestar website shop sells, for a purple "Available on the website · 200 LP" line in item tooltips. The
 * list lives on the server ({@code config/eden_ld/website_shop.json}: {@code currency} and {@code items}, item id →
 * {@code price} per purchase and {@code count} items per purchase), so the owner changes it with the website, without a
 * pack update: it's re-read on /reload and sent to every player, and on join. Exact item ids; a missing or broken file
 * shows nothing.
 */
public final class WebsiteShop {

    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve(EdenLD.MOD_ID).resolve("website_shop.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static HubPayloads.WebsitePrices prices = new HubPayloads.WebsitePrices("LP", List.of());

    private WebsiteShop() {
    }

    public static void initialize() {
        ServerLifecycleEvents.SERVER_STARTING.register(server -> load());
        ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((server, resources, success) -> {
            load();
            sendAll(server);
        });
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> server.execute(() -> send(handler.getPlayer())));
    }

    private static void load() {
        try {
            if (!Files.exists(FILE)) {
                Files.createDirectories(FILE.getParent());
                JsonObject empty = new JsonObject();
                empty.addProperty("_comment", "Minestar website shop: item id -> price (in currency) and count per purchase. "
                        + "Shown as a purple line in item tooltips; /reload to apply.");
                empty.addProperty("currency", "LP");
                empty.add("items", new JsonObject());
                Files.writeString(FILE, GSON.toJson(empty));
            }
            JsonObject root = GSON.fromJson(Files.readString(FILE), JsonObject.class);
            String currency = root.has("currency") ? root.get("currency").getAsString() : "LP";
            List<HubPayloads.WebsitePrice> list = new ArrayList<>();
            JsonObject items = root.has("items") ? root.getAsJsonObject("items") : new JsonObject();
            for (Map.Entry<String, JsonElement> entry : items.entrySet()) {
                JsonObject item = entry.getValue().getAsJsonObject();
                int price = item.has("price") ? item.get("price").getAsInt() : 0;
                int count = item.has("count") ? item.get("count").getAsInt() : 1;
                if (price > 0) {
                    list.add(new HubPayloads.WebsitePrice(entry.getKey(), price, Math.max(1, count)));
                }
            }
            prices = new HubPayloads.WebsitePrices(currency, List.copyOf(list));
            EdenLD.LOGGER.info("[Website shop] {} item(s) priced for tooltips.", list.size());
        } catch (Exception e) {
            prices = new HubPayloads.WebsitePrices("LP", List.of());
            EdenLD.LOGGER.error("[Website shop] Can't read {}: no website prices in tooltips.", FILE, e);
        }
    }

    private static void send(ServerPlayer player) {
        if (!player.hasDisconnected() && ServerPlayNetworking.canSend(player, HubPayloads.WebsitePrices.TYPE)) {
            ServerPlayNetworking.send(player, prices);
        }
    }

    private static void sendAll(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            send(player);
        }
    }
}
