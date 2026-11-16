package net.ledok.eden_ld.client.hub;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.ledok.eden_ld.client.EdenParchment;
import net.ledok.eden_ld.client.profile.ProfileBackgrounds;
import net.ledok.eden_ld.hub.HubPayloads;
import net.ledok.eden_ld.hub.HubPayloads.ActivityEntry;
import net.ledok.vectorlib.client.canvas.Shapes;
import net.ledok.vectorlib.client.canvas.Stroke;
import net.ledok.vectorlib.client.canvas.TextNode;
import net.ledok.vectorlib.client.canvas.layout.Align;
import net.ledok.vectorlib.client.canvas.layout.Flex;
import net.ledok.vectorlib.client.canvas.layout.Grid;
import net.ledok.vectorlib.client.canvas.layout.Justify;
import net.ledok.vectorlib.client.canvas.layout.Sizing;
import net.ledok.vectorlib.client.canvas.widget.Button;
import net.ledok.vectorlib.client.canvas.widget.ScrollPanel;
import net.ledok.vectorlib.client.canvas.widget.UiSounds;
import net.ledok.vectorlib.client.canvas.widget.Widget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The Activities tab: dungeons, raids and mob arenas as picture cards. Filters (All · Dungeons ·
 * Raids · Arenas · Ranked PvP · Duels) and sorting (name / required level / type, ascending or descending) on top;
 * Ranked PvP (with Duels_LD) shows the duel queues, matches and top lists ({@link DuelsTab}), Duels (with a
 * Duels_LD that has wager duels) the announcements, the player's own wagers and their history ({@link WagersTab}).
 * A card shows only the picture and the name; a locked one is dimmed with the level it needs, a closed
 * one (staff closed it for maintenance) with "Closed" — only staff may still click it.
 * Clicking an open card opens the activity's lobby screen (Arenas_LD) — everything else lives there.
 * Activities the player's level just opened glow until the tab has been seen.
 */
final class ActivitiesTab {

    private static final float LANE = 6;
    private static final float GAP = 5;
    /** A card's narrowest width: three a row at the smallest hub, more as it grows. */
    private static final float CARD_MIN = 130;
    private static final float CARD_H = 74;
    /** Set as the tab builds, from its width. */
    private static int columns = 3;
    private static float cardWidth = CARD_MIN;

    /** {@code DUELS} is Ranked PvP (the name stays: it was the tab's only kind of duel), {@code WAGERS} the wager Duels. */
    enum Filter { ALL, DUNGEON, RAID, ARENA, DUELS, WAGERS, LOBBIES }

    /** The filters in the bar's order: «Лобі» (1.3.99) right after the activity kinds. */
    private static final List<Filter> FILTER_ORDER = List.of(Filter.ALL, Filter.DUNGEON, Filter.RAID, Filter.ARENA,
            Filter.LOBBIES, Filter.DUELS, Filter.WAGERS);

    enum Sort { NAME, LEVEL, TYPE }

    /** Kept while the game runs (like the other tabs' choices). */
    private static Filter filter = Filter.ALL;
    private static Sort sort = Sort.LEVEL;
    private static boolean descending;
    /** Cards that glow on this visit (the server forgets "new" once the tab was seen). */
    private static final Set<String> GLOWING = new HashSet<>();

    private ActivitiesTab() {
    }

    private static Component tr(String key, Object... args) {
        return Component.translatable("gui.eden_ld.hub.activities." + key, args);
    }

    /** The tab was opened: forget the last visit's glow. */
    static void entered() {
        GLOWING.clear();
    }

    /**
     * The «Лобі» list (1.3.99): only while the server lists lobbies but can't create them (an Arenas_LD 4.31–4.32);
     * with 4.33.0+ the whole tab is the lobby browser.
     */
    private static boolean lobbiesFilter() {
        return HubState.lobbiesAvailable && !HubState.lobbiesCreate;
    }

    /** Duels_LD is on the server, so the Ranked PvP filter exists. */
    private static boolean duelsAvailable() {
        return HubState.homeData != null && HubState.homeData.duelsAvailable();
    }

