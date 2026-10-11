package net.ledok.eden_ld.client.hub;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.ledok.eden_ld.client.EdenParchment;
import net.ledok.eden_ld.hub.HubPayloads;
import net.ledok.eden_ld.hub.HubPayloads.DuelsData;
import net.ledok.eden_ld.hub.HubPayloads.DuelsLeader;
import net.ledok.eden_ld.hub.HubPayloads.DuelsMatch;
import net.ledok.eden_ld.hub.HubPayloads.DuelsMode;
import net.ledok.vectorlib.client.canvas.TextNode;
import net.ledok.vectorlib.client.canvas.layout.Align;
import net.ledok.vectorlib.client.canvas.layout.Flex;
import net.ledok.vectorlib.client.canvas.layout.Insets;
import net.ledok.vectorlib.client.canvas.layout.Justify;
import net.ledok.vectorlib.client.canvas.layout.Sizing;
import net.ledok.vectorlib.client.canvas.widget.Button;
import net.ledok.vectorlib.client.canvas.widget.Label;
import net.ledok.vectorlib.client.canvas.widget.ScrollPanel;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * The Duels tab (Duels_LD): a card per mode (rating, leaderboard place, wins/losses/draws, the
 * queue button and its live state), today's battle rewards, then recent matches or a mode's
 * leaderboard. While the tab is open the server pushes every change; Duels' own screen (B) stays.
 */
final class DuelsTab {

    private static final float LANE = 6;
    private static final float GAP = 4;
    /** The tab's width and a mode card's (half of it), set as the view builds. */
    private static float width = HubScreen.MIN_WIDTH - 24;
    private static float cardWidth = (width - GAP) / 2;
    private static final float CARD_H = 62;
    private static final float ROW_H = 18;
    private static final float BUTTON_W = 70;
    private static final String[] MODES = {"1v1", "2v2"};

    enum View { MATCHES, TOP_1V1, TOP_2V2 }

    /** Kept while the game runs (like the other tabs' choices). */
    private static View view = View.MATCHES;
    /** The searching card's status line, ticked every client tick ("Searching 0:42 · ±150"). */
    private static Label searchLabel;

    private DuelsTab() {
    }

    private static Component tr(String key, Object... args) {
        return Component.translatable("gui.eden_ld.hub.duels." + key, args);
    }

    /** The tab was opened: the server pushes changes until {@link #left()}. */
    static void entered() {
        ClientPlayNetworking.send(new HubPayloads.DuelsView(true));
    }

    static void left() {
        ClientPlayNetworking.send(new HubPayloads.DuelsView(false));
    }

    static void build(HubScreen screen, Flex content) {
        DuelsData data = HubState.duelsData;
        width = HubScreen.innerWidth();
        cardWidth = (width - GAP) / 2;
        searchLabel = null;
        if (data == null) {
            Flex center = Flex.column().justify(Justify.CENTER).alignItems(Align.CENTER);
            center.sizing(Sizing.fill(), Sizing.expand());
            center.item(EdenParchment.text(Component.translatable("gui.eden_ld.hub.home.loading"), EdenParchment.INK_DIM));
            content.item(center);
            return;
        }
        Flex cards = Flex.row().gap(GAP);
        cards.sizing(Sizing.fill(), Sizing.content());
        cards.item(modeCard(data, 0));
        cards.item(modeCard(data, 1));
        content.item(cards);

        if (data.rewards()) {
            long cap = Math.max(1, data.dailyCap());
            var rewards = EdenParchment.progressBar(width, 12, (float) data.earnedToday() / cap,
                    tr("rewards", data.earnedToday(), data.dailyCap()), data.earnedToday() >= data.dailyCap())
                    .remember("eden_ld/duels/rewards");
            rewards.id(HubSpotlights.DUELS_REWARDS);
            content.item(rewards);
        }

        Flex views = Flex.row().gap(3).alignItems(Align.CENTER);
        views.sizing(Sizing.fill(), Sizing.content());
        var viewTabs = EdenParchment.tabs(List.of(View.values()),
                candidate -> tr("view." + candidate.name().toLowerCase(Locale.ROOT)), view, candidate -> {
                    view = candidate;
                    screen.onDuelsData();
                }, 40, "duels/view");
        viewTabs.id(HubSpotlights.DUELS_VIEWS);
        views.item(viewTabs);
        content.item(views);

        Flex list = Flex.column().gap(1);
        switch (view) {
            case MATCHES -> matches(list, data.history());
            case TOP_1V1 -> leaderboard(list, data.top1v1(), data.oneVsOne());
            case TOP_2V2 -> leaderboard(list, data.top2v2(), data.twoVsTwo());
        }
        ScrollPanel panel = HubScroll.panel("duels/" + view.name().toLowerCase(Locale.ROOT), width, 60, list);
        panel.id(HubSpotlights.DUELS_LIST);
        panel.sizing(Sizing.fill(), Sizing.expand());
        content.item(panel);
        tick();
    }

