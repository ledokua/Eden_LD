package net.ledok.eden_ld.client.hub;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.ledok.eden_ld.client.EdenParchment;
import net.ledok.eden_ld.hub.HubPayloads;
import net.ledok.eden_ld.hub.HubPayloads.WagerAction;
import net.ledok.eden_ld.hub.HubPayloads.WagerData;
import net.ledok.eden_ld.hub.HubPayloads.WagerListingView;
import net.ledok.eden_ld.hub.HubPayloads.WagerMineView;
import net.ledok.eden_ld.hub.HubPayloads.WagerOfferView;
import net.ledok.eden_ld.hub.HubPayloads.WagerRecordView;
import net.ledok.eden_ld.hub.HubPayloads.WagerStakeView;
import net.ledok.eden_ld.hub.duels.WagerLists;
import net.ledok.vectorlib.client.canvas.ItemStackNode;
import net.ledok.vectorlib.client.canvas.TextNode;
import net.ledok.vectorlib.client.canvas.layout.Align;
import net.ledok.vectorlib.client.canvas.layout.Flex;
import net.ledok.vectorlib.client.canvas.layout.Insets;
import net.ledok.vectorlib.client.canvas.layout.Justify;
import net.ledok.vectorlib.client.canvas.layout.Sizing;
import net.ledok.vectorlib.client.canvas.widget.Button;
import net.ledok.vectorlib.client.canvas.widget.Label;
import net.ledok.vectorlib.client.canvas.widget.Modal;
import net.ledok.vectorlib.client.canvas.widget.ScrollPanel;
import net.ledok.vectorlib.client.canvas.widget.TextArea;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Function;

/**
 * Activities → Duels: wager duels (Duels_LD 1.6.0+), drawn from what the server sends ({@link HubPayloads.WagerData}).
 * Three pages:
 * <ul>
 *   <li><b>Announcements</b> — every open announcement (public ones and personal challenges to the player) as a card:
 *   the author and their wager record, the stake (coins, the first three items with their full tooltips, "+N more"),
 *   the time left (or "Preparing" / "Fight in progress" while the timer is paused), offers, the comment. Sorted newest
 *   first or by coins, filtered to money or items; the player's own announcement is pinned on top with Cancel.</li>
 *   <li><b>My duels</b> — their announcement and the offers on it (Accept / Decline, the loss warning first), their
 *   offers on others' (Withdraw), personal challenges to them (Decline), and what lies in the duel bank.</li>
 *   <li><b>History</b> — finished wager duels with their numbers, both stakes, the result, and Report.</li>
 * </ul>
 * Items never pass through here: Create duel, Terms / Offer a stake and Review open Duels' own screens, and stay
 * disabled ("Coming soon") until the server's Duels_LD can open them. Every action's answer is a toast.
 */
final class WagersTab {

    private static final float LANE = 6;
    private static final float ICON = 16;
    private static final int REPORT_MAX = 300;

    enum Page { ANNOUNCEMENTS, MINE, HISTORY }

    /** Kept while the game runs (like the other tabs' choices). */
    private static Page page = Page.ANNOUNCEMENTS;
    private static WagerLists.Sort sort = WagerLists.Sort.NEWEST;
    private static WagerLists.Filter filter = WagerLists.Filter.ALL;
    private static float width = HubScreen.MIN_WIDTH - 24;
    /** The page's running countdowns, ticked every client tick. */
    private static final List<Countdown> COUNTDOWNS = new ArrayList<>();

    /** A label counting down from {@code sentMillis} (as sent at {@code receivedAt}); {@code text} words the clock. */
    private record Countdown(Label label, long sentMillis, long receivedAt, Function<String, Component> text, String[] shown) {
    }

    private WagersTab() {
    }

    private static Component tr(String key, Object... args) {
        return Component.translatable("gui.eden_ld.hub.wagers." + key, args);
    }

    /** The Duels filter was opened: the server pushes the open page until {@link #left()}. */
    static void entered() {
        send(new WagerAction(WagerAction.Action.VIEW, page.ordinal()));
    }

    static void left() {
        send(new WagerAction(WagerAction.Action.LEAVE, 0));
    }

    private static void send(WagerAction action) {
        if (ClientPlayNetworking.canSend(WagerAction.TYPE)) {
            ClientPlayNetworking.send(action);
        }
    }