    /** The Ranked PvP filter is showing (the server pushes duel changes while it is). */
    static boolean showingDuels() {
        return filter == Filter.DUELS && duelsAvailable();
    }

    /**
     * The server runs wager duels (a Duels_LD with the wager API, switched on) and takes the pages' requests, so the
     * Duels filter exists.
     */
    static boolean wagersAvailable() {
        return HubState.wagerHeader != null && HubState.wagerHeader.enabled()
                && ClientPlayNetworking.canSend(HubPayloads.WagerAction.TYPE);
    }

    /** The Duels (wager) filter is showing (the server pushes the open page while it is). */
    static boolean showingWagers() {
        return filter == Filter.WAGERS && wagersAvailable();
    }

    /** A filter click: Ranked PvP and Duels subscribe to live data while they're shown. */
    private static void choose(HubScreen screen, Filter next) {
        boolean wasDuels = showingDuels();
        boolean wasWagers = showingWagers();
        filter = next;
        if (wasDuels && !showingDuels()) {
            DuelsTab.left();
        } else if (!wasDuels && showingDuels()) {
            DuelsTab.entered();
        }
        if (wasWagers && !showingWagers()) {
            WagersTab.left();
        } else if (!wasWagers && showingWagers()) {
            WagersTab.entered();
        }
        screen.onActivitiesData();
    }

