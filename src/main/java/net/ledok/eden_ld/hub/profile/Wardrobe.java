package net.ledok.eden_ld.hub.profile;

import com.mojang.authlib.GameProfile;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.ledok.eden_ld.EdenLD;
import net.ledok.eden_ld.compat.EconomyCompat;
import net.ledok.eden_ld.hub.HubPayloads;
import net.ledok.eden_ld.hub.HubPayloads.LookRule;
import net.ledok.eden_ld.hub.HubPayloads.WardrobeItem;
import net.ledok.eden_ld.hub.HubServer;
import net.ledok.eden_ld.hub.HubSettings;
import net.ledok.eden_ld.hub.lang.AssetLang;
import net.ledok.eden_ld.hub.mail.MailHub;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Server side of the hub's Wardrobe tab: every profile background and hub theme the player owns,
 * wears, or can see for sale ({@link LookRules}), equipping and buying through the existing paths,
 * and gifting — the giver pays the price, the recipient (online or not) gets it unlocked and a
 * mail saying who sent it. Built to take more categories later (character cosmetics).
 */
public final class Wardrobe {

    private static final int MAX_NOTE = 120;

    private Wardrobe() {
    }

    public static void initialize() {
        ServerPlayNetworking.registerGlobalReceiver(HubPayloads.WardrobeRequest.TYPE,
                (payload, context) -> context.server().execute(() -> send(context.player())));
        ServerPlayNetworking.registerGlobalReceiver(HubPayloads.LookGift.TYPE,
                (payload, context) -> context.server().execute(() -> gift(context.player(), payload)));
    }

    /** The player's wardrobe (after opening the tab, equipping, buying, or being given something). */
    public static void send(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null || player.hasDisconnected()
                || !ServerPlayNetworking.canSend(player, HubPayloads.WardrobeData.TYPE)) {
            return;
        }
        UUID uuid = player.getUUID();
        ServerPlayNetworking.send(player, new HubPayloads.WardrobeData(
                items(server, LookRules.Kind.BACKGROUND, HubSettings.availableBackgrounds(server, uuid),
                        HubSettings.background(server, uuid)),
                items(server, LookRules.Kind.THEME, HubSettings.availableThemes(server, uuid),
                        HubSettings.theme(server, uuid)),
                EconomyCompat.available()));
    }

    private static List<WardrobeItem> items(MinecraftServer server, LookRules.Kind kind, List<String> available, String worn) {
        LookRules rules = LookRules.get(server, kind);
        List<WardrobeItem> items = new ArrayList<>();
        for (String id : available) {
            if (id.isEmpty()) {
                continue;
            }
            items.add(new WardrobeItem(id, id.equals(worn) ? WardrobeItem.WORN : WardrobeItem.OWNED, rules.rule(id).price()));
        }
        for (HubPayloads.LookOffer offer : rules.offersFor(available)) {
            items.add(new WardrobeItem(offer.id(), offer.price() > 0 ? WardrobeItem.FOR_SALE : WardrobeItem.LOCKED,
                    offer.price()));
        }
        return items;
    }

    private static void gift(ServerPlayer giver, HubPayloads.LookGift gift) {
        if (net.ledok.eden_ld.hub.BusyLocks.refuse(giver)) {
            return;
        }
        MinecraftServer server = giver.server;
        LookRules.Kind kind = LookRules.Kind.of(gift.theme());
        LookRule rule = LookRules.get(server, kind).rule(gift.id());
        // Only what's for sale can be given: free looks need no gift, hidden ones aren't public.
        if (!rule.locked() || rule.price() <= 0 || !EconomyCompat.available()) {
            notice(giver, Component.translatable("message.eden_ld.gift.not_giftable"), false);
            return;
        }
        GameProfile recipient = server.getProfileCache() == null ? null
                : server.getProfileCache().get(gift.recipient().trim()).orElse(null);
        if (recipient == null) {
            notice(giver, Component.translatable("message.eden_ld.gift.unknown", gift.recipient()), false);
            return;
        }
        if (recipient.getId().equals(giver.getUUID())) {
            notice(giver, Component.translatable("message.eden_ld.gift.self"), false);
            return;
        }
        UUID to = recipient.getId();
        List<String> theirs = kind == LookRules.Kind.THEME ? HubSettings.availableThemes(server, to)
                : HubSettings.availableBackgrounds(server, to);
        if (theirs.contains(gift.id())) {
            notice(giver, Component.translatable("message.eden_ld.gift.has_it", recipient.getName()), false);
            return;
        }
        String note = gift.note().strip();
        if (note.length() > MAX_NOTE) {
            note = note.substring(0, MAX_NOTE);
        }
        String message = note;
        EconomyCompat.take(giver, rule.price(), paid -> {
            if (!paid) {
                notice(giver, Component.translatable("message.eden_ld.gift.cant_afford", rule.price()), false);
                return;
            }
            if (kind == LookRules.Kind.THEME) {
                HubSettings.setThemeUnlocked(server, to, gift.id(), true);
            } else {
                HubSettings.setBackgroundUnlocked(server, to, gift.id(), true);
            }
            EdenLD.LOGGER.info("{} gave the {} {} to {} for {} LC", giver.getGameProfile().getName(),
                    kind.name().toLowerCase(java.util.Locale.ROOT), gift.id(), recipient.getName(), rule.price());
            MailHub.giftMail(to, giver.getGameProfile().getName(), kind == LookRules.Kind.THEME, gift.id(), message);
            notice(giver, Component.translatable("message.eden_ld.gift.sent", recipient.getName()), true);
            ServerPlayer online = server.getPlayerList().getPlayer(to);
            if (online != null) {
                online.sendSystemMessage(Component.translatable("message.eden_ld.gift.received",
                        giver.getGameProfile().getName()).withStyle(ChatFormatting.GOLD));
                if (kind == LookRules.Kind.THEME) {
                    HubServer.pushSettings(server, to);
                } else {
                    ProfileServer.send(online, to);
                }
                send(online);
            }
            send(giver);
        });
    }

    /** A look's name in a language, for mail written once in every language. */
    public static String lookName(String locale, boolean theme, String id) {
        int colon = id.indexOf(':');
        String key = (theme ? "hub_theme." : "profile_background.")
                + (colon < 0 ? "minecraft." + id : id.substring(0, colon) + "." + id.substring(colon + 1));
        String name = AssetLang.get(locale, key);
        return name != null ? name : id;
    }

    private static void notice(ServerPlayer player, Component message, boolean ok) {
        ServerPlayNetworking.send(player, new HubPayloads.HubNotice(message, ok));
    }
}