    static void build(HubScreen screen, Flex content) {
        width = HubScreen.innerWidth();
        COUNTDOWNS.clear();
        WagerData header = HubState.wagerHeader;

        Flex top = Flex.row().gap(3).alignItems(Align.CENTER);
        top.sizing(Sizing.fill(), Sizing.content());
        var pages = EdenParchment.tabs(List.of(Page.values()), candidate -> tr("page." + candidate.name().toLowerCase(Locale.ROOT)),
                page, candidate -> {
                    page = candidate;
                    send(new WagerAction(WagerAction.Action.VIEW, candidate.ordinal()));
                    screen.onWagerData(false);
                }, 40, "wagers/page");
        pages.id(HubSpotlights.WAGERS_PAGES);
        top.item(pages);
        top.spacer();
        Button create = openButton(tr("create"), header != null && header.can(WagerData.CREATE), null,
                () -> send(new WagerAction(WagerAction.Action.OPEN_CREATE, 0)));
        create.id(HubSpotlights.WAGERS_CREATE);
        top.item(create);
        content.item(top);

        switch (page) {
            case ANNOUNCEMENTS -> announcements(screen, content, header);
            case MINE -> mine(screen, content, header);
            case HISTORY -> history(screen, content, header);
        }
        tick();
    }

    // ------------------------------------------------------------------ Announcements

    private static void announcements(HubScreen screen, Flex content, @Nullable WagerData header) {
        List<WagerListingView> all = HubState.wagerListings;
        if (all == null || header == null) {
            content.item(loading());
            return;
        }
        Flex controls = Flex.row().gap(3).alignItems(Align.CENTER);
        controls.sizing(Sizing.fill(), Sizing.content());
        controls.item(EdenParchment.text(tr("sort"), EdenParchment.INK_DIM));
        var sorts = EdenParchment.tabs(List.of(WagerLists.Sort.values()),
                candidate -> tr("sort." + candidate.name().toLowerCase(Locale.ROOT)), sort, candidate -> {
                    sort = candidate;
                    screen.onWagerData(false);
                }, 30, "wagers/sort").height(12);
        // Items can't be weighed: "by coins" says so.
        sorts.tabTooltip(WagerLists.Sort.COINS, tr("sort.coins.hint"));
        controls.item(sorts);
        controls.item(EdenParchment.text(tr("show"), EdenParchment.INK_DIM));
        controls.item(EdenParchment.tabs(List.of(WagerLists.Filter.values()),
                candidate -> tr("filter." + candidate.name().toLowerCase(Locale.ROOT)), filter, candidate -> {
                    filter = candidate;
                    screen.onWagerData(false);
                }, 30, "wagers/filter").height(12));
        content.item(controls);

        float inner = width - LANE;
        Flex list = Flex.column().gap(3);
        list.item(fairness(inner));
        WagerListingView own = WagerLists.pinned(all);
        if (own != null) {
            list.item(listingCard(screen, own, header, HubState.wagerListingsAt, inner));
        }
        List<WagerListingView> shown = WagerLists.arrange(all, sort, filter);
        if (shown.isEmpty()) {
            // Others hidden by the filter, else none at all (only the viewer's own, pinned above, or nothing).
            String empty = all.size() > (own == null ? 0 : 1) ? "none_filtered" : own != null ? "none_others" : "none";
            list.item(EdenParchment.text(tr(empty), EdenParchment.INK_DIM, (int) inner));
        }
        for (WagerListingView listing : shown) {
            list.item(listingCard(screen, listing, header, HubState.wagerListingsAt, inner));
        }
        content.item(scroll("announcements", list));
    }