    static void build(HubScreen screen, Flex content) {
        HubPayloads.ActivitiesData data = HubState.activitiesData;
        float width = HubScreen.innerWidth();
        columns = Math.max(3, (int) ((width - LANE + GAP) / (CARD_MIN + GAP)));
        cardWidth = (width - LANE - GAP * (columns - 1)) / columns;
        if (filter == Filter.DUELS && !duelsAvailable() || filter == Filter.WAGERS && !wagersAvailable()
                || filter == Filter.LOBBIES && !lobbiesFilter()) {
            filter = Filter.ALL;
        }
        boolean duels = showingDuels() || showingWagers();
        boolean noActivities = data == null || data.activities().isEmpty();
        if (noActivities && !duelsAvailable() && !wagersAvailable()) {
            if (data == null) {
                Flex center = Flex.column().justify(Justify.CENTER).alignItems(Align.CENTER);
                center.sizing(Sizing.fill(), Sizing.expand());
                center.item(EdenParchment.text(Component.translatable("gui.eden_ld.hub.home.loading"), EdenParchment.INK_DIM));
                content.item(center);
                return;
            }
            content.item(HubScreen.emptyState(HubTab.ACTIVITIES, tr("none_title"), tr("none")));
            return;
        }
        // Fresh unlocks glow for this visit; the server is told they've been seen.
        if (data != null && !duels) {
            boolean anyFresh = false;
            for (ActivityEntry entry : data.activities()) {
                if (entry.fresh()) {
                    GLOWING.add(entry.id().toString());
                    anyFresh = true;
                }
            }
            if (anyFresh) {
                ClientPlayNetworking.send(HubPayloads.ActivitiesSeen.INSTANCE);
            }
        }

        // The lobby browser (1.3.103) puts the own lobby and «Створити лобі» in the title row; its create panel
        // (1.3.105) takes the whole page, without the kind filters. Everything else has a plain title.
        boolean browser = LobbyBrowser.active() && !duels;
        screen.titleActions();
        if (browser && LobbyBrowser.panelOpen() && data != null) {
            LobbyBrowser.build(screen, content, data, filter == Filter.ALL ? -1 : filter.ordinal() - 1);
            return;
        }
        if (!browser) {
            OwnLobbyButton.build(content);
        }
        Flex filters = Flex.row().gap(3).alignItems(Align.CENTER);
        filters.sizing(Sizing.fill(), Sizing.content());
        List<Filter> shownFilters = new ArrayList<>();
        for (Filter candidate : FILTER_ORDER) {
            boolean available = switch (candidate) {
                case DUELS -> duelsAvailable();
                case WAGERS -> wagersAvailable();
                case LOBBIES -> lobbiesFilter();
                default -> true;
            };
            if (available) {
                shownFilters.add(candidate);
            }
        }
        var filterTabs = EdenParchment.tabs(shownFilters, candidate -> tr("filter." + candidate.name().toLowerCase(Locale.ROOT)),
                filter, candidate -> choose(screen, candidate), 36, "activities/filter");
        filterTabs.id(HubSpotlights.ACTIVITIES_FILTERS);
        // The two kinds of duel are easy to mix up: each says what it is.
        if (shownFilters.contains(Filter.DUELS)) {
            filterTabs.tabTooltip(Filter.DUELS, tr("filter.duels.hint"));
        }
        if (shownFilters.contains(Filter.WAGERS)) {
            filterTabs.tabTooltip(Filter.WAGERS, tr("filter.wagers.hint"));
        }
        if (shownFilters.contains(Filter.LOBBIES)) {
            filterTabs.tabTooltip(Filter.LOBBIES, tr("filter.lobbies.hint"));
        }
        filters.item(filterTabs);
        // The level sits on the row below (sorting, or the «Лобі» list's head): the filters fill this one (1.3.100).
        content.item(filters);

        if (showingDuels()) {
            DuelsTab.build(screen, content);
            return;
        }
        if (showingWagers()) {
            WagersTab.build(screen, content);
            return;
        }
        if (data == null) {
            content.item(EdenParchment.text(Component.translatable("gui.eden_ld.hub.home.loading"), EdenParchment.INK_DIM));
            return;
        }
        if (browser) {
            LobbyBrowser.build(screen, content, data, filter == Filter.ALL ? -1 : filter.ordinal() - 1);
            return;
        }
        if (filter == Filter.LOBBIES) {
            if (data.gated()) {
                Flex head = Flex.row().alignItems(Align.CENTER);
                head.sizing(Sizing.fill(), Sizing.content());
                head.spacer();
                head.item(levelLabel(data));
                content.item(head);
            }
            buildLobbies(data, content);
            return;
        }
        if (data.activities().isEmpty()) {
            content.item(HubScreen.emptyState(HubTab.ACTIVITIES, tr("none_title"), tr("none")));
            return;
        }

        Flex sorting = Flex.row().gap(3).alignItems(Align.CENTER);
        sorting.id(HubSpotlights.ACTIVITIES_SORT);
        sorting.sizing(Sizing.fill(), Sizing.content());
        sorting.item(EdenParchment.text(tr("sort"), EdenParchment.INK_DIM));
        sorting.item(EdenParchment.tabs(List.of(Sort.values()), candidate -> tr("sort." + candidate.name().toLowerCase(Locale.ROOT)),
                sort, candidate -> {
                    sort = candidate;
                    screen.onActivitiesData();
                }, 30, "activities/sort").height(12));
        Button order = new Button(14, 12, Component.literal(descending ? "↓" : "↑"), () -> {
            descending = !descending;
            screen.onActivitiesData();
        });
        order.tooltip(tr(descending ? "descending" : "ascending"));
        sorting.item(order);
        if (data.gated()) {
            sorting.spacer();
            sorting.item(levelLabel(data));
        }
        content.item(sorting);

        List<ActivityEntry> shown = new ArrayList<>();
        for (ActivityEntry entry : data.activities()) {
            if (filter == Filter.ALL || entry.type() == filter.ordinal() - 1) {
                shown.add(entry);
            }
        }
        shown.sort(comparator());
        if (shown.isEmpty()) {
            content.item(EdenParchment.text(tr("none_here"), EdenParchment.INK_DIM));
            return;
        }
        Grid grid = Grid.of(columns).gap(GAP, GAP).cell(cardWidth, CARD_H);
        grid.id(HubSpotlights.ACTIVITIES_CARDS);
        for (ActivityEntry entry : shown) {
            Card card = new Card(entry, GLOWING.contains(entry.id().toString()), below(data, entry));
            if (entry == shown.get(0)) {
                card.id(HubSpotlights.ACTIVITIES_CARD);
            }
            grid.item(card);
        }
        ScrollPanel panel = HubScroll.panel("activities", HubScreen.innerWidth(), 100, grid);
        panel.sizing(Sizing.fill(), Sizing.expand());
        content.item(panel);
    }

