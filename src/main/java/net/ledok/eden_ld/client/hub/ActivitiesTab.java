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
    enum Filter { ALL, DUNGEON, RAID, ARENA, DUELS, WAGERS }

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
        if (filter == Filter.DUELS && !duelsAvailable() || filter == Filter.WAGERS && !wagersAvailable()) {
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

        Flex filters = Flex.row().gap(3).alignItems(Align.CENTER);
        filters.sizing(Sizing.fill(), Sizing.content());
        List<Filter> shownFilters = new ArrayList<>();
        for (Filter candidate : Filter.values()) {
            if (candidate == Filter.DUELS ? duelsAvailable() : candidate != Filter.WAGERS || wagersAvailable()) {
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
        filters.item(filterTabs);
        filters.spacer();
        if (data != null && data.gated() && !duels) {
            var level = EdenParchment.text(tr("your_level", data.level()), EdenParchment.INK_MID);
            level.id(HubSpotlights.ACTIVITIES_LEVEL);
            filters.item(level);
        }
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

        Card(ActivityEntry entry, boolean glow, List<String> below) {
            super(cardWidth, CARD_H);
            this.entry = entry;
            this.glow = glow;
            this.below = below;
            this.closed = HubState.closedActivities.contains(entry.id().toString());
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

    /** A short line for the Home card: one name, or how many. */
    static Component homeLine(List<Component> names) {
        return names.size() == 1 ? Component.translatable("gui.eden_ld.hub.home.activity_unlocked", names.get(0))
                : Component.translatable("gui.eden_ld.hub.home.activities_unlocked", names.size());
    }
}