    /**
     * An announcement: "#1042 Author · 3 W · 1 L · 0 D · Challenge to you … 24:13 left", then the stake, the offers
     * and the button (Terms for others', Cancel and Offers for the player's own), then the comment.
     */
    private static Flex listingCard(HubScreen screen, WagerListingView listing, WagerData header, long receivedAt, float w) {
        float inner = w - 12;
        Flex card = Flex.column().gap(2).padding(Insets.of(4, 6, 4, 6));
        card.sizing(Sizing.fixed(w), Sizing.content());
        card.background(EdenParchment.framed(listing.mine() ? EdenParchment.ROW_BG_ALT : EdenParchment.ROW_BG,
                listing.mine() ? EdenParchment.ACCENT : EdenParchment.HAIRLINE));

        Flex head = Flex.row().gap(5).alignItems(Align.CENTER);
        head.sizing(Sizing.fixed(inner), Sizing.content());
        head.item(EdenParchment.text(tr("number", listing.number()), EdenParchment.INK_DIM));
        head.item(EdenParchment.text(Component.literal(listing.authorName()), listing.mine() ? EdenParchment.ACCENT : EdenParchment.INK));
        head.item(EdenParchment.text(tr("stats", listing.wins(), listing.losses(), listing.draws()), EdenParchment.INK_DIM));
        Component personal = personal(listing);
        if (personal != null) {
            head.item(EdenParchment.text(personal, EdenParchment.ACCENT));
        }
        head.spacer();
        head.item(timeLabel(listing, receivedAt));
        card.item(head);

        Flex body = Flex.row().gap(5).alignItems(Align.CENTER);
        body.sizing(Sizing.fixed(inner), Sizing.content());
        stake(body, listing.stake());
        body.spacer();
        body.item(EdenParchment.text(tr("offers", listing.offers()), listing.offers() > 0 ? EdenParchment.INK_MID : EdenParchment.INK_DIM));
        if (listing.mine()) {
            if (page == Page.ANNOUNCEMENTS) {
                body.item(EdenParchment.fitButton(tr("see_offers"), 40, 14, () -> {
                    page = Page.MINE;
                    send(new WagerAction(WagerAction.Action.VIEW, Page.MINE.ordinal()));
                    screen.onWagerData(false);
                }));
            }
            Button cancel = EdenParchment.fitButton(tr("cancel_listing"), 40, 14, () -> confirmCancel(screen, listing));
            cancel.enabled(cancellable(listing.state()));
            body.item(cancel);
        } else {
            body.item(termsButton(listing, header, tr("terms")));
        }
        card.item(body);

        if (!listing.comment().isBlank()) {
            Label comment = Label.of(inner, tr("comment", listing.comment()));
            comment.color(EdenParchment.INK_MID);
            comment.tooltipWhenCut(true);
            card.item(comment);
        }
        return card;
    }

    /** "Challenge to you" / "Challenge to Steve" on a personal challenge, else null. */
    @Nullable
    private static Component personal(WagerListingView listing) {
        if (listing.target() == null) {
            return null;
        }
        return listing.target().equals(self()) ? tr("personal_you") : tr("personal_to", listing.targetName());
    }

    /** The author may cancel until the fight starts (Duels refuses with its reason when it can't). */
    private static boolean cancellable(int state) {
        return state == WagerListingView.ACTIVE || state == WagerListingView.PREPARING;
    }

    /** Terms / Offer a stake: Duels' offer screen, once the server's Duels_LD can open it from the hub. */
    private static Button termsButton(WagerListingView listing, WagerData header, Component label) {
        boolean open = listing.state() == WagerListingView.ACTIVE;
        return openButton(label, header.can(WagerData.TERMS) && open, header.can(WagerData.TERMS) && !open ? tr("closed") : null,
                () -> send(new WagerAction(WagerAction.Action.OPEN_TERMS, listing.number())));
    }

    /**
     * A button that opens one of Duels' screens: disabled with "Coming soon" while the server's Duels_LD can't, or
     * with {@code why} when it can but not now. Duels' screen then replaces the hub.
     */
    private static Button openButton(Component label, boolean enabled, @Nullable Component why, Runnable click) {
        Button button = EdenParchment.fitButton(label, 40, 14, click);
        button.enabled(enabled);
        if (!enabled) {
            button.tooltip(why != null ? why : tr("coming_soon"));
        }
        return button;
    }

    /** "24:13 left" (counting down), or the paused state with what's left in the tooltip. */
    private static Label timeLabel(WagerListingView listing, long receivedAt) {
        Label label = Label.of(timeWidth(), Component.empty());
        label.align(TextNode.Align.RIGHT);
        if (listing.state() == WagerListingView.ACTIVE && !listing.paused()) {
            label.color(EdenParchment.INK_MID);
            countdown(label, listing.remainingMillis(), receivedAt, time -> tr("time_left", time));
            return label;
        }
        label.text(listingState(listing.state()));
        label.color(listing.state() == WagerListingView.FIGHTING ? EdenParchment.DANGER : EdenParchment.WARN);
        if (listing.paused()) {
            label.pickable(true);
            label.tooltip(tr("paused", WagerLists.clock(listing.remainingMillis())));
        }
        return label;
    }

    private static float timeWidth() {
        return fit(50, tr("time_left", "29:59"), tr("state.preparing"), tr("state.fighting"));
    }

    private static Component listingState(int state) {
        return switch (state) {
            case WagerListingView.ACTIVE -> tr("state.active");
            case WagerListingView.PREPARING -> tr("state.preparing");
            case WagerListingView.FIGHTING -> tr("state.fighting");
            case WagerListingView.FINISHED -> tr("state.finished");
            case WagerListingView.CANCELLED -> tr("state.cancelled");
            case WagerListingView.EXPIRED -> tr("state.expired");
            default -> tr("state.unknown");
        };
    }