    // ------------------------------------------------------------------ mode cards

    private static Flex modeCard(DuelsData data, int mode) {
        DuelsMode stats = data.of(mode);
        boolean searching = stats.searching();
        float inner = cardWidth - 12;
        Flex card = Flex.column().gap(3).padding(Insets.of(4, 6, 4, 6));
        card.id(mode == 0 ? HubSpotlights.DUELS_CARD_1V1 : HubSpotlights.DUELS_CARD_2V2);
        card.sizing(Sizing.fixed(cardWidth), Sizing.fixed(CARD_H));
        card.background(EdenParchment.framed(EdenParchment.ROW_BG, searching ? EdenParchment.ACCENT : EdenParchment.HAIRLINE));

        // "1v1" … "#3 on the leaderboard"
        Flex top = Flex.row().alignItems(Align.CENTER);
        top.sizing(Sizing.fixed(inner), Sizing.content());
        top.item(EdenParchment.text(tr("mode." + MODES[mode]), EdenParchment.INK));
        int played = stats.played();
        if (played > 0) {
            top.spacer();
            top.item(EdenParchment.text(stats.rank() > 0 ? tr("rank", stats.rank()) : tr("unranked"), EdenParchment.INK_DIM));
        }
        card.item(top);

        if (played == 0) {
            // A new player: one line saying what to do, not three lines of "nothing yet".
            card.item(EdenParchment.text(tr("first_match"), EdenParchment.INK_MID, (int) inner));
        } else {
            // "Rating 1034" … "12 W · 5 L · 1 D (67%)"
            Flex record = Flex.row().alignItems(Align.CENTER);
            record.sizing(Sizing.fixed(inner), Sizing.content());
            boolean rated = stats.rating() > 0;
            record.item(EdenParchment.text(rated ? tr("rating", stats.rating()) : tr("rating_none"), EdenParchment.INK_MID));
            record.spacer();
            record.item(EdenParchment.text(tr("record", stats.wins(), stats.losses(), stats.draws(),
                    stats.wins() * 100 / played), EdenParchment.INK_DIM));
            card.item(record);
        }

        // The party line (2v2) or how matching works (1v1).
        Label hint = Label.of(inner, mode == 1 ? partyHint(data) : tr("hint_1v1"));
        hint.color(EdenParchment.INK_DIM);
        hint.tooltipWhenCut(true);
        card.item(hint);

        Flex actions = Flex.row().gap(5).alignItems(Align.CENTER);
        actions.sizing(Sizing.fixed(inner), Sizing.content());
        Button button = queueButton(data, mode);
        button.id(mode == 0 ? HubSpotlights.DUELS_QUEUE_1V1 : HubSpotlights.DUELS_QUEUE_2V2);
        actions.item(button);
        Label status = Label.of(Math.max(20, inner - button.width() - 5), statusLine(data, mode));
        status.color(searching ? EdenParchment.ACCENT : EdenParchment.INK_DIM);
        status.tooltipWhenCut(true);
        if (searching && data.state() == DuelsData.SEARCHING && data.searchingMillis() >= 0) {
            searchLabel = status;
        }
        actions.item(status);
        card.item(actions);
        return card;
    }

    /** The queue button: Find match / Leave queue, or a disabled state (in a match, match found). */
    private static Button queueButton(DuelsData data, int mode) {
        DuelsMode stats = data.of(mode);
        Component label;
        Runnable click = () -> { };
        boolean enabled;
        if (data.state() == DuelsData.IN_MATCH) {
            label = tr("in_match");
            enabled = false;
        } else if (stats.searching() && data.state() == DuelsData.PENDING) {
            label = tr("match_found");
            enabled = false;
        } else if (stats.searching()) {
            label = tr("leave");
            click = () -> ClientPlayNetworking.send(new HubPayloads.DuelsQueue(mode, false));
            // Only the party leader can stop a party's search.
            enabled = !(mode == 1 && data.viaParty() && !data.partyLeader());
        } else {
            label = tr("find");
            click = () -> ClientPlayNetworking.send(new HubPayloads.DuelsQueue(mode, true));
            enabled = data.state() != DuelsData.PENDING
                    && !(mode == 1 && data.partySize() > 0 && (!data.partyLeader() || data.partySize() != 2));
        }
        Button button = EdenParchment.button(label, EdenParchment.fitWidth(label, BUTTON_W), 14, click);
        button.enabled(enabled);
        return button;
    }