    /** Online partymates below the activity's level (only for cards the player has open). */
    private static List<String> below(HubPayloads.ActivitiesData data, ActivityEntry entry) {
        List<String> names = new ArrayList<>();
        if (entry.unlocked()) {
            for (HubPayloads.PartyLevel member : data.party()) {
                if (member.level() < entry.unlockLevel()) {
                    names.add(member.name());
                }
            }
        }
        return names;
    }

    private static Comparator<ActivityEntry> comparator() {
        Comparator<ActivityEntry> byName = Comparator.comparing(e -> e.name().getString().toLowerCase(Locale.ROOT));
        Comparator<ActivityEntry> primary = switch (sort) {
            case NAME -> byName;
            case LEVEL -> Comparator.comparingInt(ActivityEntry::unlockLevel);
            case TYPE -> Comparator.comparingInt(ActivityEntry::type);
        };
        if (descending) {
            primary = primary.reversed();
        }
        return primary.thenComparing(byName);
    }

    /**
     * A picture card: name top-left; locked → dimmed with the level it needs; closed → dimmed with
     * "Closed"; new → glowing frame.
     */
    private static final class Card extends Widget {
        private final ActivityEntry entry;
        private final boolean glow;
        /** Closed for maintenance ({@link HubPayloads.ActivitiesClosed}). */
        private final boolean closed;
        /** Partymates below its level: a ⚠ in the corner, the names in the tooltip. */
        private final List<String> below;
        /** The lobbies forming now that the player may see (the badge, and the tooltip's list), best first. */
        private final List<HubPayloads.LobbyLine> lobbies;

        Card(ActivityEntry entry, boolean glow, List<String> below) {
            super(cardWidth, CARD_H);
            this.entry = entry;
            this.glow = glow;
            this.below = below;
            this.closed = HubState.closedActivities.contains(entry.id().toString());
            this.lobbies = sorted(HubState.activityLobbies.getOrDefault(entry.id().toString(), List.of()));
            sizing(Sizing.fixed(cardWidth), Sizing.fixed(CARD_H));
            tooltip(() -> {
                List<Component> lines = new ArrayList<>();
                lines.add(entry.name());
                if (closed) {
                    lines.add(tr("closed_hint"));
                    if (HubState.closedBypass) {
                        lines.add(tr("closed_staff").copy().withStyle(net.minecraft.ChatFormatting.GOLD));
                    }
                    return lines;
                }
                lines.add(entry.unlocked() ? tr("open_hint") : tr("min_level", entry.unlockLevel()));
                if (!below.isEmpty()) {
                    lines.add(tr("party_below", entry.unlockLevel(), String.join(", ", below))
                            .copy().withStyle(net.minecraft.ChatFormatting.GOLD));
                }
                if (entry.unlocked() && !lobbies.isEmpty()) {
                    lines.add(tr("lobbies.title").copy().withStyle(net.minecraft.ChatFormatting.GRAY));
                    for (int i = 0; i < Math.min(LOBBY_LINES, lobbies.size()); i++) {
                        lines.add(lobbyLine(lobbies.get(i)));
                    }
                    if (lobbies.size() > LOBBY_LINES) {
                        lines.add(tr("lobbies.more", lobbies.size() - LOBBY_LINES)
                                .copy().withStyle(net.minecraft.ChatFormatting.GRAY));
                    }
                }
                return lines;
            });
        }