    private static void confirmCancel(HubScreen screen, WagerListingView listing) {
        confirm(screen, List.of(text(tr("cancel_question", listing.number()), EdenParchment.INK),
                        text(tr("cancel_hint"), EdenParchment.INK_DIM)), tr("cancel_listing"),
                () -> send(new WagerAction(WagerAction.Action.CANCEL_LISTING, listing.number())));
    }

    // ------------------------------------------------------------------ My duels

    private static void mine(HubScreen screen, Flex content, @Nullable WagerData header) {
        WagerMineView mine = HubState.wagerMine;
        if (mine == null || header == null) {
            content.item(loading());
            return;
        }
        Flex bar = Flex.row().gap(5).alignItems(Align.CENTER);
        bar.sizing(Sizing.fill(), Sizing.content());
        bar.item(bank(mine.inBank()));
        bar.spacer();
        bar.item(record(header));
        content.item(bar);

        float inner = width - LANE;
        long at = HubState.wagerMineAt;
        Flex list = Flex.column().gap(3);
        list.item(fairness(inner));

        list.item(section(tr("section.listing")));
        if (mine.listing() == null) {
            list.item(EdenParchment.text(tr("no_listing"), EdenParchment.INK_DIM, (int) inner));
        } else {
            list.item(listingCard(screen, mine.listing(), header, at, inner));
            if (mine.offersOnMine().isEmpty()) {
                list.item(EdenParchment.text(tr("no_offers"), EdenParchment.INK_DIM, (int) inner));
            }
            boolean alt = false;
            for (WagerOfferView offer : mine.offersOnMine()) {
                list.item(offerOnMine(screen, offer, header, inner, alt));
                alt = !alt;
            }
        }

        list.item(section(tr("section.my_offers")));
        if (mine.myOffers().isEmpty()) {
            list.item(EdenParchment.text(tr("no_my_offers"), EdenParchment.INK_DIM, (int) inner));
        }
        boolean alt = false;
        for (WagerOfferView offer : mine.myOffers()) {
            list.item(myOffer(offer, inner, alt));
            alt = !alt;
        }

        list.item(section(tr("section.challenges")));
        if (mine.challengesToMe().isEmpty()) {
            list.item(EdenParchment.text(tr("no_challenges"), EdenParchment.INK_DIM, (int) inner));
        }
        for (WagerListingView challenge : mine.challengesToMe()) {
            list.item(challengeCard(challenge, header, at, inner));
        }
        content.item(scroll("mine", list));
    }

    /** "In the duel bank: 500 coins, 3 items" — the items one by one in the tooltip. */
    private static TextNode bank(WagerStakeView bank) {
        Component amount = bank.isEmpty() ? tr("bank_empty") : tr("bank", compact(bank));
        TextNode text = EdenParchment.text(amount, EdenParchment.INK_MID);
        List<Component> lines = new ArrayList<>();
        lines.add(tr("bank_hint"));
        lines.addAll(itemLines(bank.items()));
        text.tooltip(lines.toArray(Component[]::new));
        return text;
    }

    /** An offer on the player's announcement: "● Challenger · state … Review · Accept · Decline", then their stake. */
    private static Flex offerOnMine(HubScreen screen, WagerOfferView offer, WagerData header, float w, boolean alt) {
        Flex card = entry(w, alt);
        float inner = w - 12;
        Flex top = line(inner);
        TextNode dot = EdenParchment.text(Component.literal("●"), offer.online() ? EdenParchment.GOOD : EdenParchment.INK_DIM);
        dot.tooltip(tr(offer.online() ? "online" : "offline"));
        top.item(dot);
        top.item(EdenParchment.text(Component.literal(offer.challengerName()), EdenParchment.INK));
        boolean pending = offer.state() == WagerOfferView.PENDING;
        // A pending offer's buttons say what it waits for; any other state is worth a word.
        if (!pending) {
            top.item(EdenParchment.text(offerState(offer.state()), EdenParchment.WARN));
        }
        top.spacer();
        top.item(openButton(tr("review"), header.can(WagerData.REVIEW) && pending, null,
                () -> send(new WagerAction(WagerAction.Action.OPEN_REVIEW, offer.id()))));
        Button accept = EdenParchment.fitButton(tr("accept"), 40, 14, () -> accept(screen, offer, header));
        accept.enabled(pending && offer.online());
        if (pending && !offer.online()) {
            accept.tooltip(tr("accept_offline"));
        }
        top.item(accept);
        Button decline = EdenParchment.fitButton(tr("decline"), 40, 14,
                () -> send(new WagerAction(WagerAction.Action.DECLINE_OFFER, offer.id())));
        decline.enabled(pending);
        top.item(decline);
        card.item(top);
        Flex bottom = line(inner);
        bottom.item(EdenParchment.text(tr("their_stake"), EdenParchment.INK_DIM));
        stake(bottom, offer.stake());
        card.item(bottom);
        return card;
    }

