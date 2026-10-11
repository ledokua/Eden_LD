package net.ledok.eden_ld.client.hub;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.ledok.eden_ld.client.EdenParchment;
import net.ledok.eden_ld.hub.HubPayloads;
import net.ledok.eden_ld.hub.HubPayloads.MailAction;
import net.ledok.eden_ld.hub.HubPayloads.MailData;
import net.ledok.eden_ld.hub.HubPayloads.MailView;
import net.ledok.vectorlib.client.canvas.ItemStackNode;
import net.ledok.vectorlib.client.canvas.Shapes;
import net.ledok.vectorlib.client.canvas.Stroke;
import net.ledok.vectorlib.client.canvas.TextNode;
import net.ledok.vectorlib.client.canvas.layout.Align;
import net.ledok.vectorlib.client.canvas.layout.Flex;
import net.ledok.vectorlib.client.canvas.layout.Insets;
import net.ledok.vectorlib.client.canvas.layout.Justify;
import net.ledok.vectorlib.client.canvas.layout.Sizing;
import net.ledok.vectorlib.client.canvas.widget.Button;
import net.ledok.vectorlib.client.canvas.widget.Label;
import net.ledok.vectorlib.client.canvas.widget.TextField;
import net.ledok.vectorlib.client.canvas.widget.ScrollPanel;
import net.ledok.vectorlib.client.canvas.widget.UiSounds;
import net.ledok.vectorlib.client.canvas.widget.Widget;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.ledok.vectorlib.text.MarkdownText;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The Mail tab (mail is Economy_LD's): the list on the left — kind, title, date, time left, an
 * unread dot and an attachment marker — and the open mail on the right: its text, items and coins,
 * Claim and Delete. Filters (all / unread / with items), Claim all and Delete read on top. Pending
 * website purchases show as one pinned entry whose Claim runs the delivery. A wallet bar along the
 * bottom shows the balance and turns it into Economy_LD coins and back (Withdraw / Deposit all).
 * <p>
 * Written mail shows the reader's language, else its default, as Markdown (VectorLib's
 * {@link MarkdownText}: {@code **bold**}, {@code {#ff8800}color{/}}, headings, sizes…). Opening a
 * mail — or the tab showing it first — marks it read.
 */
final class MailTab {

    private static final float LANE = 6;
    /** The list's width (rows; its scroll lane is extra) until the player drags the divider beside it. */
    private static final float LIST_WIDTH = 160;
    private static final float LIST_MIN = 120;
    private static final float DETAIL_MIN = 190;
    private static final float GAP = 6;
    /** The open mail's panel at the smallest hub; the Moderation window's preview copies it exactly. */
    static final float DETAIL_WIDTH = HubScreen.MIN_WIDTH - 24 - LIST_WIDTH - LANE - GAP;
    /** Website purchases are pinned rows keyed below zero: {@code -1 - purchase id} (mail ids are positive). */
    private static long shopKey(long purchaseId) {
        return -1 - purchaseId;
    }

    private static boolean isShop(long key) {
        return key < 0 && key != Long.MIN_VALUE;
    }
    /** Kind icon colors, by Economy_LD's MailKind order: reward, shop, admin, system. */
    private static final int[] KIND_COLORS = {0xFFE8B84A, 0xFF4CC46A, 0xFFD2463C, 0xFF3A8FE0};

    private enum Filter { ALL, UNREAD, ITEMS }

    private static Filter filter = Filter.ALL;
    private static long selected = Long.MIN_VALUE;
    /** Mails already reported opened (the server's echo takes a moment). */
    private static final java.util.Set<Long> OPENED = new java.util.HashSet<>();

    private MailTab() {
    }

    private static Component tr(String key, Object... args) {
        return Component.translatable("gui.eden_ld.hub.mail." + key, args);
    }

    static void build(HubScreen screen, Flex content) {
        MailData data = HubState.mailData;
        if (data == null) {
            content.item(EdenParchment.text(Component.translatable("gui.eden_ld.hub.home.loading"), EdenParchment.INK_DIM));
            return;
        }
        List<MailView> shown = new ArrayList<>();
        for (MailView mail : data.mails()) {
            if (filter == Filter.UNREAD && mail.read() || filter == Filter.ITEMS && !mail.hasUnclaimed()) {
                continue;
            }
            shown.add(mail);
        }
        // Waiting website purchases: pinned on top in every filter (they count as unread and hold items).
        List<HubPayloads.ShopPurchase> purchases = data.purchases();
        boolean valid = isShop(selected) ? purchases.stream().anyMatch(p -> shopKey(p.id()) == selected)
                : shown.stream().anyMatch(m -> m.id() == selected);
        if (selected == Long.MIN_VALUE || !valid) {
            selected = !purchases.isEmpty() ? shopKey(purchases.get(0).id())
                    : shown.isEmpty() ? Long.MIN_VALUE : shown.get(0).id();
        }

        content.item(header(screen, data));
        content.item(EdenParchment.hairline());

        // List | open mail, the divider dragged to give the mail more room (or the list).
        var split = screen.splitLeft("mail/list", LIST_WIDTH + LANE, LIST_MIN + LANE, DETAIL_MIN, GAP);
        float[] widths = split.sizesFor(HubScreen.innerWidth());
        float listWidth = widths[0] - LANE;
        float detailWidth = widths[1];

        Flex list = Flex.column().gap(2);
        for (HubPayloads.ShopPurchase purchase : purchases) {
            list.item(new MailRow(screen, shopKey(purchase.id()), 1, purchaseTitle(purchase), tr("shop.sender"),
                    false, true, false, listWidth));
        }
        for (MailView mail : shown) {
            list.item(new MailRow(screen, mail.id(), mail.kind(), title(mail), rowDate(mail), !mail.read(),
                    mail.hasUnclaimed(), expiringSoon(mail), listWidth));
        }
        if (purchases.isEmpty() && shown.isEmpty()) {
            list.item(EdenParchment.text(tr(data.mails().isEmpty() ? "empty" : "no_matches"), EdenParchment.INK_DIM,
                    (int) listWidth));
        }
        ScrollPanel listPanel = HubScroll.panel("mail/list", listWidth + LANE, 100, list);
        listPanel.sizing(Sizing.fill(), Sizing.expand());
        Flex listColumn = Flex.column().gap(4);
        listColumn.sizing(Sizing.fill(), Sizing.fill());
        listColumn.item(listPanel);
        listColumn.item(listActions(data, listWidth + LANE));
        split.first().item(listColumn);

        Flex detail = Flex.column().gap(4).padding(Insets.of(4, 6, 4, 6));
        // As tall as the list column, so its buttons line up with Claim all / Delete read.
        detail.sizing(Sizing.fill(), Sizing.fill());
        detail.background(EdenParchment.framed(EdenParchment.ROW_BG, EdenParchment.HAIRLINE));
        if (isShop(selected)) {
            purchases.stream().filter(p -> shopKey(p.id()) == selected).findFirst()
                    .ifPresent(purchase -> shopDetail(detail, purchase, purchases.size(), detailWidth));
        } else if (shown.stream().anyMatch(m -> m.id() == selected && !m.read()) && OPENED.add(selected)) {
            // Shown first (not clicked): it counts as opened too.
            send(MailAction.Action.OPEN, selected);
            shown.stream().filter(m -> m.id() == selected).findFirst().ifPresent(mail -> mailDetail(detail, mail, detailWidth));
        } else {
            shown.stream().filter(m -> m.id() == selected).findFirst().ifPresentOrElse(
                    mail -> mailDetail(detail, mail, detailWidth),
                    () -> detail.item(EdenParchment.text(tr("pick"), EdenParchment.INK_DIM)));
        }
        split.second().item(detail);
        content.item(split);
        content.item(walletBar(screen, data));
    }

    // ---------------------------------------------------------------- wallet bar

    /** What the player typed in the wallet bar's amount field; kept across the tab's rebuilds. */
    private static String withdrawText = "";
    private static TextField withdrawField;

    /**
     * Under the mail: the wallet balance, an amount and Withdraw (paid out as Economy_LD coins), and
     * Deposit all (every coin and money bag carried goes into the wallet). Economy_LD toasts the outcome.
     */
    private static Flex walletBar(HubScreen screen, MailData data) {
        Flex bar = Flex.row().gap(4).padding(Insets.of(3, 6, 3, 6)).alignItems(Align.CENTER);
        bar.sizing(Sizing.fill(), Sizing.content());
        bar.background(EdenParchment.framed(EdenParchment.ROW_BG, EdenParchment.HAIRLINE));
        Component balance = data.balance() < 0 ? tr("wallet.unknown")
                : tr("wallet.balance", String.format(Locale.ROOT, "%,d", data.balance()));
        Label label = Label.of(60, balance);
        label.color(EdenParchment.INK);
        label.tooltipWhenCut(true);
        label.sizing(Sizing.expand(), Sizing.content());
        bar.item(label);

        boolean hadFocus = withdrawField != null && withdrawField.isFocused();
        TextField amount = new TextField(70, withdrawText, text -> withdrawText = text);
        amount.sizing(Sizing.fixed(70), Sizing.fixed(14));
        amount.maxLength(16);
        amount.filter(text -> text.chars().allMatch(Character::isDigit));
        amount.hint(tr("wallet.amount_hint").getString());
        bar.item(amount);
        Button withdraw = EdenParchment.fitButton(tr("wallet.withdraw"), 50, 14, MailTab::withdraw);
        withdraw.tooltip(tr("wallet.withdraw_tip"));
        bar.item(withdraw);
        Button deposit = EdenParchment.fitButton(tr("wallet.deposit_all"), 50, 14,
                () -> send(MailAction.Action.DEPOSIT_COINS, 0));
        deposit.tooltip(tr("wallet.deposit_tip"));
        bar.item(deposit);

        withdrawField = amount;
        if (hadFocus) {
            screen.canvas().focus(amount);
        }
        return bar;
    }

    private static void withdraw() {
        long amount;
        try {
            amount = Long.parseLong(withdrawText.trim());
        } catch (NumberFormatException e) {
            return;
        }
        if (amount > 0) {
            send(MailAction.Action.WITHDRAW, amount);
            withdrawText = "";
            if (withdrawField != null) {
                withdrawField.text("");
            }
        }
    }

    // ---------------------------------------------------------------- header

    private static Flex header(HubScreen screen, MailData data) {
        Flex header = Flex.row().gap(4).alignItems(Align.CENTER);
        header.sizing(Sizing.fill(), Sizing.content());
        header.item(EdenParchment.text(tr("title", data.mails().size()), EdenParchment.INK_MID));
        header.item(EdenParchment.tabs(List.of(Filter.values()),
                candidate -> tr("filter." + candidate.name().toLowerCase(Locale.ROOT)), filter, candidate -> {
                    filter = candidate;
                    screen.onMailData();
                }, 30, "mail/filter"));
        return header;
    }

    /**
     * Claim all and Delete read, under the list: side by side when both labels fit its width,
     * else one above the other.
     */
    private static Flex listActions(MailData data, float width) {
        Component claimLabel = tr("claim_all");
        Component deleteLabel = tr("delete_read");
        boolean row = EdenParchment.fitWidth(claimLabel, 50) + EdenParchment.fitWidth(deleteLabel, 50) + 4 <= width;
        float each = row ? (width - 4) / 2 : width;
        Flex actions = row ? Flex.row().gap(4) : Flex.column().gap(3);
        actions.sizing(Sizing.fixed(width), Sizing.content());
        Button claimAll = new Button(each, 14, claimLabel, () -> send(MailAction.Action.CLAIM_ALL, 0));
        claimAll.enabled(data.mails().stream().anyMatch(MailView::hasUnclaimed));
        actions.item(claimAll);
        Button deleteRead = new Button(each, 14, deleteLabel, () -> send(MailAction.Action.DELETE_READ, 0));
        deleteRead.enabled(data.mails().stream().anyMatch(m -> m.read() && !m.hasUnclaimed()));
        actions.item(deleteRead);
        return actions;
    }

    // ---------------------------------------------------------------- the open mail

    private static void mailDetail(Flex detail, MailView mail, float width) {
        Flex scrolled = content(mail, (int) width - 12 - LANE_INNER);
        ScrollPanel panel = HubScroll.panel("mail/detail/" + mail.id(), width - 12, 100, scrolled);
        panel.sizing(Sizing.fill(), Sizing.expand());
        detail.item(panel);
        detail.item(actions(mail));
    }

    /**
     * An open mail's words and attachments at {@code textWidth}, as the tab shows them — the
     * Moderation window's composer previews a draft with it.
     */
    static Flex content(MailView mail, int textWidth) {
        Flex scrolled = Flex.column().gap(4);
        // The full text width, so the separator under the header runs edge to edge.
        scrolled.sizing(Sizing.fixed(textWidth), Sizing.content());
        // Authored words skip the theme's shadow: a shadow doubles colored and dark text.
        scrolled.item(EdenParchment.text(title(mail), EdenParchment.INK, textWidth).shadow(false));
        scrolled.item(EdenParchment.text(tr("from", sender(mail), date(mail.createdAt())), EdenParchment.INK_DIM, textWidth));
        scrolled.item(EdenParchment.text(expiry(mail), expiringSoon(mail) ? EdenParchment.DANGER : EdenParchment.INK_DIM,
                textWidth));
        scrolled.item(EdenParchment.hairline());
        for (Component paragraph : body(mail)) {
            scrolled.item(EdenParchment.text(paragraph, EdenParchment.INK_MID, textWidth).shadow(false));
        }
        if (!mail.items().isEmpty() || mail.coins() > 0) {
            scrolled.item(EdenParchment.spacer(2));
            scrolled.item(EdenParchment.text(tr(mail.claimed() ? "attachments_claimed" : "attachments"),
                    EdenParchment.INK_MID));
            scrolled.item(attachments(mail, textWidth));
        }
        return scrolled;
    }

    /** Width the detail's scrollbar lane takes from the text. */
    private static final int LANE_INNER = 8;
    /** The open mail's text width at the smallest hub (the Moderation preview wraps at exactly this). */
    static final int TEXT_WIDTH = (int) DETAIL_WIDTH - 12 - LANE_INNER;

    private static Flex attachments(MailView mail, int width) {
        Flex grid = Flex.column().gap(3);
        Flex row = null;
        int perRow = Math.max(1, width / 26);
        for (int i = 0; i < mail.items().size(); i++) {
            if (i % perRow == 0) {
                row = Flex.row().gap(8).alignItems(Align.CENTER);
                grid.item(row);
            }
            int count = i < mail.counts().size() ? mail.counts().get(i) : 1;
            ItemStackNode icon = ItemStackNode.of(mail.items().get(i).copyWithCount(Math.max(1, count)), 0, 0, 18);
            row.item(icon);
        }
        if (mail.coins() > 0) {
            grid.item(EdenParchment.text(tr("coins", String.format(Locale.ROOT, "%,d", mail.coins())),
                    mail.claimed() ? EdenParchment.INK_DIM : EdenParchment.GOOD));
        }
        return grid;
    }

    private static Flex actions(MailView mail) {
        Flex row = Flex.row().gap(4).justify(Justify.END).alignItems(Align.CENTER);
        row.sizing(Sizing.fill(), Sizing.content());
        if (mail.hasUnclaimed()) {
            row.item(EdenParchment.fitButton(tr("claim"), 60, 14, () -> send(MailAction.Action.CLAIM, mail.id())));
        } else {
            if (!mail.items().isEmpty() || mail.coins() > 0) {
                row.item(EdenParchment.text(tr("claimed"), EdenParchment.GOOD));
            }
            row.item(EdenParchment.fitButton(tr("delete"), 50, 14, () -> send(MailAction.Action.DELETE, mail.id())));
        }
        return row;
    }

    /** The shop's name for it, "×N" when more than one; a plain name when the website gave none. */
    private static Component purchaseTitle(HubPayloads.ShopPurchase purchase) {
        Component name = purchase.name().isBlank() ? tr("shop.unnamed") : Component.literal(purchase.name());
        return purchase.quantity() > 1 ? Component.empty().append(name).append(" ×" + purchase.quantity()) : name;
    }

    private static void shopDetail(Flex detail, HubPayloads.ShopPurchase purchase, int pending, float width) {
        int textWidth = (int) width - 12;
        detail.item(EdenParchment.text(purchaseTitle(purchase), EdenParchment.INK, textWidth));
        detail.item(EdenParchment.text(tr("from", tr("shop.sender"), ""), EdenParchment.INK_DIM, textWidth));
        detail.item(EdenParchment.hairline());
        detail.item(EdenParchment.text(tr("shop.body"), EdenParchment.INK_MID, textWidth));
        Flex spacer = Flex.column();
        spacer.sizing(Sizing.fill(), Sizing.expand());
        detail.item(spacer);
        Flex row = Flex.row().gap(4).justify(Justify.END);
        row.sizing(Sizing.fill(), Sizing.content());
        if (pending > 1) {
            row.item(EdenParchment.fitButton(tr("shop.claim_all", pending), 60, 14, () -> send(MailAction.Action.CLAIM_SHOP, 0)));
        }
        row.item(EdenParchment.fitButton(tr("claim"), 60, 14, () -> send(MailAction.Action.CLAIM_SHOP, purchase.id())));
        detail.item(row);
    }

    // ---------------------------------------------------------------- words

    /** The reader's language version of written mail, else its default, else any. */
    private static HubPayloads.MailText text(MailView mail) {
        if (mail.texts().isEmpty()) {
            return null;
        }
        String language = HubLanguage.code();
        HubPayloads.MailText fallback = null;
        for (HubPayloads.MailText text : mail.texts()) {
            if (text.language().equalsIgnoreCase(language)) {
                return text;
            }
            if (text.language().equalsIgnoreCase(mail.defaultLanguage())) {
                fallback = text;
            }
        }
        return fallback != null ? fallback : mail.texts().get(0);
    }

    static Component title(MailView mail) {
        HubPayloads.MailText text = text(mail);
        if (text != null) {
            return formatted(text.title());
        }
        return Component.translatable(mail.titleKey(), mail.args().toArray());
    }

    /** The body: written mail's Markdown, or the translated text. */
    private static List<Component> body(MailView mail) {
        HubPayloads.MailText text = text(mail);
        if (text != null) {
            return List.of(formatted(text.body()));
        }
        return mail.bodyKey().isEmpty() ? List.of()
                : List.of(Component.translatable(mail.bodyKey(), mail.args().toArray()));
    }

    private static Component formatted(String markdown) {
        return MarkdownText.parse(markdown);
    }

    private static Component sender(MailView mail) {
        return mail.senderKey() ? Component.translatable(mail.sender()) : Component.literal(mail.sender());
    }

    private static String date(long epochSeconds) {
        return HubDates.full(epochSeconds * 1000L);
    }

    private static Component rowDate(MailView mail) {
        return Component.literal(date(mail.createdAt()));
    }

    private static boolean expiringSoon(MailView mail) {
        return mail.hasUnclaimed() && mail.expiresAt() - System.currentTimeMillis() / 1000L < 86_400L;
    }

    private static Component expiry(MailView mail) {
        long left = Math.max(0, mail.expiresAt() - System.currentTimeMillis() / 1000L);
        long days = left / 86_400L;
        return days >= 1 ? tr("expires_days", days) : tr("expires_hours", Math.max(1, left / 3_600L));
    }

    private static void send(MailAction.Action action, long mailId) {
        ClientPlayNetworking.send(new MailAction(action, mailId));
    }

    // ---------------------------------------------------------------- list row

    /** One mail in the list: its kind, title, date, and markers; a click opens it. */
    private static final class MailRow extends Widget {
        private static final float HEIGHT = 24;
        private final HubScreen screen;
        private final long id;
        private final int kind;
        private final Component title;
        private final Component date;
        private final boolean unread;
        private final boolean hasItems;
        private final boolean expiring;

        MailRow(HubScreen screen, long id, int kind, Component title, Component date, boolean unread, boolean hasItems,
                boolean expiring, float width) {
            super(width, HEIGHT);
            this.screen = screen;
            this.id = id;
            this.kind = kind;
            this.title = title;
            this.date = date;
            this.unread = unread;
            this.hasItems = hasItems;
            this.expiring = expiring;
            sizing(Sizing.fixed(width), Sizing.fixed(HEIGHT));
            tooltip(title);
        }

        @Override
        protected void rebuild() {
            boolean open = id == selected;
            add(Shapes.rect(0, 0, width, height).fill(open ? EdenParchment.ROW_BG : EdenParchment.ROW_BG_ALT)
                    .stroke(Stroke.of(open ? EdenParchment.ACCENT : hovered ? EdenParchment.HAIRLINE_HI : EdenParchment.HAIRLINE, 1)));
            int color = kind >= 0 && kind < KIND_COLORS.length ? KIND_COLORS[kind] : KIND_COLORS[3];
            add(Shapes.rect(5, (height - 10) / 2, 10, 10).fill(color).stroke(0xC0000000, 1));
            float textX = 20;
            float textWidth = width - textX - 14;
            add(TextNode.of(title).at(textX, 3).maxWidth((int) textWidth).ellipsis(true)
                    .color(unread ? EdenParchment.INK : EdenParchment.INK_MID).shadow(false));
            add(TextNode.of(date).at(textX, 13).maxWidth((int) textWidth).ellipsis(true)
                    .color(expiring ? EdenParchment.DANGER : EdenParchment.INK_DIM).shadow(EdenParchment.TEXT_SHADOW));
            if (unread) {
                add(Shapes.circle(width - 7, 7, 3).fill(HubNotifications.color()).stroke(0xC0000000, 1));
            }
            if (hasItems) {
                add(Shapes.rect(width - 10, height - 10, 6, 6).fill(0xFFE8B84A).stroke(0xC0000000, 1));
            }
        }

        @Override
        public boolean onMouseDown(float x, float y, int button) {
            if (button != 0) {
                return false;
            }
            UiSounds.click();
            selected = id;
            if (unread && !isShop(id)) {
                send(MailAction.Action.OPEN, id);
            }
            screen.onMailData();
            return true;
        }

        @Override
        public Cursor cursor() {
            return Cursor.HAND;
        }
    }
}
