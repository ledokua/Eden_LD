package net.ledok.eden_ld.client.hub;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.ledok.eden_ld.client.EdenParchment;
import net.ledok.eden_ld.client.profile.ProfileBackgrounds;
import net.ledok.eden_ld.hub.HubPayloads;
import net.ledok.eden_ld.hub.HubPayloads.ActivityEntry;
import net.ledok.vectorlib.client.canvas.ItemStackNode;
import net.ledok.vectorlib.client.canvas.Shapes;
import net.ledok.vectorlib.client.canvas.Stroke;
import net.ledok.vectorlib.client.canvas.TextNode;
import net.ledok.vectorlib.client.canvas.layout.Align;
import net.ledok.vectorlib.client.canvas.layout.Flex;
import net.ledok.vectorlib.client.canvas.layout.Insets;
import net.ledok.vectorlib.client.canvas.layout.Sizing;
import net.ledok.vectorlib.client.canvas.widget.Button;
import net.ledok.vectorlib.client.canvas.widget.Dropdown;
import net.ledok.vectorlib.client.canvas.widget.NumberField;
import net.ledok.vectorlib.client.canvas.widget.ScrollPanel;
import net.ledok.vectorlib.client.canvas.widget.Toggle;
import net.ledok.vectorlib.client.canvas.widget.Widget;
import net.minecraft.ChatFormatting;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The Activities tab as a lobby browser (1.3.103, LeDok's plan {@code Issues/2026-11-16-activities-lobby-browser-PLAN.md},
 * with Arenas_LD 4.33.0+): every lobby forming now that the player may see, one row each (activity, difficulty, hardcore
 * and modifiers, owner, friends-only lock, minimum level, members and ready), filtered by kind (the tab's filter bar),
 * activity, difficulty, hardcore and "only ones I can join", sorted by newest / players / level, with Приєднатися /
 * Попроситися / Відкрити. On top: the player's own lobby, «Змінити лобі» for its owner and «Створити лобі».
 * <p>
 * «Створити лобі» opens the panel in place of the list: the activities on the left (locked ones greyed with why), the
 * picked one's art, level, size and possible drops in the middle, and its settings on the right (difficulty, hardcore,
 * run modifiers with their reward effect, visibility, minimum join level). The same panel edits the owner's lobby.
 * The old card grid is gone here (LeDok: the panel's activity list replaces it); it stays for servers whose Arenas_LD
 * can't create lobbies.
 */
final class LobbyBrowser {

    enum Sort { NEWEST, PLAYERS, LEVEL }

    private static final List<String> DIFFICULTIES = List.of("easy", "normal", "hard", "nightmare");
    /** Hardcore filter: any, only hardcore, only without. */
    private static final List<Integer> HARDCORE_FILTERS = List.of(0, 1, 2);
    private static final String HARDCORE = "hardcore_run";

    // The list's choices, kept while the game runs (like the other tabs' choices).
    private static String activityFilter = "";
    private static String difficultyFilter = "";
    private static int hardcoreFilter;
    private static boolean joinableOnly;
    private static Sort sort = Sort.NEWEST;

    // The panel.
    private static boolean panelOpen;
    private static boolean editing;
    /** «Створити лобі» was sent: the panel closes once the player owns a lobby (its screen opened meanwhile). */
    private static boolean awaitingLobby;
    private static int panelKind = -1;
    @Nullable
    private static ResourceLocation panelActivity;
    @Nullable
    private static HubPayloads.ActivityInfoData info;
    private static String difficulty = "";
    private static boolean hardcore;
    private static final Set<String> modifiers = new LinkedHashSet<>();
    private static int visibility;
    private static int minLevel;
    /** The edited lobby's settings were taken over from the server (once per edit). */
    private static boolean prefilled;

    private LobbyBrowser() {
    }

    private static Component tr(String key, Object... args) {
        return Component.translatable("gui.eden_ld.hub.activities." + key, args);
    }

    /** The server creates lobbies from the hub: the Activities tab is this browser instead of the card grid. */
    static boolean active() {
        return HubState.lobbiesCreate;
    }

    /** A new server: nothing chosen in the panel carries over. */
    static void reset() {
        panelOpen = false;
        editing = false;
        awaitingLobby = false;
        panelActivity = null;
        info = null;
    }

    // ---------------------------------------------------------------- from the server

    /** The panel's data for an activity: kept when it's the one shown; an edit takes the lobby's settings once. */
    static boolean onInfo(HubPayloads.ActivityInfoData data) {
        if (!panelOpen || !data.activity().equals(panelActivity)) {
            return false;
        }
        info = data;
        if (editing && data.hasCurrent() && !prefilled) {
            prefilled = true;
            difficulty = data.difficulty();
            hardcore = data.hardcore();
            modifiers.clear();
            for (String id : data.chosen()) {
                if (!HARDCORE.equals(id)) {
                    modifiers.add(id);
                }
            }
            visibility = data.visibility();
            minLevel = data.minLevel();
        }
        if (!data.difficulties().isEmpty() && !data.difficulties().contains(difficulty)) {
            difficulty = firstOpenDifficulty(data);
            request();
        }
        // Modifiers the activity doesn't offer (another activity's) go.
        modifiers.removeIf(id -> data.modifiers().stream().noneMatch(m -> m.id().equals(id)));
        return true;
    }

    /** The own lobby changed: a lobby made from the panel closes it. */
    static void onOwnLobby(HubPayloads.OwnLobbyData lobby) {
        if (awaitingLobby && lobby.present()) {
            awaitingLobby = false;
            panelOpen = false;
        }
        if (editing && (!lobby.present() || !lobby.owner())) {
            // No longer theirs to edit.
            panelOpen = false;
            editing = false;
        }
    }

    // ---------------------------------------------------------------- the list

    static void build(HubScreen screen, Flex content, HubPayloads.ActivitiesData data, int kind) {
        if (panelOpen) {
            buildPanel(screen, content, data);
            return;
        }
        Map<String, ActivityEntry> byId = byId(data);
        float width = HubScreen.innerWidth();

        // My lobby on the left, «Змінити лобі» and «Створити лобі» on the right.
        Flex top = Flex.row().gap(4).alignItems(Align.CENTER);
        top.sizing(Sizing.fill(), Sizing.content());
        Flex own = OwnLobbyButton.row();
        if (own != null) {
            top.item(own);
        }
        top.spacer();
        HubPayloads.OwnLobbyData lobby = OwnLobbyButton.data();
        if (lobby.present() && lobby.owner() && !lobby.inRun() && !lobby.activity().isEmpty()) {
            Component label = tr("browser.edit");
            Button edit = EdenParchment.button(label, EdenParchment.fitWidth(label, 70), 14,
                    () -> openPanel(screen, ResourceLocation.tryParse(lobby.activity()), true, kind));
            edit.tooltip(tr("browser.edit_hint"));
            top.item(edit);
        }
        Component createLabel = tr("browser.create");
        Button create = EdenParchment.button(createLabel, EdenParchment.fitWidth(createLabel, 80), 14,
                () -> openPanel(screen, null, false, kind));
        create.tooltip(tr("browser.create_hint"));
        top.item(create);
        content.item(top);

        // Activity, difficulty, hardcore, joinable.
        Flex filters = Flex.row().gap(4).alignItems(Align.CENTER);
        filters.sizing(Sizing.fill(), Sizing.content());
        List<String> activityOptions = new ArrayList<>();
        activityOptions.add("");
        for (ActivityEntry entry : sortedEntries(data, kind)) {
            activityOptions.add(entry.id().toString());
        }
        if (!activityOptions.contains(activityFilter)) {
            activityFilter = "";
        }
        filters.item(new Dropdown<>(110, activityOptions, activityFilter, id -> id.isEmpty() ? tr("browser.any_activity")
                : byId.containsKey(id) ? byId.get(id).name() : Component.literal(id), id -> {
                    activityFilter = id;
                    screen.onActivitiesData();
                }));
        List<String> difficultyOptions = new ArrayList<>();
        difficultyOptions.add("");
        difficultyOptions.addAll(DIFFICULTIES);
        filters.item(new Dropdown<>(90, difficultyOptions, difficultyFilter,
                id -> id.isEmpty() ? tr("browser.any_difficulty") : difficultyName(id), id -> {
                    difficultyFilter = id;
                    screen.onActivitiesData();
                }));
        filters.item(new Dropdown<>(90, HARDCORE_FILTERS, hardcoreFilter, value -> tr("browser.hardcore." + value), value -> {
            hardcoreFilter = value;
            screen.onActivitiesData();
        }));
        filters.item(new Toggle(100, tr("browser.joinable"), joinableOnly, value -> {
            joinableOnly = value;
            screen.onActivitiesData();
        }));
        content.item(filters);

        // Sorting, and the player's level on the right.
        Flex sorting = Flex.row().gap(3).alignItems(Align.CENTER);
        sorting.id(HubSpotlights.ACTIVITIES_SORT);
        sorting.sizing(Sizing.fill(), Sizing.content());
        sorting.item(EdenParchment.text(tr("sort"), EdenParchment.INK_DIM));
        sorting.item(EdenParchment.tabs(List.of(Sort.values()), candidate -> tr("browser.sort." + candidate.name()
                .toLowerCase(Locale.ROOT)), sort, candidate -> {
                    sort = candidate;
                    screen.onActivitiesData();
                }, 30, "activities/browser_sort").height(12));
        if (data.gated()) {
            sorting.spacer();
            var level = EdenParchment.text(tr("your_level", data.level()), EdenParchment.INK_MID);
            level.id(HubSpotlights.ACTIVITIES_LEVEL);
            sorting.item(level);
        }
        content.item(sorting);

        // The rows.
        List<Row> rows = rows(data, byId, kind);
        if (rows.isEmpty()) {
            Flex empty = HubScreen.emptyState(HubTab.ACTIVITIES, tr("lobbies.none_title"), tr("browser.none"));
            empty.id(HubSpotlights.ACTIVITIES_CARDS);
            content.item(empty);
            return;
        }
        float rowWidth = width - 6;
        Flex list = Flex.column().gap(2);
        list.sizing(Sizing.fill(), Sizing.content());
        list.id(HubSpotlights.ACTIVITIES_CARDS);
        for (int i = 0; i < rows.size(); i++) {
            Flex row = row(rows.get(i), data, i % 2 == 1, rowWidth);
            if (i == 0) {
                row.id(HubSpotlights.ACTIVITIES_CARD);
            }
            list.item(row);
        }
        ScrollPanel panel = HubScroll.panel("activities/browser", width, 100, list);
        panel.sizing(Sizing.fill(), Sizing.expand());
        content.item(panel);
    }

    /** One lobby in the list, with its activity. */
    private record Row(String activity, @Nullable ActivityEntry entry, HubPayloads.LobbyLine lobby) {
    }

    private static List<Row> rows(HubPayloads.ActivitiesData data, Map<String, ActivityEntry> byId, int kind) {
        List<Row> rows = new ArrayList<>();
        HubState.activityLobbies.forEach((activity, lobbies) -> {
            ActivityEntry entry = byId.get(activity);
            if (kind >= 0 && (entry == null || entry.type() != kind)) {
                return;
            }
            if (!activityFilter.isEmpty() && !activityFilter.equals(activity)) {
                return;
            }
            for (HubPayloads.LobbyLine lobby : lobbies) {
                if (!difficultyFilter.isEmpty() && !difficultyFilter.equals(lobby.difficulty())) {
                    continue;
                }
                if (hardcoreFilter == 1 && !lobby.hardcore() || hardcoreFilter == 2 && lobby.hardcore()) {
                    continue;
                }
                if (joinableOnly && blocked(data, entry, activity, lobby) != null) {
                    continue;
                }
                rows.add(new Row(activity, entry, lobby));
            }
        });
        Comparator<Row> order = switch (sort) {
            case NEWEST -> Comparator.comparingLong((Row r) -> -r.lobby().createdAt());
            case PLAYERS -> Comparator.comparingInt((Row r) -> -r.lobby().members())
                    .thenComparingLong(r -> -r.lobby().createdAt());
            case LEVEL -> Comparator.comparingInt((Row r) -> r.lobby().minLevel())
                    .thenComparingLong(r -> -r.lobby().createdAt());
        };
        // The player's own lobby first, whatever the order.
        rows.sort(Comparator.comparing((Row r) -> !r.lobby().member()).thenComparing(order));
        return rows;
    }

    /** Why the player can't join this lobby now (null: they can, or it's theirs). */
    @Nullable
    private static Component blocked(HubPayloads.ActivitiesData data, @Nullable ActivityEntry entry, String activity,
                                     HubPayloads.LobbyLine lobby) {
        if (lobby.member()) {
            return null;
        }
        if (entry == null) {
            return tr("lobbies.unknown");
        }
        if (HubState.closedActivities.contains(activity) && !HubState.closedBypass) {
            return tr("closed");
        }
        if (!entry.unlocked()) {
            return tr("min_level", entry.unlockLevel());
        }
        if (lobby.full()) {
            return tr("lobbies.full_hint");
        }
        if (data.gated() && lobby.minLevel() > 0 && data.level() < lobby.minLevel()) {
            return tr("browser.min_level_hint", lobby.minLevel());
        }
        return null;
    }

    /**
     * Two lines and a button: «Activity  [Важко]  Хардкор · modifiers» over «owner · 🔒 · Рів. 20+ · 3/4 (2 готові)».
     */
    private static Flex row(Row row, HubPayloads.ActivitiesData data, boolean alt, float width) {
        HubPayloads.LobbyLine lobby = row.lobby();
        Flex box = Flex.row().gap(4).padding(Insets.of(2, 3, 2, 3)).alignItems(Align.CENTER);
        box.sizing(Sizing.fixed(width), Sizing.content());
        box.background(EdenParchment.framed(alt ? EdenParchment.ROW_BG_ALT : EdenParchment.ROW_BG,
                lobby.member() ? EdenParchment.ACCENT : EdenParchment.HAIRLINE));

        Component label = tr(lobby.member() ? "lobbies.open" : lobby.request() ? "lobbies.ask" : "lobbies.join");
        float buttonWidth = EdenParchment.fitWidth(label, 64);
        int textWidth = (int) (width - 6 - buttonWidth - 8);

        Flex lines = Flex.column().gap(1);
        Flex first = Flex.row().gap(4).alignItems(Align.CENTER);
        Component name = row.entry() != null ? row.entry().name() : Component.literal(row.activity());
        first.item(EdenParchment.text(name, EdenParchment.INK).maxWidth(textWidth / 2).ellipsis(true));
        if (!lobby.difficulty().isEmpty()) {
            first.item(new Pill(difficultyName(lobby.difficulty()), difficultyColor(lobby.difficulty())));
        }
        MutableComponent tags = Component.empty();
        if (lobby.hardcore()) {
            tags.append(tr("lobbies.hardcore").copy().withStyle(ChatFormatting.DARK_RED));
        }
        for (Component modifier : lobby.modifiers()) {
            if (!tags.getSiblings().isEmpty()) {
                tags.append(" · ");
            }
            tags.append(modifier);
        }
        if (!tags.getSiblings().isEmpty()) {
            first.item(EdenParchment.text(tags, EdenParchment.INK_MID).maxWidth(textWidth / 2).ellipsis(true));
        }
        lines.item(first);

        MutableComponent second = Component.literal(lobby.owner());
        if (lobby.request()) {
            second.append(" · ").append(tr("browser.friends_only"));
        }
        if (lobby.minLevel() > 0) {
            second.append(" · ").append(tr("browser.min_level", lobby.minLevel()));
        }
        second.append(" · ").append(tr("browser.members", lobby.members(), lobby.maxMembers(), lobby.ready()));
        if (lobby.member()) {
            second.append(" · ").append(tr("lobbies.yours"));
        }
        lines.item(EdenParchment.text(second, EdenParchment.INK_DIM).maxWidth(textWidth).ellipsis(true));
        box.item(lines);
        box.spacer();

        Component why = blocked(data, row.entry(), row.activity(), lobby);
        ResourceLocation activity = ResourceLocation.tryParse(row.activity());
        Button button = EdenParchment.button(label, buttonWidth, 14, () -> {
            if (activity == null) {
                return;
            }
            ActivityReturn.arm();
            if (lobby.member()) {
                ClientPlayNetworking.send(HubPayloads.OwnLobbyOpen.INSTANCE);
            } else {
                ClientPlayNetworking.send(new HubPayloads.LobbyJoin(activity, lobby.lobby()));
            }
        });
        if (activity == null || why != null) {
            button.enabled(false);
        }
        button.tooltip(why != null ? why : tr(lobby.member() ? "lobbies.open_hint"
                : lobby.request() ? "lobbies.ask_hint" : "lobbies.join_hint"));
        box.item(button);
        return box;
    }

    /** A small colored label (the difficulty). */
    private static final class Pill extends Widget {
        private final Component text;
        private final int color;

        Pill(Component text, int color) {
            super(net.minecraft.client.Minecraft.getInstance().font.width(text) * 0.8f + 8, 10);
            this.text = text;
            this.color = color;
            sizing(Sizing.fixed(width), Sizing.fixed(10));
        }

        @Override
        protected void rebuild() {
            add(Shapes.rect(0, 0, width, height).fill(0x33000000 | (color & 0xFFFFFF)));
            add(Shapes.rect(0.5f, 0.5f, width - 1, height - 1).stroke(Stroke.of(color, 1)));
            add(TextNode.of(text).at(4, 2).color(color).scale(0.8f));
        }
    }

    // ---------------------------------------------------------------- the panel

    private static void openPanel(HubScreen screen, @Nullable ResourceLocation activity, boolean edit, int kind) {
        panelOpen = true;
        editing = edit;
        prefilled = false;
        awaitingLobby = false;
        panelKind = edit ? -1 : kind;
        info = null;
        if (!edit) {
            hardcore = false;
            modifiers.clear();
            visibility = 0;
            minLevel = 0;
            difficulty = "";
        }
        HubPayloads.ActivitiesData data = HubState.activitiesData;
        if (activity == null && data != null) {
            ResourceLocation filtered = activityFilter.isEmpty() ? null : ResourceLocation.tryParse(activityFilter);
            for (ActivityEntry entry : sortedEntries(data, panelKind)) {
                if (filtered != null ? entry.id().equals(filtered) : entry.unlocked()) {
                    activity = entry.id();
                    break;
                }
            }
        }
        panelActivity = activity;
        request();
        screen.onActivitiesData();
    }

    private static void pick(HubScreen screen, ResourceLocation activity) {
        if (activity.equals(panelActivity)) {
            return;
        }
        panelActivity = activity;
        info = null;
        difficulty = "";
        request();
        screen.onActivitiesData();
    }

    /** Asks the server for the picked activity's options and its drops at the picked difficulty. */
    private static void request() {
        if (panelActivity != null && ClientPlayNetworking.canSend(HubPayloads.ActivityInfoRequest.TYPE)) {
            ClientPlayNetworking.send(new HubPayloads.ActivityInfoRequest(panelActivity, difficulty, editing));
        }
    }

    private static void buildPanel(HubScreen screen, Flex content, HubPayloads.ActivitiesData data) {
        Map<String, ActivityEntry> byId = byId(data);
        ActivityEntry entry = panelActivity == null ? null : byId.get(panelActivity.toString());

        Flex head = Flex.row().gap(4).alignItems(Align.CENTER);
        head.sizing(Sizing.fill(), Sizing.content());
        Component back = tr("browser.back");
        head.item(EdenParchment.button(back, EdenParchment.fitWidth(back, 50), 14, () -> {
            panelOpen = false;
            editing = false;
            screen.onActivitiesData();
        }));
        head.item(EdenParchment.text(tr(editing ? "browser.edit" : "browser.create"), EdenParchment.INK));
        content.item(head);

        float width = HubScreen.innerWidth();
        float gap = 6;
        float left = Math.max(110, (width - 2 * gap) * 0.28f);
        float right = Math.max(150, (width - 2 * gap) * 0.36f);
        float middle = width - left - right - 2 * gap;
        Flex columns = Flex.row().gap(gap);
        columns.sizing(Sizing.fill(), Sizing.expand());
        columns.item(column("activities/panel_left", left, activityList(screen, data, left - 6)));
        columns.item(column("activities/panel_middle", middle, details(screen, data, entry, middle - 6)));
        columns.item(column("activities/panel_right", right, settings(screen, data, entry, right - 6)));
        content.item(columns);
    }

    private static ScrollPanel column(String key, float width, Flex body) {
        body.sizing(Sizing.fill(), Sizing.content());
        ScrollPanel panel = HubScroll.panel(key, width, 100, body);
        panel.sizing(Sizing.fixed(width), Sizing.fill());
        return panel;
    }

    /** Left: every activity (of the kind picked on the list), locked ones greyed with why; editing shows just its own. */
    private static Flex activityList(HubScreen screen, HubPayloads.ActivitiesData data, float width) {
        Flex list = Flex.column().gap(2);
        list.item(EdenParchment.text(tr("browser.activities"), EdenParchment.INK_MID));
        for (ActivityEntry entry : sortedEntries(data, panelKind)) {
            if (editing && !entry.id().equals(panelActivity)) {
                continue;
            }
            boolean closed = HubState.closedActivities.contains(entry.id().toString()) && !HubState.closedBypass;
            Component why = closed ? tr("closed") : !entry.unlocked() ? tr("min_level", entry.unlockLevel()) : null;
            Button button = EdenParchment.button(entry.name(), width, 14, () -> pick(screen, entry.id()));
            if (why != null) {
                button.secondary(why);
                button.enabled(false);
            }
            if (entry.id().equals(panelActivity)) {
                button.style(EdenParchment.TAB_ACTIVE);
            }
            list.item(button);
        }
        return list;
    }

    /** Middle: the art, name, level, size, possible drops, and the leaderboard. */
    private static Flex details(HubScreen screen, HubPayloads.ActivitiesData data, @Nullable ActivityEntry entry,
                                float width) {
        Flex body = Flex.column().gap(3);
        if (entry == null) {
            body.item(EdenParchment.text(tr("browser.pick"), EdenParchment.INK_DIM, (int) width));
            return body;
        }
        body.item(new Art(entry, width));
        body.item(EdenParchment.text(entry.name(), EdenParchment.INK));
        int level = entry.unlockLevel();
        if (info != null && !difficulty.isEmpty()) {
            int index = info.difficulties().indexOf(difficulty);
            if (index >= 0 && index < info.levels().size()) {
                level = info.levels().get(index);
            }
        }
        if (data.gated() && level > 0) {
            boolean meets = data.level() >= level;
            body.item(EdenParchment.text(tr("browser.required_level", level),
                    meets ? EdenParchment.GOOD : EdenParchment.DANGER));
        }
        if (info != null && info.maxMembers() > 0) {
            body.item(EdenParchment.text(tr("browser.max_members", info.maxMembers()), EdenParchment.INK_MID));
        }
        body.item(EdenParchment.text(tr("browser.drops"), EdenParchment.INK_MID));
        if (info == null) {
            body.item(EdenParchment.text(Component.translatable("gui.eden_ld.hub.home.loading"), EdenParchment.INK_DIM));
        } else if (info.drops().isEmpty()) {
            body.item(EdenParchment.text(tr("browser.no_drops"), EdenParchment.INK_DIM, (int) width));
        } else {
            int perRow = Math.max(1, (int) ((width + 2) / 18));
            Flex grid = Flex.column().gap(2);
            Flex line = null;
            for (int i = 0; i < info.drops().size(); i++) {
                if (i % perRow == 0) {
                    line = Flex.row().gap(2);
                    grid.item(line);
                }
                ItemStack stack = info.drops().get(i);
                line.item(ItemStackNode.of(stack, 0, 0, 16).decorations(false));
            }
            body.item(grid);
        }
        Component board = tr("browser.leaderboard");
        Button leaderboard = EdenParchment.button(board, EdenParchment.fitWidth(board, 70), 14, () -> {
            ActivityReturn.arm();
            ClientPlayNetworking.send(new HubPayloads.ActivityOpen(entry.id()));
        });
        leaderboard.tooltip(tr("browser.leaderboard_hint"));
        if (!entry.unlocked()) {
            leaderboard.enabled(false);
        }
        body.item(leaderboard);
        return body;
    }

    /** The card art, or the plain card look without one. */
    private static final class Art extends Widget {
        private final ActivityEntry entry;

        Art(ActivityEntry entry, float width) {
            super(width, Math.round(width * 0.45f));
            this.entry = entry;
            sizing(Sizing.fixed(width), Sizing.fixed(Math.round(width * 0.45f)));
        }

        @Override
        protected void rebuild() {
            if (!entry.cardArt().isEmpty() && ProfileBackgrounds.get(entry.cardArt()) != null) {
                add(ProfileBackgrounds.card(entry.cardArt(), width, height));
            } else {
                add(Shapes.rect(0, 0, width, height).fill(EdenParchment.ROW_BG_ALT));
            }
            add(Shapes.rect(0.5f, 0.5f, width - 1, height - 1).stroke(Stroke.of(EdenParchment.HAIRLINE, 1)));
        }
    }

    /** Right: difficulty, hardcore, modifiers, visibility, minimum level, and Create / Save. */
    private static Flex settings(HubScreen screen, HubPayloads.ActivitiesData data, @Nullable ActivityEntry entry,
                                 float width) {
        Flex body = Flex.column().gap(4);
        if (entry == null) {
            return body;
        }
        if (info == null) {
            body.item(EdenParchment.text(Component.translatable("gui.eden_ld.hub.home.loading"), EdenParchment.INK_DIM));
            return body;
        }
        if (!info.creatable()) {
            // Not a lobby activity here (a datapack one): it opens where it lives.
            body.item(EdenParchment.text(tr("browser.not_creatable"), EdenParchment.INK_DIM, (int) width));
            Component open = tr("browser.open");
            Button button = EdenParchment.button(open, EdenParchment.fitWidth(open, 70), 14, () -> {
                ActivityReturn.arm();
                ClientPlayNetworking.send(new HubPayloads.ActivityOpen(entry.id()));
            });
            button.enabled(entry.unlocked());
            body.item(button);
            return body;
        }
        if (editing && !info.hasCurrent() && !prefilled) {
            // The lobby's settings can't be read (a private lobby with an older Arenas_LD): nothing to edit safely.
            body.item(EdenParchment.text(tr("browser.edit_unknown"), EdenParchment.INK_DIM, (int) width));
            return body;
        }

        if (!info.difficulties().isEmpty()) {
            body.item(EdenParchment.text(tr("browser.difficulty"), EdenParchment.INK_MID));
            Flex pills = Flex.row().gap(2);
            for (int i = 0; i < info.difficulties().size(); i++) {
                String id = info.difficulties().get(i);
                int level = i < info.levels().size() ? info.levels().get(i) : 0;
                boolean open = !data.gated() || level <= 0 || data.level() >= level;
                Component label = difficultyName(id);
                Button pill = EdenParchment.button(label, EdenParchment.fitWidth(label, 30), 14, () -> {
                    difficulty = id;
                    request();
                    screen.onActivitiesData();
                });
                if (id.equals(difficulty)) {
                    pill.style(EdenParchment.TAB_ACTIVE);
                }
                if (level > 0) {
                    pill.tooltip(tr("browser.required_level", level));
                }
                if (!open) {
                    pill.enabled(false);
                }
                pills.item(pill);
            }
            body.item(pills);
        }

        HubPayloads.ModifierLine hardcoreLine = null;
        List<HubPayloads.ModifierLine> others = new ArrayList<>();
        for (HubPayloads.ModifierLine modifier : info.modifiers()) {
            if (HARDCORE.equals(modifier.id())) {
                hardcoreLine = modifier;
            } else {
                others.add(modifier);
            }
        }
        if (hardcoreLine != null) {
            HubPayloads.ModifierLine hardcoreInfo = hardcoreLine;
            Toggle toggle = new Toggle(width, tr("lobbies.hardcore"), hardcore, value -> {
                hardcore = value;
                if (value) {
                    // Hardcore turns off what it rules out, as the other modifiers do.
                    modifiers.removeIf(id -> hardcoreInfo.conflicts().contains(id) || info.modifiers().stream()
                            .anyMatch(m -> m.id().equals(id) && m.conflicts().contains(HARDCORE)));
                }
                screen.onActivitiesData();
            });
            toggle.secondary(hardcoreLine.rewards());
            toggle.tooltip(hardcoreLine.description());
            body.item(toggle);
        }
        if (!others.isEmpty()) {
            body.item(EdenParchment.text(tr("browser.modifiers"), EdenParchment.INK_MID));
            for (HubPayloads.ModifierLine modifier : others) {
                Toggle toggle = new Toggle(width, modifier.name(), modifiers.contains(modifier.id()), value -> {
                    if (value) {
                        modifiers.add(modifier.id());
                        // Turning one on turns off what it rules out (either way round).
                        for (HubPayloads.ModifierLine other : info.modifiers()) {
                            if (!other.id().equals(modifier.id()) && (modifier.conflicts().contains(other.id())
                                    || other.conflicts().contains(modifier.id()))) {
                                modifiers.remove(other.id());
                                if (HARDCORE.equals(other.id())) {
                                    hardcore = false;
                                }
                            }
                        }
                    } else {
                        modifiers.remove(modifier.id());
                    }
                    screen.onActivitiesData();
                });
                toggle.secondary(modifier.rewards());
                toggle.tooltip(modifier.description());
                body.item(toggle);
            }
        }

        body.item(EdenParchment.text(tr("browser.visibility"), EdenParchment.INK_MID));
        body.item(EdenParchment.tabs(List.of(0, 1, 2), value -> tr("browser.visibility." + value), visibility, value -> {
            visibility = value;
            screen.onActivitiesData();
        }, 40, "activities/panel_visibility").height(14));

        if (info.levelsActive() && data.gated()) {
            Flex levelRow = Flex.row().gap(4).alignItems(Align.CENTER);
            levelRow.item(EdenParchment.text(tr("browser.min_level_field"), EdenParchment.INK_MID));
            NumberField field = new NumberField(50, minLevel, null).range(0, 1000).step(1);
            field.onCommit(value -> minLevel = (int) value);
            field.tooltip(tr("browser.min_level_field_hint"));
            levelRow.item(field);
            body.item(levelRow);
        }

        Component action = tr(editing ? "browser.save" : "browser.create");
        Button submit = EdenParchment.button(action, Math.min(width, EdenParchment.fitWidth(action, 100)), 16, () -> {
            if (panelActivity == null) {
                return;
            }
            if (!editing) {
                awaitingLobby = true;
                ActivityReturn.arm();
            }
            List<String> chosen = new ArrayList<>(modifiers);
            ClientPlayNetworking.send(new HubPayloads.LobbyCreate(panelActivity, difficulty, hardcore, chosen,
                    visibility, minLevel, editing));
        });
        boolean closed = HubState.closedActivities.contains(entry.id().toString()) && !HubState.closedBypass;
        if (!entry.unlocked() || closed) {
            submit.enabled(false);
            submit.tooltip(closed ? tr("closed") : tr("min_level", entry.unlockLevel()));
        }
        body.item(submit);
        return body;
    }

    // ---------------------------------------------------------------- helpers

    private static Map<String, ActivityEntry> byId(HubPayloads.ActivitiesData data) {
        Map<String, ActivityEntry> byId = new HashMap<>();
        for (ActivityEntry entry : data.activities()) {
            byId.put(entry.id().toString(), entry);
        }
        return byId;
    }

    /** The activities of a kind (-1: all), open ones first, then by level and name. */
    private static List<ActivityEntry> sortedEntries(HubPayloads.ActivitiesData data, int kind) {
        List<ActivityEntry> entries = new ArrayList<>();
        for (ActivityEntry entry : data.activities()) {
            if (kind < 0 || entry.type() == kind) {
                entries.add(entry);
            }
        }
        entries.sort(Comparator.comparing((ActivityEntry e) -> !e.unlocked())
                .thenComparingInt(ActivityEntry::unlockLevel)
                .thenComparing(e -> e.name().getString().toLowerCase(Locale.ROOT)));
        return entries;
    }

    /** The first difficulty the player's level opens, else the first. */
    private static String firstOpenDifficulty(HubPayloads.ActivityInfoData data) {
        HubPayloads.ActivitiesData activities = HubState.activitiesData;
        for (int i = 0; i < data.difficulties().size(); i++) {
            int level = i < data.levels().size() ? data.levels().get(i) : 0;
            if (activities == null || !activities.gated() || level <= 0 || activities.level() >= level) {
                return data.difficulties().get(i);
            }
        }
        return data.difficulties().get(0);
    }

    static Component difficultyName(String id) {
        String key = "gui.eden_ld.moderation.activities.difficulty." + id;
        return I18n.exists(key) ? Component.translatable(key) : Component.literal(id);
    }

    private static int difficultyColor(String id) {
        return switch (id) {
            case "easy" -> EdenParchment.GOOD;
            case "normal" -> EdenParchment.ACCENT;
            case "hard" -> EdenParchment.WARN;
            case "nightmare" -> EdenParchment.DANGER;
            default -> EdenParchment.INK_MID;
        };
    }
}