    /** Accepting starts the ready check: Eden's loss warning first, unless the player turned it off in Settings. */
    private static void accept(HubScreen screen, WagerOfferView offer, WagerData header) {
        Runnable go = () -> send(new WagerAction(WagerAction.Action.ACCEPT_OFFER, offer.id()));
        if (header.skipLossWarning()) {
            go.run();
            return;
        }
        confirm(screen, List.of(text(tr("accept_question", offer.challengerName()), EdenParchment.INK),
                        text(tr("loss_warning"), EdenParchment.DANGER),
                        text(tr("loss_warning_setting"), EdenParchment.INK_DIM)),
                tr("accept"), go);
    }

    /** The player's offer on someone's announcement: "#1042 Author · state … Withdraw", then their stake. */
    private static Flex myOffer(WagerOfferView offer, float w, boolean alt) {
        Flex card = entry(w, alt);
        float inner = w - 12;
        Flex top = line(inner);
        top.item(EdenParchment.text(tr("number", offer.number()), EdenParchment.INK_DIM));
        if (!offer.authorName().isEmpty()) {
            top.item(EdenParchment.text(Component.literal(offer.authorName()), EdenParchment.INK));
        }
        top.item(EdenParchment.text(offerState(offer.state()), offer.state() == WagerOfferView.READY_CHECK
                || offer.state() == WagerOfferView.FIGHTING ? EdenParchment.WARN : EdenParchment.INK_DIM));
        top.spacer();
        Button withdraw = EdenParchment.fitButton(tr("withdraw"), 40, 14,
                () -> send(new WagerAction(WagerAction.Action.WITHDRAW_OFFER, offer.id())));
        withdraw.enabled(offer.state() == WagerOfferView.PENDING);
        top.item(withdraw);
        card.item(top);
        Flex bottom = line(inner);
        bottom.item(EdenParchment.text(tr("your_stake"), EdenParchment.INK_DIM));
        stake(bottom, offer.stake());
        card.item(bottom);
        return card;
    }

    /** A personal challenge to the player: who, their stake, the time left, the comment … Offer a stake · Decline. */
    private static Flex challengeCard(WagerListingView challenge, WagerData header, long receivedAt, float w) {
        float inner = w - 12;
        Flex card = Flex.column().gap(2).padding(Insets.of(4, 6, 4, 6));
        card.sizing(Sizing.fixed(w), Sizing.content());
        card.background(EdenParchment.framed(EdenParchment.ROW_BG, EdenParchment.ACCENT));
        Flex head = Flex.row().gap(5).alignItems(Align.CENTER);
        head.sizing(Sizing.fixed(inner), Sizing.content());
        head.item(EdenParchment.text(tr("number", challenge.number()), EdenParchment.INK_DIM));
        head.item(EdenParchment.text(tr("challenge_from", challenge.authorName()), EdenParchment.INK));
        head.item(EdenParchment.text(tr("stats", challenge.wins(), challenge.losses(), challenge.draws()), EdenParchment.INK_DIM));
        head.spacer();
        head.item(timeLabel(challenge, receivedAt));
        card.item(head);

        Flex body = Flex.row().gap(5).alignItems(Align.CENTER);
        body.sizing(Sizing.fixed(inner), Sizing.content());
        stake(body, challenge.stake());
        body.spacer();
        body.item(termsButton(challenge, header, tr("offer_stake")));
        Button decline = EdenParchment.fitButton(tr("decline"), 40, 14,
                () -> send(new WagerAction(WagerAction.Action.DECLINE_CHALLENGE, challenge.number())));
        decline.enabled(challenge.state() == WagerListingView.ACTIVE);
        body.item(decline);
        card.item(body);
        if (!challenge.comment().isBlank()) {
            Label comment = Label.of(inner, tr("comment", challenge.comment()));
            comment.color(EdenParchment.INK_MID);
            comment.tooltipWhenCut(true);
            card.item(comment);
        }
        return card;
    }