        @Override
        protected void rebuild() {
            boolean art = !entry.cardArt().isEmpty() && ProfileBackgrounds.get(entry.cardArt()) != null;
            if (art) {
                add(ProfileBackgrounds.card(entry.cardArt(), width, height));
            } else {
                add(Shapes.rect(0, 0, width, height).fill(EdenParchment.ROW_BG_ALT));
            }
            if (!entry.unlocked() || closed) {
                add(Shapes.rect(0, 0, width, height).fill(0xB0000000));
            } else if (hovered) {
                add(Shapes.rect(0, 0, width, height).fill(0x22FFFFFF));
            }
            int frame = glow ? HubNotifications.color() : hovered && clickable() ? EdenParchment.ACCENT : EdenParchment.HAIRLINE;
            add(Shapes.rect(0.5f, 0.5f, width - 1, height - 1).stroke(Stroke.of(frame, glow ? 2 : 1)));
            // Light text with a shadow over pictures and the dark lock; theme ink on the plain card.
            boolean light = art || !entry.unlocked() || closed;
            add(TextNode.of(entry.name()).at(5, 5).maxWidth((int) width - 10).ellipsis(true)
                    .color(light ? 0xFFFFFFFF : EdenParchment.INK).shadow(light));
            if (!below.isEmpty()) {
                HubIcons.Icon warning = HubIcons.get("warning");
                if (warning != null) {
                    add(warning.node(8).at(width - 5 - 8, 4));
                } else {
                    add(TextNode.of(Component.literal("⚠")).at(width - 5, 4).align(TextNode.Align.RIGHT)
                            .color(0xFFF2C94C).shadow(true));
                }
            }
            if (entry.unlocked() && !closed && !lobbies.isEmpty()) {
                badge();
            }
            if (closed) {
                add(TextNode.of(tr("closed")).at(width / 2, height / 2 - 6)
                        .align(TextNode.Align.CENTER).maxWidth((int) width - 8).ellipsis(true)
                        .color(0xFFE6DCC8).shadow(true));
                add(TextNode.of(tr("closed_hint")).at(width / 2, height / 2 + 5)
                        .align(TextNode.Align.CENTER).maxWidth((int) width - 8).ellipsis(true)
                        .color(0xFFE6DCC8).shadow(true).scale(0.8f));
            } else if (!entry.unlocked()) {
                // Two lines, so the level is never cut off.
                add(TextNode.of(tr("locked")).at(width / 2, height / 2 - 6)
                        .align(TextNode.Align.CENTER).maxWidth((int) width - 8).ellipsis(true)
                        .color(0xFFE6DCC8).shadow(true));
                add(TextNode.of(tr("min_level", entry.unlockLevel())).at(width / 2, height / 2 + 5)
                        .align(TextNode.Align.CENTER).maxWidth((int) width - 8).ellipsis(true)
                        .color(0xFFE6DCC8).shadow(true));
            }
        }

        /**
         * Bottom-right (Tend1tnuy's mock-up, 1.3.98): how many lobbies are forming and the best one to join, e.g.
         * «2 лобі · 3/4 · Важко». Clicking the card opens them, as before.
         */
        private void badge() {
            HubPayloads.LobbyLine best = lobbies.get(0);
            Component text = Component.empty()
                    .append(tr(lobbies.size() == 1 ? "lobbies.count_one" : "lobbies.count", lobbies.size()))
                    .append(" · " + best.members() + "/" + best.maxMembers() + " · ")
                    .append(difficulty(best.difficulty()));
            float scale = 0.8f;
            float max = width - 10;
            float textWidth = Math.min(max - 6, net.minecraft.client.Minecraft.getInstance().font.width(text) * scale);
            float w = textWidth + 6;
            float h = 10;
            float x = width - 4 - w;
            float y = height - 4 - h;
            add(Shapes.rect(x, y, w, h).fill(0xC0101010));
            add(Shapes.rect(x + 0.5f, y + 0.5f, w - 1, h - 1).stroke(Stroke.of(best.hardcore() ? 0xFFD05050 : 0x80E6DCC8, 1)));
            add(TextNode.of(text).at(x + 3, y + 2).maxWidth((int) (textWidth / scale)).ellipsis(true)
                    .color(0xFFF2E6C8).shadow(true).scale(scale));
        }

        @Override
        public boolean onMouseDown(float x, float y, int button) {
            if (button != 0 || !clickable()) {
                return false;
            }
            UiSounds.click();
            ActivityReturn.arm();
            ClientPlayNetworking.send(new HubPayloads.ActivityOpen(entry.id()));
            return true;
        }

        @Override
        public Cursor cursor() {
            return clickable() ? Cursor.HAND : Cursor.DEFAULT;
        }