    private static Component partyHint(DuelsData data) {
        if (data.partySize() == 0) {
            return tr("party_none");
        }
        if (data.partySize() != 2) {
            return tr("party_size", data.partySize());
        }
        return data.partyLeader() ? tr("party_ready") : tr("party_member");
    }

    /** Under the button: the search (ticked), the arena wait, or how many are searching. */
    private static Component statusLine(DuelsData data, int mode) {
        DuelsMode stats = data.of(mode);
        if (data.state() == DuelsData.IN_MATCH) {
            return tr("status_in_match");
        }
        if (stats.searching() && data.state() == DuelsData.PENDING) {
            return tr("status_pending");
        }
        if (stats.searching()) {
            return searching(data, data.searchingMillis());
        }
        return stats.queued() > 0 ? tr("status_queued", stats.queued()) : tr("status_empty");
    }

    private static Component searching(DuelsData data, long millis) {
        long seconds = Math.max(0, millis / 1000);
        String time = String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
        return data.searchRange() > 0 ? tr("status_searching_range", time, data.searchRange())
                : tr("status_searching", time);
    }

    /** Every client tick on the tab: the search timer. */
    static void tick() {
        DuelsData data = HubState.duelsData;
        if (searchLabel == null || data == null || data.searchingMillis() < 0) {
            return;
        }
        long elapsed = data.searchingMillis() + System.currentTimeMillis() - HubState.duelsReceivedAt;
        searchLabel.text(searching(data, elapsed));
    }

    // ------------------------------------------------------------------ lists

    private static void matches(Flex list, List<DuelsMatch> history) {
        if (history.isEmpty()) {
            list.item(EdenParchment.text(tr("no_matches"), EdenParchment.INK_DIM));
            return;
        }
        long age = System.currentTimeMillis() - HubState.duelsReceivedAt;
        boolean alt = false;
        for (DuelsMatch match : history) {
            list.item(matchRow(match, age, alt));
            alt = !alt;
        }
    }