    private static Component offerState(int state) {
        return switch (state) {
            case WagerOfferView.PENDING -> tr("offer.pending");
            case WagerOfferView.READY_CHECK -> tr("offer.ready_check");
            case WagerOfferView.FIGHTING -> tr("offer.fighting");
            case WagerOfferView.WON -> tr("offer.won");
            case WagerOfferView.LOST -> tr("offer.lost");
            case WagerOfferView.DRAW -> tr("offer.draw");
            case WagerOfferView.DECLINED -> tr("offer.declined");
            case WagerOfferView.WITHDRAWN -> tr("offer.withdrawn");
            case WagerOfferView.CLOSED -> tr("offer.closed");
            default -> tr("state.unknown");
        };
    }

    // ------------------------------------------------------------------ History

    private static void history(HubScreen screen, Flex content, @Nullable WagerData header) {
        List<WagerRecordView> records = HubState.wagerHistory;
        if (records == null || header == null) {
            content.item(loading());
            return;
        }
        Flex bar = Flex.row().gap(5).alignItems(Align.CENTER);
        bar.sizing(Sizing.fill(), Sizing.content());
        bar.item(EdenParchment.text(tr("history_hint"), EdenParchment.INK_DIM));
        bar.spacer();
        bar.item(record(header));
        content.item(bar);

        float inner = width - LANE;
        Flex list = Flex.column().gap(1);
        if (records.isEmpty()) {
            list.item(EdenParchment.text(tr("no_history"), EdenParchment.INK_DIM, (int) inner));
        }
        boolean alt = false;
        for (WagerRecordView record : records) {
            list.item(historyRow(screen, record, inner, alt));
            alt = !alt;
        }
        content.item(scroll("history", list));
    }

    /**
     * Two lines and Report: "#1042 vs Steve · Won · 3:12 · Oct 4", then "You: 500 coins + 3 items · Them: 2 items"
     * (each stake's items in its tooltip).
     */
    private static Flex historyRow(HubScreen screen, WagerRecordView record, float w, boolean alt) {
        Flex row = Flex.row().gap(6).padding(Insets.of(2, 6, 2, 6)).alignItems(Align.CENTER);
        row.sizing(Sizing.fixed(w), Sizing.content());
        row.background(EdenParchment.flat(alt ? EdenParchment.ROW_BG_ALT : EdenParchment.ROW_BG));

        Button report = EdenParchment.fitButton(record.reported() ? tr("reported") : tr("report"), 50, 14,
                () -> openReport(screen, record));
        report.enabled(!record.reported());
        if (record.reported()) {
            report.tooltip(tr("reported_hint"));
        }
        float textWidth = w - 12 - report.width() - 6;

        Flex lines = Flex.column().gap(1);
        lines.sizing(Sizing.fixed(textWidth), Sizing.content());
        Flex first = Flex.row().gap(5).alignItems(Align.CENTER);
        first.sizing(Sizing.fixed(textWidth), Sizing.content());
        first.item(EdenParchment.text(tr("number", record.number()), EdenParchment.INK_DIM));
        first.item(EdenParchment.text(tr("versus", record.opponentName()), EdenParchment.INK));
        int color = switch (record.result()) {
            case WagerRecordView.WON -> EdenParchment.GOOD;
            case WagerRecordView.LOST -> EdenParchment.DANGER;
            default -> EdenParchment.INK_MID;
        };
        first.item(EdenParchment.text(tr("result." + switch (record.result()) {
            case WagerRecordView.WON -> "won";
            case WagerRecordView.LOST -> "lost";
            default -> "draw";
        }), color));
        first.spacer();
        TextNode duration = EdenParchment.text(Component.literal(WagerLists.clock(record.durationMillis())), EdenParchment.INK_DIM);
        duration.tooltip(tr("duration"));
        first.item(duration);
        first.item(EdenParchment.text(Component.literal(HubDates.full(record.endedAtMillis())), EdenParchment.INK_DIM));
        lines.item(first);

        Flex second = Flex.row().gap(5).alignItems(Align.CENTER);
        second.sizing(Sizing.fixed(textWidth), Sizing.content());
        second.item(stakeText(tr("you_staked", compact(record.myStake())), record.myStake()));
        second.item(EdenParchment.text(Component.literal("·"), EdenParchment.INK_DIM));
        second.item(stakeText(tr("they_staked", compact(record.theirStake())), record.theirStake()));
        lines.item(second);

        row.item(lines);
        row.item(report);
        return row;
    }

    /** A stake as one line, its items one by one in the tooltip. */
    private static TextNode stakeText(Component text, WagerStakeView stake) {
        TextNode node = EdenParchment.text(text, EdenParchment.INK_MID);
        List<Component> lines = itemLines(stake.items());
        if (!lines.isEmpty()) {
            node.tooltip(lines.toArray(Component[]::new));
        }
        return node;
    }