        /** Open, and not closed for maintenance (staff may still enter a closed one, to test it). */
        private boolean clickable() {
            return entry.unlocked() && (!closed || HubState.closedBypass);
        }
    }

    /** «Your level: 64», named for the tutorial's spotlight. */
    private static net.ledok.vectorlib.client.canvas.TextNode levelLabel(HubPayloads.ActivitiesData data) {
        var level = EdenParchment.text(tr("your_level", data.level()), EdenParchment.INK_MID);
        level.id(HubSpotlights.ACTIVITIES_LEVEL);
        return level;
    }

    // ---------------------------------------------------------------- the «Лобі» list (1.3.99)

    /**
     * Every open lobby the player may see, grouped by activity (in the cards' order: by name), each row with Join (public),
     * Request (friends-only) or Open (theirs). Rows of an activity the player can't enter yet (level, closed) or of a
     * full lobby have the button greyed, with why in its tooltip.
     */
    private static void buildLobbies(HubPayloads.ActivitiesData data, Flex content) {
        float width = HubScreen.innerWidth() - LANE;
        Flex list = Flex.column().gap(2);
        list.sizing(Sizing.fill(), Sizing.content());
        java.util.Map<String, ActivityEntry> byId = new java.util.HashMap<>();
        for (ActivityEntry entry : data.activities()) {
            byId.put(entry.id().toString(), entry);
        }
        List<String> ids = new ArrayList<>(HubState.activityLobbies.keySet());
        ids.sort(Comparator.comparing(id -> byId.containsKey(id)
                ? byId.get(id).name().getString().toLowerCase(Locale.ROOT) : id));
        boolean any = false;
        for (String id : ids) {
            List<HubPayloads.LobbyLine> lobbies = sorted(HubState.activityLobbies.get(id));
            if (lobbies.isEmpty()) {
                continue;
            }
            ActivityEntry entry = byId.get(id);
            if (any) {
                list.item(EdenParchment.spacer(4));
            }
            any = true;
            Component name = entry != null ? entry.name() : Component.literal(id);
            list.item(EdenParchment.text(Component.empty().append(name).append(" (" + lobbies.size() + ")"),
                    EdenParchment.INK_MID));
            Component blocked = entry == null ? tr("lobbies.unknown")
                    : HubState.closedActivities.contains(id) && !HubState.closedBypass ? tr("closed")
                    : !entry.unlocked() ? tr("min_level", entry.unlockLevel()) : null;
            for (int i = 0; i < lobbies.size(); i++) {
                list.item(lobbyRow(entry == null ? null : entry.id(), lobbies.get(i), blocked, i % 2 == 1, width));
            }
        }
        if (!any) {
            content.item(HubScreen.emptyState(HubTab.ACTIVITIES, tr("lobbies.none_title"), tr("lobbies.none")));
            return;
        }
        ScrollPanel panel = HubScroll.panel("activities/lobbies", HubScreen.innerWidth(), 100, list);
        panel.sizing(Sizing.fill(), Sizing.expand());
        content.item(panel);
    }