    private static Flex matchRow(DuelsMatch match, long age, boolean alt) {
        Flex row = row(alt);
        int resultColor = switch (match.result()) {
            case 0 -> EdenParchment.GOOD;
            case 1 -> EdenParchment.DANGER;
            default -> EdenParchment.INK_DIM;
        };
        String result = switch (match.result()) {
            case 0 -> "win";
            case 1 -> "loss";
            default -> "draw";
        };
        // Columns sized to their longest text in the current language.
        float resultW = fit(30, tr("result.win"), tr("result.loss"), tr("result.draw"));
        float modeW = fit(20, tr("mode.1v1"), tr("mode.2v2"));
        float changeW = fit(50, tr("unrated"), tr("rating_change", "+99", 9999));
        float timeW = fit(26, Component.literal("99:59"));
        float agoW = fit(40, tr("time.now"), tr("time.minutes", 59), tr("time.hours", 23), tr("time.days", 99));
        row.item(fixed(tr("result." + result), resultW, resultColor, TextNode.Align.LEFT));
        row.item(fixed(tr("mode." + MODES[Math.min(1, Math.max(0, match.mode()))]), modeW, EdenParchment.INK_MID, TextNode.Align.LEFT));

        MutableComponent who = tr("versus", String.join(", ", match.opponents())).copy();
        if (!match.teammates().isEmpty()) {
            who.append(" · ").append(tr("with", String.join(", ", match.teammates())));
        }
        float whoWidth = width - LANE - 12 - resultW - modeW - changeW - timeW - agoW - 5 * 6;
        Label people = Label.of(whoWidth, who);
        people.color(EdenParchment.INK);
        people.tooltipWhenCut(true);
        row.item(people);

        int delta = match.ratingAfter() - match.ratingBefore();
        Component change = match.ratingAfter() == match.ratingBefore() ? tr("unrated")
                : tr("rating_change", (delta > 0 ? "+" : "") + delta, match.ratingAfter());
        row.item(fixed(change, changeW, delta > 0 ? EdenParchment.GOOD : delta < 0 ? EdenParchment.DANGER : EdenParchment.INK_DIM,
                TextNode.Align.RIGHT));
        long seconds = Math.max(0, match.durationMillis() / 1000);
        row.item(fixed(Component.literal(String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)), timeW,
                EdenParchment.INK_DIM, TextNode.Align.RIGHT));
        row.item(fixed(ago(match.agoMillis() + age), agoW, EdenParchment.INK_DIM, TextNode.Align.RIGHT));
        return row;
    }

    private static void leaderboard(Flex list, List<DuelsLeader> top, DuelsMode mine) {
        if (top.isEmpty()) {
            list.item(EdenParchment.text(tr("no_leaders"), EdenParchment.INK_DIM));
            return;
        }
        list.item(leaderHeader());
        UUID self = Minecraft.getInstance().player == null ? null : Minecraft.getInstance().player.getUUID();
        boolean shownSelf = false;
        for (int i = 0; i < top.size(); i++) {
            DuelsLeader leader = top.get(i);
            boolean isSelf = leader.uuid().equals(self);
            shownSelf |= isSelf;
            list.item(leaderRow(i + 1, leader.name(), leader.rating(), leader.played(), isSelf, i % 2 == 1));
        }
        if (!shownSelf && mine.rank() > 0 && Minecraft.getInstance().player != null) {
            list.item(EdenParchment.text(Component.literal("…"), EdenParchment.INK_DIM));
            list.item(leaderRow(mine.rank(), Minecraft.getInstance().player.getGameProfile().getName(),
                    mine.rating(), mine.played(), true, false));
        }
    }

    /** Leaderboard columns: rank, name, then rating and matches sized to their header in this language. */
    private static float rankWidth() {
        return fit(26, Component.literal("#999"));
    }

    private static float ratingWidth() {
        return fit(32, tr("col.rating"), Component.literal("9999"));
    }

    private static float playedWidth() {
        return fit(32, tr("col.played"), Component.literal("9999"));
    }

    private static float nameWidth() {
        return width - LANE - 12 - rankWidth() - ratingWidth() - playedWidth() - 3 * 6;
    }

    private static Flex leaderHeader() {
        Flex row = Flex.row().gap(6).padding(Insets.of(0, 6, 0, 6)).alignItems(Align.CENTER);
        row.sizing(Sizing.fixed(width - LANE), Sizing.fixed(12));
        row.item(fixed(Component.literal("#"), rankWidth(), EdenParchment.INK_DIM, TextNode.Align.LEFT));
        row.item(fixed(tr("col.player"), nameWidth(), EdenParchment.INK_DIM, TextNode.Align.LEFT));
        row.item(fixed(tr("col.rating"), ratingWidth(), EdenParchment.INK_DIM, TextNode.Align.RIGHT));
        row.item(fixed(tr("col.played"), playedWidth(), EdenParchment.INK_DIM, TextNode.Align.RIGHT));
        return row;
    }

    private static Flex leaderRow(int rank, String name, int rating, int played, boolean self, boolean alt) {
        Flex row = row(alt);
        if (self) {
            row.background(EdenParchment.framed(0x33000000 | (EdenParchment.ACCENT & 0xFFFFFF), EdenParchment.ACCENT));
        }
        int rankColor = switch (rank) {
            case 1 -> 0xFFC9A227;
            case 2 -> 0xFF8E9AA6;
            case 3 -> 0xFFA86B3C;
            default -> EdenParchment.INK_DIM;
        };
        if (self) {
            // The medal colors are hard to read on the highlighted row.
            rankColor = EdenParchment.ACCENT;
        }
        row.item(fixed(Component.literal("#" + rank), rankWidth(), rankColor, TextNode.Align.LEFT));
        Label label = Label.of(nameWidth(), Component.literal(name));
        label.color(self ? EdenParchment.ACCENT : EdenParchment.INK);
        row.item(label);
        row.item(fixed(Component.literal(String.valueOf(rating)), ratingWidth(), EdenParchment.INK_MID, TextNode.Align.RIGHT));
        row.item(fixed(Component.literal(String.valueOf(played)), playedWidth(), EdenParchment.INK_DIM, TextNode.Align.RIGHT));
        return row;
    }

    private static Flex row(boolean alt) {
        Flex row = Flex.row().gap(6).padding(Insets.of(0, 6, 0, 6)).alignItems(Align.CENTER);
        row.sizing(Sizing.fixed(width - LANE), Sizing.fixed(ROW_H));
        row.background(EdenParchment.flat(alt ? EdenParchment.ROW_BG_ALT : EdenParchment.ROW_BG));
        return row;
    }

    /** The widest of the samples (plus a little), never below {@code min}. */
    private static float fit(float min, Component... samples) {
        var font = Minecraft.getInstance().font;
        float width = min;
        for (Component sample : samples) {
            width = Math.max(width, font.width(sample) + 2);
        }
        return width;
    }

    private static Label fixed(Component text, float width, int color, TextNode.Align align) {
        Label label = Label.of(width, text);
        label.color(color);
        label.align(align);
        label.tooltipWhenCut(true);
        return label;
    }

    /** Coarse relative time: minutes, hours, then days. */
    private static Component ago(long millis) {
        long minutes = Math.max(0, millis / 60_000L);
        if (minutes < 1) {
            return tr("time.now");
        }
        if (minutes < 60) {
            return tr("time.minutes", minutes);
        }
        long hours = minutes / 60;
        return hours < 24 ? tr("time.hours", hours) : tr("time.days", hours / 24);
    }
}