    /** Report: the complaint (up to 300 characters) goes to the moderators with the duel's number and full log. */
    private static void openReport(HubScreen screen, WagerRecordView record) {
        float inner = 220;
        Flex dialog = Flex.column().gap(6).padding(Insets.of(8, 10, EdenParchment.tileEdges() ? 12 : 8, 10));
        dialog.sizing(Sizing.fixed(inner + 20), Sizing.content());
        dialog.background(EdenParchment.panel());
        dialog.item(EdenParchment.text(tr("report_title", record.number(), record.opponentName()), EdenParchment.INK));
        dialog.item(EdenParchment.text(tr("report_hint"), EdenParchment.INK_DIM, (int) inner));

        Label counter = Label.of(inner, tr("report_count", 0, REPORT_MAX));
        counter.color(EdenParchment.INK_DIM);
        counter.align(TextNode.Align.RIGHT);
        Modal[] modal = new Modal[1];
        TextArea[] area = new TextArea[1];
        Button sendButton = EdenParchment.fitButton(tr("report_send"), 56, 14, () -> {
            String text = area[0].text().strip();
            if (!text.isEmpty()) {
                send(new WagerAction(WagerAction.Action.REPORT, record.number(), text));
                modal[0].close();
            }
        });
        sendButton.enabled(false);
        area[0] = new TextArea(inner, 60, "", text -> {
            counter.text(tr("report_count", text.length(), REPORT_MAX));
            sendButton.enabled(!text.isBlank());
        });
        area[0].maxLength(REPORT_MAX);
        area[0].hint(tr("report_placeholder").getString());
        dialog.item(area[0]);
        dialog.item(counter);

        Flex buttons = Flex.row().gap(6).justify(Justify.END);
        buttons.sizing(Sizing.fixed(inner), Sizing.content());
        buttons.item(sendButton);
        buttons.item(EdenParchment.fitButton(Component.translatable("gui.eden_ld.hub.friends.cancel"), 56, 14,
                () -> modal[0].close()));
        dialog.item(buttons);
        modal[0] = Modal.open(screen.canvas(), dialog);
        modal[0].closeOnClickOutside(true);
        screen.canvas().focus(area[0]);
    }

    // ------------------------------------------------------------------ shared pieces

    /** Coins, then the first items as icons (their full tooltips), then "+N more" with the rest in its tooltip. */
    private static void stake(Flex row, WagerStakeView stake) {
        if (stake.isEmpty()) {
            row.item(EdenParchment.text(tr("stake_none"), EdenParchment.INK_DIM));
            return;
        }
        if (stake.coins() > 0) {
            row.item(EdenParchment.text(tr("coins", WagerLists.coins(stake.coins())), EdenParchment.GOOD));
        }
        int shown = WagerLists.shownItems(stake.items().size());
        for (int i = 0; i < shown; i++) {
            row.item(ItemStackNode.of(stake.items().get(i), 0, 0, ICON));
        }
        int more = WagerLists.moreItems(stake.items().size());
        if (more > 0) {
            TextNode rest = EdenParchment.text(tr("more", more), EdenParchment.INK_MID);
            rest.tooltip(itemLines(stake.items().subList(shown, stake.items().size())).toArray(Component[]::new));
            row.item(rest);
        }
    }

    /** "500 coins + 3 items", "2 items", "500 coins" or "nothing". */
    private static Component compact(WagerStakeView stake) {
        int items = stake.items().size();
        Component itemText = items == 1 ? tr("items_one") : tr("items", items);
        if (stake.coins() > 0 && items > 0) {
            return tr("coins_and", tr("coins", WagerLists.coins(stake.coins())), itemText);
        }
        if (stake.coins() > 0) {
            return tr("coins", WagerLists.coins(stake.coins()));
        }
        return items > 0 ? itemText : tr("stake_none");
    }

    /** "3× Diamond Sword" per stack (tooltips can't hold item icons). */
    private static List<Component> itemLines(List<ItemStack> items) {
        List<Component> lines = new ArrayList<>();
        for (ItemStack stack : items) {
            lines.add(Component.literal(stack.getCount() + "× ").withStyle(ChatFormatting.GRAY).append(stack.getHoverName()));
        }
        return lines;
    }

    /** "Your record: 3 W · 1 L · 0 D". */
    private static TextNode record(WagerData header) {
        return EdenParchment.text(tr("your_record", tr("stats", header.wins(), header.losses(), header.draws())),
                EdenParchment.INK_DIM);
    }

