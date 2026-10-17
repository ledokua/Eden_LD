package net.ledok.eden_ld.hub.mail;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.ledok.busylib.BusyState;
import net.ledok.eden_ld.EdenLD;
import net.ledok.eden_ld.compat.PlaceholdersCompat;
import net.ledok.eden_ld.hub.HubPayloads;
import net.ledok.eden_ld.minestar.MinestarHook;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.TickTask;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The server side of the hub's Mail tab: mail lives in Economy_LD (storage, claiming, expiry);
 * this sends each player their mailbox — on join, and whenever Economy_LD reports it changed — and
 * carries out what they do in the tab. Written mail gets its placeholders filled for the reader
 * ({@code {player}}, and Placeholder API's {@code %…%} when installed). Pending website purchases
 * (Minestar) show as one extra entry; claiming it runs the usual delivery.
 */
public final class MailHub {

    /** Economy_LD is installed: the Mail tab exists. */
    public static final boolean LOADED = FabricLoader.getInstance().isModLoaded("economy_ld");

    private MailHub() {
    }

    public static void initialize() {
        if (LOADED) {
            Economy.initialize();
        }
    }

    /** Sends the player's mailbox now (nothing without Economy_LD). */
    public static void push(ServerPlayer player) {
        if (LOADED) {
            Economy.push(player);
        }
    }

    /**
     * A gift's mail to {@code to}: who gave which look, and their note — written in every language
     * Eden ships (the recipient may be offline), the Mail tab picks theirs. Nothing without Economy_LD.
     */
    public static void giftMail(java.util.UUID to, String giver, boolean theme, String id, String note) {
        if (LOADED) {
            Economy.giftMail(to, giver, theme, id, note);
        }
    }

    /** The only class touching Economy_LD types. */
    private static final class Economy {
        private static MinecraftServer server;

        static void initialize() {
            ServerLifecycleEvents.SERVER_STARTED.register(started -> server = started);
            ServerLifecycleEvents.SERVER_STOPPED.register(stopped -> server = null);
            ServerPlayNetworking.registerGlobalReceiver(HubPayloads.MailAction.TYPE,
                    (payload, context) -> context.server().execute(() -> handle(context.player(), payload)));
            ServerPlayConnectionEvents.JOIN.register((handler, sender, joined) -> push(handler.player));
            // The wallet bar's balance: a deposit or withdrawal (from the bar, a right-click or a command) resends it.
            net.ledok.economy_ld.api.EconomyEvents.COINS_MOVED.add(player -> {
                if (server == null) {
                    return;
                }
                ServerPlayer online = server.getPlayerList().getPlayer(player);
                if (online != null) {
                    push(online);
                    // The other tabs showing the balance (Home, Wardrobe, Settings).
                    net.ledok.eden_ld.hub.HubServer.sendBalance(online);
                }
            });
            net.ledok.economy_ld.api.EconomyEvents.MAIL_CHANGED.add(player -> {
                if (server == null) {
                    return;
                }
                if (player == null) {
                    server.getPlayerList().getPlayers().forEach(Economy::push);
                } else {
                    ServerPlayer online = server.getPlayerList().getPlayer(player);
                    if (online != null) {
                        push(online);
                    }
                }
            });
        }

        static void giftMail(java.util.UUID to, String giver, boolean theme, String id, String note) {
            Map<String, net.ledok.economy_ld.mail.MailContent.Text> texts = new java.util.LinkedHashMap<>();
            for (String locale : net.ledok.eden_ld.hub.lang.AssetLang.LOCALES) {
                String look = net.ledok.eden_ld.hub.profile.Wardrobe.lookName(locale, theme, id);
                String kind = theme ? "theme" : "background";
                String body = net.ledok.eden_ld.hub.lang.AssetLang.format(locale, "mail.eden_ld.gift." + kind + ".body", giver, look);
                if (!note.isBlank()) {
                    // The giver's own words: shown as typed, not as Markdown.
                    body += "\n\n" + net.ledok.eden_ld.hub.lang.AssetLang.format(locale, "mail.eden_ld.gift.note",
                            net.ledok.vectorlib.text.MarkdownText.escape(note));
                }
                texts.put(locale, new net.ledok.economy_ld.mail.MailContent.Text(
                        net.ledok.eden_ld.hub.lang.AssetLang.format(locale, "mail.eden_ld.gift.title", giver), body));
            }
            var draft = net.ledok.economy_ld.mail.MailDraft.builder(net.ledok.economy_ld.mail.MailKind.SYSTEM,
                            net.ledok.economy_ld.mail.MailContent.written(net.ledok.eden_ld.hub.lang.AssetLang.LOCALES[0], texts))
                    .sender(giver, null)
                    .to(to)
                    .audienceNote("gift")
                    .build();
            net.ledok.economy_ld.mail.EconomyMail.send(draft).exceptionally(error -> {
                net.ledok.eden_ld.EdenLD.LOGGER.error("[Wardrobe] Couldn't mail a gift to {}.", to, error);
                return null;
            });
        }

        static void push(ServerPlayer player) {
            if (!net.ledok.economy_ld.manager.EconomyManager.getInstance().isRunning()) {
                return;
            }
            var balanceFuture = net.ledok.economy_ld.manager.EconomyManager.getInstance()
                    .getBalance(player.getUUID(), player.getGameProfile().getName())
                    .exceptionally(error -> -1L);
            net.ledok.economy_ld.mail.EconomyMail.list(player.getUUID()).thenCombine(balanceFuture, MailAndBalance::new)
                    .whenComplete((both, error) ->
                    player.server.execute(() -> {
                        if (error != null) {
                            EdenLD.LOGGER.warn("[Mail] Can't load the mail of {}", player.getGameProfile().getName(), error);
                            return;
                        }
                        if (player.hasDisconnected()) {
                            return;
                        }
                        List<net.ledok.economy_ld.mail.Mail> mails = both.mails();
                        MinestarHook.fetchPendingPurchases(player, purchases -> {
                            if (player.hasDisconnected()) {
                                return;
                            }
                            List<HubPayloads.MailView> views = new ArrayList<>(mails.size());
                            for (net.ledok.economy_ld.mail.Mail mail : mails) {
                                views.add(view(mail, player));
                            }
                            ServerPlayNetworking.send(player, new HubPayloads.MailData(views, purchases, both.balance()));
                        });
                    }));
        }

        private record MailAndBalance(List<net.ledok.economy_ld.mail.Mail> mails, Long balance) {
        }

        private static HubPayloads.MailView view(net.ledok.economy_ld.mail.Mail mail, ServerPlayer reader) {
            net.ledok.economy_ld.mail.MailContent content = mail.content();
            List<HubPayloads.MailText> texts = new ArrayList<>();
            for (Map.Entry<String, net.ledok.economy_ld.mail.MailContent.Text> text : content.texts().entrySet()) {
                texts.add(new HubPayloads.MailText(text.getKey(), PlaceholdersCompat.fill(text.getValue().title(), reader),
                        PlaceholdersCompat.fill(text.getValue().body(), reader)));
            }
            List<ItemStack> items = new ArrayList<>();
            List<Integer> counts = new ArrayList<>();
            for (net.ledok.economy_ld.mail.MailAttachment item : mail.items()) {
                items.add(item.stack().copyWithCount(1));
                counts.add(item.count());
            }
            String sender = mail.senderName();
            return new HubPayloads.MailView(mail.id(), mail.kind().ordinal(), sender, sender.startsWith("economy_ld."),
                    content.defaultLanguage() == null ? "" : content.defaultLanguage(), texts,
                    content.titleKey() == null ? "" : content.titleKey(),
                    content.bodyKey() == null ? "" : content.bodyKey(), content.args(), items, counts, mail.coins(),
                    mail.createdAt(), mail.expiresAt(), mail.read(), mail.claimed());
        }

        private static void handle(ServerPlayer player, HubPayloads.MailAction action) {
            if (!net.ledok.economy_ld.manager.EconomyManager.getInstance().isRunning()) {
                return;
            }
            java.util.UUID uuid = player.getUUID();
            switch (action.action()) {
                case OPEN -> net.ledok.economy_ld.mail.EconomyMail.markRead(uuid, action.mailId());
                case DELETE -> net.ledok.economy_ld.mail.EconomyMail.delete(uuid, action.mailId())
                        .thenAccept(deleted -> player.server.execute(() -> {
                            if (!deleted) {
                                notice(player, Component.translatable("message.eden_ld.mail.claim_first"));
                            }
                        }));
                case DELETE_READ -> net.ledok.economy_ld.mail.EconomyMail.deleteRead(uuid);
                case CLAIM, CLAIM_ALL -> {
                    // No looting the mailbox mid-fight (a duel, a dungeon run).
                    if (BusyState.isBusy(player)) {
                        notice(player, BusyState.getReasons(player.getUUID()).contains(net.ledok.eden_ld.hub.BusyLocks.TUTORIAL)
                                ? net.ledok.eden_ld.hub.BusyLocks.reason(player)
                                : Component.translatable("message.eden_ld.mail.busy"));
                        return;
                    }
                    var claim = action.action() == HubPayloads.MailAction.Action.CLAIM
                            ? net.ledok.economy_ld.mail.EconomyMail.claim(player, action.mailId())
                            : net.ledok.economy_ld.mail.EconomyMail.claimAll(player);
                    claim.whenComplete((result, error) -> player.server.execute(() -> report(player, result, error)));
                }
                // The wallet bar: Economy_LD moves the coins and toasts the outcome; COINS_MOVED resends the balance.
                case WITHDRAW -> net.ledok.economy_ld.network.CoinNetworking.withdraw(player, action.mailId());
                case DEPOSIT_COINS -> net.ledok.economy_ld.network.CoinNetworking.depositAll(player);
                case CLAIM_SHOP -> {
                    MinestarHook.claimPurchase(player, action.mailId());
                    // The delivery runs on its own; show the new count once it's had time to finish.
                    player.server.tell(new TickTask(player.server.getTickCount() + 40, () -> {
                        if (!player.hasDisconnected()) {
                            push(player);
                        }
                    }));
                }
            }
        }

        private static void report(ServerPlayer player, net.ledok.economy_ld.mail.EconomyMail.ClaimResult result,
                                   Throwable error) {
            if (error != null || result == null) {
                EdenLD.LOGGER.warn("[Mail] A claim failed for {}", player.getGameProfile().getName(), error);
                notice(player, Component.translatable("message.eden_ld.mail.failed"));
                return;
            }
            switch (result.status()) {
                case NO_SPACE -> notice(player, Component.translatable("message.eden_ld.mail.no_space"));
                case GONE -> notice(player, Component.translatable("message.eden_ld.mail.gone"));
                case NOTHING -> {
                }
                case CLAIMED -> {
                    if (result.dropped() > 0) {
                        notice(player, Component.translatable("message.eden_ld.mail.dropped", result.dropped()));
                    }
                    if (result.leftOver() > 0) {
                        notice(player, Component.translatable("message.eden_ld.mail.left_over", result.leftOver()));
                    }
                }
            }
        }

        private static void notice(ServerPlayer player, Component message) {
            ServerPlayNetworking.send(player, new HubPayloads.HubNotice(message));
        }
    }
}
