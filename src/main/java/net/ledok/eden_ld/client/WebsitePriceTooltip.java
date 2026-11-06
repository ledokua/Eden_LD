package net.ledok.eden_ld.client;

import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.ledok.eden_ld.hub.HubPayloads;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;

import java.util.HashMap;
import java.util.Map;

/**
 * The purple "Available on the website · 200 LP" (or "· 20 LP for 8") line at the end of the tooltip of items the
 * Minestar website shop sells, from the list the server sends ({@code WebsiteShop}). Nothing without that list.
 */
public final class WebsitePriceTooltip {

    /** Prime purple. */
    private static final int COLOR = 0xB070F0;

    private static volatile Map<String, HubPayloads.WebsitePrice> prices = Map.of();
    private static volatile String currency = "LP";

    private WebsitePriceTooltip() {
    }

    public static void initialize() {
        ClientPlayNetworking.registerGlobalReceiver(HubPayloads.WebsitePrices.TYPE, (payload, context) -> {
            Map<String, HubPayloads.WebsitePrice> byItem = new HashMap<>();
            payload.prices().forEach(price -> byItem.put(price.item(), price));
            prices = Map.copyOf(byItem);
            currency = payload.currency();
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> prices = Map.of());
        ItemTooltipCallback.EVENT.register((stack, context, type, lines) -> {
            Map<String, HubPayloads.WebsitePrice> current = prices;
            if (current.isEmpty() || stack.isEmpty()) {
                return;
            }
            HubPayloads.WebsitePrice price = current.get(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
            if (price == null) {
                return;
            }
            Component line = price.count() > 1
                    ? Component.translatable("tooltip.eden_ld.website_shop.pack", price.price(), currency, price.count())
                    : Component.translatable("tooltip.eden_ld.website_shop", price.price(), currency);
            lines.add(line.copy().withStyle(Style.EMPTY.withColor(COLOR)));
        });
    }
}