    /** The moderator's warning: nobody has to play fair; a cheated player reports with the duel's number. */
    private static Flex fairness(float w) {
        Flex box = Flex.column().padding(Insets.of(3, 6, 3, 6));
        box.sizing(Sizing.fixed(w), Sizing.content());
        box.background(EdenParchment.framed(EdenParchment.PANEL_2, EdenParchment.HAIRLINE));
        box.item(EdenParchment.text(tr("fairness"), EdenParchment.INK_DIM, (int) w - 12));
        return box;
    }

    private static TextNode section(Component title) {
        return EdenParchment.text(title, EdenParchment.INK_MID);
    }

    /** A two-line list entry (an offer): rows go in with {@link #line}. */
    private static Flex entry(float w, boolean alt) {
        Flex entry = Flex.column().gap(1).padding(Insets.of(2, 6, 2, 6));
        entry.sizing(Sizing.fixed(w), Sizing.content());
        entry.background(EdenParchment.flat(alt ? EdenParchment.ROW_BG_ALT : EdenParchment.ROW_BG));
        return entry;
    }

    private static Flex line(float w) {
        Flex line = Flex.row().gap(5).alignItems(Align.CENTER);
        line.sizing(Sizing.fixed(w), Sizing.content());
        return line;
    }

    private static ScrollPanel scroll(String key, Flex list) {
        ScrollPanel panel = HubScroll.panel("wagers/" + key, width, 60, list);
        panel.id(HubSpotlights.WAGERS_LIST);
        panel.sizing(Sizing.fill(), Sizing.expand());
        return panel;
    }

    private static Flex loading() {
        Flex center = Flex.column().justify(Justify.CENTER).alignItems(Align.CENTER);
        center.sizing(Sizing.fill(), Sizing.expand());
        center.item(EdenParchment.text(Component.translatable("gui.eden_ld.hub.home.loading"), EdenParchment.INK_DIM));
        return center;
    }

    private static TextNode text(Component text, int color) {
        return EdenParchment.text(text, color, 200);
    }

    /** Yes / Cancel over the hub, for what can't be undone. */
    private static void confirm(HubScreen screen, List<TextNode> lines, Component yes, Runnable action) {
        Flex dialog = Flex.column().gap(6).padding(Insets.of(8, 10, EdenParchment.tileEdges() ? 12 : 8, 10));
        dialog.sizing(Sizing.fixed(220), Sizing.content());
        dialog.background(EdenParchment.panel());
        lines.forEach(dialog::item);
        Modal[] modal = new Modal[1];
        Flex buttons = Flex.row().gap(6).justify(Justify.END);
        buttons.sizing(Sizing.fixed(200), Sizing.content());
        buttons.item(EdenParchment.fitButton(yes, 56, 14, () -> {
            action.run();
            modal[0].close();
        }));
        buttons.item(EdenParchment.fitButton(Component.translatable("gui.eden_ld.hub.friends.cancel"), 56, 14,
                () -> modal[0].close()));
        dialog.item(buttons);
        modal[0] = Modal.open(screen.canvas(), dialog);
        modal[0].closeOnClickOutside(true);
    }

    @Nullable
    private static UUID self() {
        return Minecraft.getInstance().player == null ? null : Minecraft.getInstance().player.getUUID();
    }

    /** The widest of the samples (plus a little), never below {@code min}. */
    private static float fit(float min, Component... samples) {
        var font = Minecraft.getInstance().font;
        float out = min;
        for (Component sample : samples) {
            out = Math.max(out, font.width(sample) + 2);
        }
        return out;
    }

    // ------------------------------------------------------------------ countdowns

    private static void countdown(Label label, long sentMillis, long receivedAt, Function<String, Component> text) {
        Countdown countdown = new Countdown(label, sentMillis, receivedAt, text, new String[1]);
        COUNTDOWNS.add(countdown);
        update(countdown, System.currentTimeMillis());
    }

    /** Every client tick on the page: the countdowns (a label changes only when its second does). */
    static void tick() {
        long now = System.currentTimeMillis();
        for (Countdown countdown : COUNTDOWNS) {
            update(countdown, now);
        }
    }

    private static void update(Countdown countdown, long now) {
        String clock = WagerLists.clock(WagerLists.remaining(countdown.sentMillis(), countdown.receivedAt(), now));
        if (!clock.equals(countdown.shown()[0])) {
            countdown.shown()[0] = clock;
            countdown.label().text(countdown.text().apply(clock));
        }
    }
}