    /** «Owner · 3/4 · Важко · Хардкор» and the row's button. */
    private static Flex lobbyRow(net.minecraft.resources.@org.jetbrains.annotations.Nullable ResourceLocation activity,
                                 HubPayloads.LobbyLine lobby, @org.jetbrains.annotations.Nullable Component blocked,
                                 boolean alt, float width) {
        Flex row = Flex.row().gap(4).padding(net.ledok.vectorlib.client.canvas.layout.Insets.of(2, 3, 2, 3))
                .alignItems(Align.CENTER);
        row.sizing(Sizing.fixed(width), Sizing.content());
        row.background(EdenParchment.framed(alt ? EdenParchment.ROW_BG_ALT : EdenParchment.ROW_BG, EdenParchment.HAIRLINE));
        Component label = tr(lobby.member() ? "lobbies.open" : lobby.request() ? "lobbies.ask" : "lobbies.join");
        float buttonWidth = EdenParchment.fitWidth(label, 60);
        net.minecraft.network.chat.MutableComponent text = Component.literal(lobby.owner() + " · " + lobby.members()
                + "/" + lobby.maxMembers() + " · ").append(difficulty(lobby.difficulty()));
        if (lobby.hardcore()) {
            text.append(" · ").append(tr("lobbies.hardcore").copy().withStyle(net.minecraft.ChatFormatting.DARK_RED));
        }
        if (lobby.member()) {
            text.append(" · ").append(tr("lobbies.yours"));
        } else if (lobby.full()) {
            text.append(" · ").append(tr("lobbies.full"));
        } else if (lobby.request()) {
            text.append(" · ").append(tr("lobbies.request"));
        }
        row.item(EdenParchment.text(text, lobby.full() && !lobby.member() ? EdenParchment.INK_DIM : EdenParchment.INK)
                .maxWidth((int) (width - 6 - buttonWidth - 8)).ellipsis(true));
        row.spacer();
        Button button = EdenParchment.button(label, buttonWidth, 14, () -> {
            if (activity != null) {
                ActivityReturn.arm();
                ClientPlayNetworking.send(new HubPayloads.LobbyJoin(activity, lobby.lobby()));
            }
        });
        Component why = lobby.member() ? null : blocked != null ? blocked : lobby.full() ? tr("lobbies.full_hint") : null;
        if (activity == null || why != null) {
            button.enabled(false);
        }
        button.tooltip(why != null ? why : tr(lobby.member() ? "lobbies.open_hint"
                : lobby.request() ? "lobbies.ask_hint" : "lobbies.join_hint"));
        row.item(button);
        return row;
    }

    /** Lobbies listed in a card's tooltip (more are counted). */
    private static final int LOBBY_LINES = 6;

    /**
     * Best first: ones the player can join now (not full, not theirs, public before on request), then the fullest
     * (closest to starting), then by owner.
     */
    private static List<HubPayloads.LobbyLine> sorted(List<HubPayloads.LobbyLine> lobbies) {
        if (lobbies.size() < 2) {
            return lobbies;
        }
        List<HubPayloads.LobbyLine> out = new ArrayList<>(lobbies);
        out.sort(Comparator.comparingInt((HubPayloads.LobbyLine l) -> l.full() || l.member() ? 2 : l.request() ? 1 : 0)
                .thenComparingInt(l -> -l.members())
                .thenComparing(l -> l.owner().toLowerCase(Locale.ROOT)));
        return out;
    }

    /** «Owner · 3/4 · Важко · Хардкор · за запитом» (gray when full, green when the player is in it). */
    private static Component lobbyLine(HubPayloads.LobbyLine lobby) {
        net.minecraft.network.chat.MutableComponent line = Component.literal(lobby.owner() + " · " + lobby.members()
                + "/" + lobby.maxMembers() + " · ").append(difficulty(lobby.difficulty()));
        if (lobby.hardcore()) {
            line.append(" · ").append(tr("lobbies.hardcore").copy().withStyle(net.minecraft.ChatFormatting.RED));
        }
        if (lobby.member()) {
            line.append(" · ").append(tr("lobbies.yours"));
            return line.withStyle(net.minecraft.ChatFormatting.GREEN);
        }
        if (lobby.full()) {
            line.append(" · ").append(tr("lobbies.full"));
            return line.withStyle(net.minecraft.ChatFormatting.DARK_GRAY);
        }
        if (lobby.request()) {
            line.append(" · ").append(tr("lobbies.request"));
        }
        return line.withStyle(net.minecraft.ChatFormatting.WHITE);
    }

    /** A difficulty id's name ("hard" → «Важко»); an unknown id as it is. */
    private static Component difficulty(String id) {
        String key = "gui.eden_ld.moderation.activities.difficulty." + id;
        return net.minecraft.client.resources.language.I18n.exists(key) ? Component.translatable(key)
                : Component.literal(id);
    }

    /** A short line for the Home card: one name, or how many. */
    static Component homeLine(List<Component> names) {
        return names.size() == 1 ? Component.translatable("gui.eden_ld.hub.home.activity_unlocked", names.get(0))
                : Component.translatable("gui.eden_ld.hub.home.activities_unlocked", names.size());
    }
}
