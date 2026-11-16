package net.ledok.eden_ld.client.hub;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.ledok.eden_ld.client.EdenParchment;
import net.ledok.eden_ld.client.profile.ProfileBackgrounds;
import net.ledok.eden_ld.hub.HubPayloads;
import net.ledok.eden_ld.hub.HubPayloads.ActivityEntry;
import net.ledok.vectorlib.client.canvas.CanvasNode;
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
import net.ledok.vectorlib.client.canvas.widget.UiSounds;
import net.ledok.vectorlib.client.canvas.widget.Widget;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
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
 * The Activities tab as a lobby browser (1.3.103; laid out as the Arenas UI rework concept's section 4 in 1.3.105, after
 * LeDok's TEST look): every lobby forming now that the player may see, with Arenas_LD 4.33.0+.
 * <ul>
 *   <li>The page's title row carries «Моє лобі» / «Змінити лобі» and «+ Створити лобі» (top right).</li>
 *   <li>Under the kind tabs, one row of filters: activity, difficulty, hardcore, "only joinable", and the sort.</li>
 *   <li>One 28-unit row per lobby: the activity's picture, «name · kind · owner» over pills (difficulty, hardcore,
 *   modifiers, minimum level, friends-only), members, and Приєднатися / Попроситися / Відкрити (greyed with why).</li>
 * </ul>
 * «Створити лобі» opens the panel in place of the list: activities | picture, level, size, possible drops, leaderboard
 * | difficulty and run modifiers (only this part scrolls), and a bar fixed at the bottom with visibility, minimum level
 * and the primary button, so nothing has to be scrolled to. The same panel edits the owner's lobby. Every long name or
 * effect is cut with "…" and given in full in its tooltip; nothing is wider than the smallest hub (416 units inside).
 */
final class LobbyBrowser {

    enum Sort { NEWEST, PLAYERS, LEVEL }

    private static final List<String> DIFFICULTIES = List.of("easy", "normal", "hard", "nightmare");
    /** Hardcore filter: any, only hardcore, only without. */
    private static final List<Integer> HARDCORE_FILTERS = List.of(0, 1, 2);
    private static final String HARDCORE = "hardcore_run";
    private static final float ROW_H = 28;
    private static final float THUMB = 18;
    private static final float MOD_H = 16;
    /** A scroll column's lane and padding: what its content can't use. */
    private static final float COLUMN_INSET = 10;

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

    /** The create panel shows instead of the list (the tab then hides its kind filters). */
    static boolean panelOpen() {
        return panelOpen;
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
        screen.titleActions(titleActions(screen, data, kind));
        Map<String, ActivityEntry> byId = byId(data);
        float width = HubScreen.innerWidth();
        content.item(filters(screen, data, byId, kind, width));

        List<Row> rows = rows(data, byId, kind);
        if (rows.isEmpty()) {
            Flex empty = HubScreen.emptyState(HubTab.ACTIVITIES, tr("lobbies.none_title"), tr("browser.none"));
            empty.id(HubSpotlights.ACTIVITIES_CARDS);
            empty.item(createButton(screen, kind));
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

    /** The title row's right end: the own lobby (open, or edit for its owner) and «+ Створити лобі». */
    private static CanvasNode[] titleActions(HubScreen screen, HubPayloads.ActivitiesData data, int kind) {
        List<CanvasNode> actions = new ArrayList<>();
        if (data.gated()) {
            var level = EdenParchment.text(tr("your_level", data.level()), EdenParchment.INK_DIM);
            level.id(HubSpotlights.ACTIVITIES_LEVEL);
            actions.add(level);
        }
        HubPayloads.OwnLobbyData lobby = OwnLobbyButton.data();
        if (lobby.present()) {
            Component label = tr("browser.my_lobby");
            Button mine = EdenParchment.button(label, EdenParchment.fitWidth(label, 50), 14,
                    () -> ClientPlayNetworking.send(HubPayloads.OwnLobbyOpen.INSTANCE));
            mine.tooltip(lobby.inRun() ? tr("own_lobby.in_run", lobby.name())
                    : tr("own_lobby", lobby.name(), lobby.members(), lobby.maxMembers()));
            actions.add(mine);
            if (lobby.owner() && !lobby.inRun() && !lobby.activity().isEmpty()) {
                Component edit = tr("browser.edit");
                Button button = EdenParchment.button(edit, EdenParchment.fitWidth(edit, 50), 14,
                        () -> openPanel(screen, ResourceLocation.tryParse(lobby.activity()), true, kind));
                button.tooltip(tr("browser.edit_hint"));
                actions.add(button);
            }
        }
        actions.add(createButton(screen, kind));
        return actions.toArray(CanvasNode[]::new);
    }

    private static Button createButton(HubScreen screen, int kind) {
        Component label = tr("browser.create_plus");
        Button create = EdenParchment.button(label, EdenParchment.fitWidth(label, 70), 14,
                () -> openPanel(screen, null, false, kind));
        create.style(EdenParchment.TAB_ACTIVE);
        create.tooltip(tr("browser.create_hint"));
        return create;
    }

    /** One row under the kind tabs: activity, difficulty, hardcore, joinable, and the sort; sized to fit 416 units. */
    private static Flex filters(HubScreen screen, HubPayloads.ActivitiesData data, Map<String, ActivityEntry> byId,
                                int kind, float width) {
        Flex filters = Flex.row().gap(3).alignItems(Align.CENTER);
        filters.sizing(Sizing.fill(), Sizing.content());
        filters.id(HubSpotlights.ACTIVITIES_SORT);
        Component joinableLabel = tr("browser.joinable");
        float toggleWidth = Minecraft.getInstance().font.width(joinableLabel) + 16;
        float sortWidth = clamp(width * 0.15f, 62, 90);
        // What's left goes to the three dropdowns, the activity one a bit wider.
        float left = width - toggleWidth - sortWidth - 3 * 5;
        float activityWidth = clamp(left * 0.38f, 80, 150);
        float dropdownWidth = clamp((left - activityWidth) / 2, 66, 110);

        List<String> activityOptions = new ArrayList<>();
        activityOptions.add("");
        for (ActivityEntry entry : sortedEntries(data, kind)) {
            activityOptions.add(entry.id().toString());
        }
        if (!activityOptions.contains(activityFilter)) {
            activityFilter = "";
        }
        filters.item(dropdown(new Dropdown<>(activityWidth, activityOptions, activityFilter,
                id -> id.isEmpty() ? tr("browser.any_activity") : byId.containsKey(id) ? byId.get(id).name()
                        : Component.literal(id), id -> {
                    activityFilter = id;
                    screen.onActivitiesData();
                }), activityWidth));
        List<String> difficultyOptions = new ArrayList<>();
        difficultyOptions.add("");
        difficultyOptions.addAll(DIFFICULTIES);
        filters.item(dropdown(new Dropdown<>(dropdownWidth, difficultyOptions, difficultyFilter,
                id -> id.isEmpty() ? tr("browser.any_difficulty") : difficultyName(id), id -> {
                    difficultyFilter = id;
                    screen.onActivitiesData();
                }), dropdownWidth));
        filters.item(dropdown(new Dropdown<>(dropdownWidth, HARDCORE_FILTERS, hardcoreFilter,
                value -> tr("browser.hardcore." + value), value -> {
                    hardcoreFilter = value;
                    screen.onActivitiesData();
                }), dropdownWidth));
        Toggle joinable = new Toggle(toggleWidth, joinableLabel, joinableOnly, value -> {
            joinableOnly = value;
            screen.onActivitiesData();
        });
        joinable.tooltip(tr("browser.joinable_hint"));
        filters.item(joinable);
        filters.spacer();
        Dropdown<Sort> sorting = new Dropdown<>(sortWidth, List.of(Sort.values()), sort,
                candidate -> tr("browser.sort." + candidate.name().toLowerCase(Locale.ROOT)), candidate -> {
                    sort = candidate;
                    screen.onActivitiesData();
                });
        sorting.tooltip(tr("sort"));
        filters.item(dropdown(sorting, sortWidth));
        return filters;
    }

    /** Dropdowns at the filter row's height. */
    private static <T> Dropdown<T> dropdown(Dropdown<T> dropdown, float width) {
        dropdown.sizing(Sizing.fixed(width), Sizing.fixed(16));
        return dropdown;
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
     * [picture] «Візер · рейд · LeDok» over [Важко] [Хардкор] [+2] «Рів. 25+ · для друзів», then «3/5» and the button.
     */
    private static Flex row(Row row, HubPayloads.ActivitiesData data, boolean alt, float width) {
        HubPayloads.LobbyLine lobby = row.lobby();
        ActivityEntry entry = row.entry();
        Flex box = Flex.row().gap(4).padding(Insets.of(2, 4, 2, 4)).alignItems(Align.CENTER);
        box.sizing(Sizing.fixed(width), Sizing.fixed(ROW_H));
        box.background(EdenParchment.framed(alt ? EdenParchment.ROW_BG_ALT : EdenParchment.ROW_BG,
                lobby.member() ? EdenParchment.ACCENT : EdenParchment.HAIRLINE));

        Component label = tr(lobby.member() ? "lobbies.open" : lobby.request() ? "lobbies.ask" : "lobbies.join");
        float buttonWidth = EdenParchment.fitWidth(label, 56);
        Component members = Component.literal(lobby.members() + "/" + lobby.maxMembers());
        float membersWidth = Minecraft.getInstance().font.width(members) + 2;
        int textWidth = (int) (width - 8 - THUMB - buttonWidth - membersWidth - 4 * 3);

        box.item(new Thumb(entry, row.activity()));
        Flex lines = Flex.column().gap(2);
        lines.sizing(Sizing.fixed(textWidth), Sizing.content());
        Component name = entry != null ? entry.name() : Component.literal(row.activity());
        MutableComponent first = Component.empty().append(name.copy().withStyle(ChatFormatting.BOLD));
        if (entry != null) {
            first.append(Component.literal(" · ").append(tr("browser.type." + entry.type())).withColor(EdenParchment.INK_DIM & 0xFFFFFF));
        }
        first.append(Component.literal(" · " + lobby.owner()).withColor(EdenParchment.INK_DIM & 0xFFFFFF));
        lines.item(EdenParchment.text(first, EdenParchment.INK).maxWidth(textWidth).ellipsis(true));

        Flex pills = Flex.row().gap(2).alignItems(Align.CENTER);
        if (!lobby.difficulty().isEmpty()) {
            pills.item(Pill.filled(difficultyName(lobby.difficulty()), difficultyColor(lobby.difficulty())));
        }
        if (lobby.hardcore()) {
            pills.item(Pill.filled(tr("lobbies.hardcore"), EdenParchment.DANGER));
        }
        if (!lobby.modifiers().isEmpty()) {
            Pill more = Pill.outline(Component.literal("+" + lobby.modifiers().size()), EdenParchment.INK_MID);
            List<Component> names = new ArrayList<>();
            names.add(tr("browser.modifiers"));
            names.addAll(lobby.modifiers());
            more.tooltip(names.toArray(Component[]::new));
            pills.item(more);
        }
        MutableComponent rest = Component.empty();
        if (lobby.minLevel() > 0) {
            boolean low = data.gated() && data.level() < lobby.minLevel();
            rest.append(tr("browser.min_level", lobby.minLevel()).copy()
                    .withColor((low ? EdenParchment.DANGER : EdenParchment.INK_DIM) & 0xFFFFFF));
        }
        if (lobby.request()) {
            if (!rest.getSiblings().isEmpty()) {
                rest.append(" · ");
            }
            rest.append(tr("browser.friends_only"));
        }
        if (lobby.member()) {
            if (!rest.getSiblings().isEmpty()) {
                rest.append(" · ");
            }
            rest.append(tr("lobbies.yours").copy().withColor(EdenParchment.GOOD & 0xFFFFFF));
        }
        if (!rest.getSiblings().isEmpty()) {
            pills.item(EdenParchment.text(rest, EdenParchment.INK_DIM).maxWidth(Math.max(20, textWidth - 90)).ellipsis(true));
        }
        lines.item(pills);
        box.item(lines);
        box.spacer();

        var count = EdenParchment.text(members, lobby.full() ? EdenParchment.INK_DIM : EdenParchment.INK);
        count.tooltip(tr("browser.members", lobby.members(), lobby.maxMembers(), lobby.ready()));
        box.item(count);

        Component why = blocked(data, entry, row.activity(), lobby);
        ResourceLocation activity = ResourceLocation.tryParse(row.activity());
        Button button = EdenParchment.button(label, buttonWidth, 16, () -> {
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

    /** The activity's picture, small: its card art, else its kind's color with the name's first letter. */
    private static final class Thumb extends Widget {
        private final @Nullable ActivityEntry entry;
        private final String id;

        Thumb(@Nullable ActivityEntry entry, String id) {
            super(THUMB, THUMB);
            this.entry = entry;
            this.id = id;
            sizing(Sizing.fixed(THUMB), Sizing.fixed(THUMB));
        }

        @Override
        protected void rebuild() {
            if (entry != null && !entry.cardArt().isEmpty() && ProfileBackgrounds.get(entry.cardArt()) != null) {
                add(ProfileBackgrounds.card(entry.cardArt(), width, height));
            } else {
                add(Shapes.rect(0, 0, width, height).fill(typeColor(entry == null ? -1 : entry.type())));
                String name = entry != null ? entry.name().getString() : id;
                String letter = name.isEmpty() ? "?" : name.substring(0, 1).toUpperCase(Locale.ROOT);
                add(TextNode.of(Component.literal(letter)).at(width / 2, 5).align(TextNode.Align.CENTER)
                        .color(0xFFF6EAD0).shadow(true)).pickable(false);
            }
            add(Shapes.rect(0.5f, 0.5f, width - 1, height - 1).stroke(Stroke.of(EdenParchment.HAIRLINE, 1)));
        }
    }

    /** A small rounded label: filled (difficulty, hardcore) or outlined (counts). */
    private static final class Pill extends Widget {
        private final Component text;
        private final int color;
        private final boolean filled;

        private Pill(Component text, int color, boolean filled) {
            super(Minecraft.getInstance().font.width(text) * 0.8f + 8, 10);
            this.text = text;
            this.color = color;
            this.filled = filled;
            sizing(Sizing.fixed(width), Sizing.fixed(10));
        }

        static Pill filled(Component text, int color) {
            return new Pill(text, color, true);
        }

        static Pill outline(Component text, int color) {
            return new Pill(text, color, false);
        }

        @Override
        protected void rebuild() {
            if (filled) {
                add(Shapes.rect(0, 0, width, height).fill(color | 0xFF000000));
            }
            add(Shapes.rect(0.5f, 0.5f, width - 1, height - 1).stroke(Stroke.of(filled ? EdenParchment.HAIRLINE_HI
                    : color, 1)));
            add(TextNode.of(text).at(4, 2).color(filled ? 0xFFF6EAD0 : color).scale(0.8f)).pickable(false);
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

    private static void closePanel(HubScreen screen) {
        panelOpen = false;
        editing = false;
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

    /** Activities | details | settings, and the action bar fixed under them. */
    private static void buildPanel(HubScreen screen, Flex content, HubPayloads.ActivitiesData data) {
        Component back = tr("browser.back");
        Button close = EdenParchment.button(back, EdenParchment.fitWidth(back, 50), 14, () -> closePanel(screen));
        close.tooltip(tr("browser.back_hint"));
        screen.titleActions(EdenParchment.text(tr(editing ? "browser.edit" : "browser.create"), EdenParchment.INK),
                close);
        Map<String, ActivityEntry> byId = byId(data);
        ActivityEntry entry = panelActivity == null ? null : byId.get(panelActivity.toString());

        float width = HubScreen.innerWidth();
        float gap = 5;
        float left = clamp(width * 0.24f, 96, 150);
        float middle = clamp(width * 0.30f, 116, 190);
        float right = width - left - middle - 2 * gap;
        Flex columns = Flex.row().gap(gap);
        columns.sizing(Sizing.fill(), Sizing.expand());
        columns.item(column("activities/panel_left", left, activityList(screen, data, left - COLUMN_INSET)));
        columns.item(column("activities/panel_middle", middle, details(data, entry, middle - COLUMN_INSET)));
        columns.item(column("activities/panel_right", right, settings(screen, data, right - COLUMN_INSET)));
        content.item(columns);
        content.item(actionBar(screen, data, entry, width));
    }

    private static ScrollPanel column(String key, float width, Flex body) {
        body.sizing(Sizing.fill(), Sizing.content());
        // Room on every side, so frames and checkboxes at the edges aren't clipped.
        body.padding(Insets.of(2, 2, 2, 2));
        ScrollPanel panel = HubScroll.panel(key, width, 100, body);
        panel.sizing(Sizing.fixed(width), Sizing.fill());
        return panel;
    }

    /** «25+» for a level a player needs; null when nothing is locked by level or it needs none. */
    @Nullable
    private static Component levelBadge(HubPayloads.ActivitiesData data, int level) {
        return data.gated() && level > 0 ? tr("browser.level_badge", level) : null;
    }

    /** One line of text, cut with "…" to {@code width}, the whole text in its tooltip. */
    private static CanvasNode line(Component text, int color, float width) {
        var node = EdenParchment.text(text, color).maxWidth((int) width).ellipsis(true);
        node.tooltip(text);
        return node;
    }

    /** A small dim caps heading, as in the concept ("DIFFICULTY", "POSSIBLE DROPS"). */
    private static CanvasNode section(Component label) {
        // Full size: a scaled label was laid out at its unscaled size and drawn half outside its row (1.3.108).
        return EdenParchment.text(Component.literal(label.getString().toUpperCase(Locale.ROOT)), EdenParchment.INK_DIM);
    }

    /** Left: every activity (of the kind picked on the list), locked ones greyed with why; editing shows just its own. */
    private static Flex activityList(HubScreen screen, HubPayloads.ActivitiesData data, float width) {
        Flex list = Flex.column().gap(2);
        list.item(section(tr("browser.activities")));
        for (ActivityEntry entry : sortedEntries(data, panelKind)) {
            if (editing && !entry.id().equals(panelActivity)) {
                continue;
            }
            boolean closed = HubState.closedActivities.contains(entry.id().toString()) && !HubState.closedBypass;
            Component why = closed ? tr("closed") : !entry.unlocked() ? tr("min_level", entry.unlockLevel()) : null;
            Button button = EdenParchment.button(entry.name(), width, 16, () -> pick(screen, entry.id()));
            button.align(TextNode.Align.LEFT);
            button.tooltipWhenCut(true);
            // The level it opens at on the right («25+»), red while the player is below it; the name is cut first.
            Component badge = levelBadge(data, entry.unlockLevel());
            if (badge != null) {
                button.secondary(badge);
                button.secondaryColor(data.level() >= entry.unlockLevel() ? EdenParchment.GOOD : EdenParchment.DANGER);
                button.cutSecondaryFirst(false);
            }
            List<Component> tip = new ArrayList<>();
            tip.add(entry.name());
            tip.add(tr("browser.type." + entry.type()));
            if (why != null) {
                tip.add(why.copy().withStyle(ChatFormatting.RED));
                button.enabled(false);
            }
            button.tooltip(tip.toArray(Component[]::new));
            if (entry.id().equals(panelActivity)) {
                button.style(EdenParchment.TAB_ACTIVE);
            }
            list.item(button);
        }
        return list;
    }

    /** Middle: the picture, name, kind and size, the level, possible drops, and the leaderboard. */
    private static Flex details(HubPayloads.ActivitiesData data, @Nullable ActivityEntry entry, float width) {
        Flex body = Flex.column().gap(3);
        if (entry == null) {
            body.item(EdenParchment.text(tr("browser.pick"), EdenParchment.INK_DIM, (int) width));
            return body;
        }
        body.item(new Art(entry, width));
        // One line each, cut with "…" (full in the tooltip): wrapped lines here were measured short and the next
        // line drew over them (1.3.106).
        body.item(line(entry.name(), EdenParchment.INK, width));
        body.item(line(tr("browser.type." + entry.type()), EdenParchment.INK_MID, width));
        if (info != null && info.maxMembers() > 0) {
            body.item(line(tr("browser.max_members", info.maxMembers()), EdenParchment.INK_MID, width));
        }
        int level = entry.unlockLevel();
        if (info != null && !difficulty.isEmpty()) {
            int index = info.difficulties().indexOf(difficulty);
            if (index >= 0 && index < info.levels().size()) {
                level = info.levels().get(index);
            }
        }
        if (data.gated()) {
            boolean meets = level <= 0 || data.level() >= level;
            body.item(line(level > 0 ? tr("browser.required_level_yours", level, data.level())
                    : tr("browser.level_none", data.level()), meets ? EdenParchment.GOOD : EdenParchment.DANGER, width));
            // Every difficulty's level, ✓ or ✗ against the player's (LeDok: the drops' space goes to this, 1.3.109).
            if (info != null && !info.difficulties().isEmpty()) {
                body.item(section(tr("browser.levels")));
                for (int i = 0; i < info.difficulties().size(); i++) {
                    int needed = i < info.levels().size() ? info.levels().get(i) : 0;
                    boolean has = needed <= 0 || data.level() >= needed;
                    Component tier = difficultyName(info.difficulties().get(i));
                    body.item(line(tr(has ? "browser.level_row_ok" : "browser.level_row_no", tier,
                                    needed > 0 ? tr("browser.level_badge", needed) : tr("browser.level_free")),
                            has ? EdenParchment.GOOD : EdenParchment.DANGER, width));
                }
            }
        }
        // Possible drops are off unless the server turns them on (activities.show_possible_drops; LeDok 2026-11-16:
        // hidden until Arenas reads the gear tables). Off, the server sends a total of -1 and nothing shows.
        if (info != null && info.dropsTotal() >= 0) {
            drops(body, width);
        }
        Component board = tr("browser.leaderboard");
        Button leaderboard = EdenParchment.button(board, Math.min(width, EdenParchment.fitWidth(board, 70)), 14, () -> {
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

    /** The possible drops: two rows of icons, then «+N» naming the rest (LeDok, 1.3.107). */
    private static void drops(Flex body, float width) {
        // The server falls back to every difficulty's drops when the picked one has none listed (1.3.108).
        boolean allTiers = info.dropsFor().isEmpty() && !difficulty.isEmpty() && !info.drops().isEmpty();
        body.item(section(tr(allTiers ? "browser.drops_all" : "browser.drops")));
        if (info.drops().isEmpty()) {
            body.item(EdenParchment.text(tr("browser.no_drops"), EdenParchment.INK_DIM, (int) width));
            return;
        }
        int perRow = Math.max(1, (int) ((width + 2) / 18));
        int capacity = perRow * DROP_ROWS;
        int total = Math.max(info.dropsTotal(), info.drops().size());
        int shown = Math.min(info.drops().size(), total > capacity ? capacity - 1 : total);
        Flex grid = Flex.column().gap(2);
        Flex row = null;
        for (int i = 0; i < shown; i++) {
            if (i % perRow == 0) {
                row = Flex.row().gap(2);
                grid.item(row);
            }
            ItemStack stack = info.drops().get(i);
            row.item(ItemStackNode.of(stack, 0, 0, 16).decorations(false));
        }
        if (total > shown) {
            if (row == null || shown % perRow == 0) {
                row = Flex.row().gap(2);
                grid.item(row);
            }
            row.item(new More(total - shown, info.drops().subList(shown, info.drops().size()), total));
        }
        body.item(grid);
    }

    /** Rows of drop icons before «+N». */
    private static final int DROP_ROWS = 2;
    /** Names listed in «+N»'s tooltip before "…and N more". */
    private static final int MORE_NAMES = 15;

    /** The «+N» cell after the drop icons: its tooltip names the rest (those the server sent; the count is the total). */
    private static final class More extends Widget {
        private final int count;

        More(int count, List<ItemStack> rest, int total) {
            super(16, 16);
            this.count = count;
            sizing(Sizing.fixed(16), Sizing.fixed(16));
            tooltip(() -> {
                List<Component> lines = new ArrayList<>();
                lines.add(tr("browser.drops_more", count, total).copy().withStyle(ChatFormatting.BOLD));
                int listed = Math.min(MORE_NAMES, rest.size());
                for (int i = 0; i < listed; i++) {
                    lines.add(rest.get(i).getHoverName().copy().withStyle(ChatFormatting.GRAY));
                }
                if (count > listed) {
                    lines.add(tr("lobbies.more", count - listed).copy().withStyle(ChatFormatting.DARK_GRAY));
                }
                return lines;
            });
        }

        @Override
        protected void rebuild() {
            add(Shapes.rect(0, 0, width, height).fill(EdenParchment.ROW_BG_ALT));
            add(Shapes.rect(0.5f, 0.5f, width - 1, height - 1).stroke(Stroke.of(EdenParchment.HAIRLINE, 1)));
            Component text = Component.literal("+" + count);
            float scale = Minecraft.getInstance().font.width(text) > 14 ? 0.7f : 0.9f;
            add(TextNode.of(text).at(width / 2, 4).align(TextNode.Align.CENTER).scale(scale)
                    .color(EdenParchment.INK)).pickable(false);
        }
    }

    /** The card art; without one, the kind's color with the name over it. */
    private static final class Art extends Widget {
        private final ActivityEntry entry;

        Art(ActivityEntry entry, float width) {
            super(width, Math.round(width * 0.4f));
            this.entry = entry;
            sizing(Sizing.fixed(width), Sizing.fixed(Math.round(width * 0.4f)));
        }

        @Override
        protected void rebuild() {
            if (!entry.cardArt().isEmpty() && ProfileBackgrounds.get(entry.cardArt()) != null) {
                add(ProfileBackgrounds.card(entry.cardArt(), width, height));
            } else {
                int color = typeColor(entry.type());
                add(Shapes.rect(0, 0, width, height).fill(color));
                add(Shapes.rect(0, height * 0.55f, width, height * 0.45f).fill(0x33000000));
                add(TextNode.of(entry.name()).at(width / 2, height / 2 - 4).align(TextNode.Align.CENTER)
                        .maxWidth((int) width - 8).ellipsis(true).color(0xFFF6EAD0).shadow(true)).pickable(false);
            }
            add(Shapes.rect(0.5f, 0.5f, width - 1, height - 1).stroke(Stroke.of(EdenParchment.HAIRLINE, 1)));
        }
    }

    /** Right: difficulty and the run modifiers (the part that scrolls). */
    private static Flex settings(HubScreen screen, HubPayloads.ActivitiesData data, float width) {
        Flex body = Flex.column().gap(3);
        if (panelActivity == null) {
            return body;
        }
        if (info == null) {
            body.item(EdenParchment.text(Component.translatable("gui.eden_ld.hub.home.loading"), EdenParchment.INK_DIM));
            return body;
        }
        if (!info.creatable()) {
            // Not a lobby activity here (a datapack one): it opens where it lives (the bar's button).
            body.item(EdenParchment.text(tr("browser.not_creatable"), EdenParchment.INK_DIM, (int) width));
            return body;
        }
        if (editing && !info.hasCurrent() && !prefilled) {
            // The lobby's settings can't be read (a private lobby with an older Arenas_LD): nothing to edit safely.
            body.item(EdenParchment.text(tr("browser.edit_unknown"), EdenParchment.INK_DIM, (int) width));
            return body;
        }

        if (!info.difficulties().isEmpty()) {
            body.item(section(tr("browser.difficulty")));
            // One row when every name fits its share; else two per row (1.3.106: «Nightmare» ran over its button).
            int count = info.difficulties().size();
            var font = Minecraft.getInstance().font;
            float needed = 0;
            for (int i = 0; i < count; i++) {
                int level = i < info.levels().size() ? info.levels().get(i) : 0;
                Component badge = levelBadge(data, level);
                needed += font.width(difficultyName(info.difficulties().get(i))) + 12
                        + (badge == null ? 0 : font.width(badge) + 6);
            }
            int perRow = needed + 2 * (count - 1) <= width ? count : 2;
            float each = (width - 2 * (perRow - 1)) / perRow;
            Flex grid = Flex.column().gap(2);
            Flex seg = null;
            for (int i = 0; i < count; i++) {
                if (i % perRow == 0) {
                    seg = Flex.row().gap(2);
                    grid.item(seg);
                }
                String id = info.difficulties().get(i);
                int level = i < info.levels().size() ? info.levels().get(i) : 0;
                boolean open = !data.gated() || level <= 0 || data.level() >= level;
                Button pill = EdenParchment.button(difficultyName(id), each, 14, () -> {
                    difficulty = id;
                    request();
                    screen.onActivitiesData();
                });
                pill.tooltipWhenCut(true);
                Component badge = levelBadge(data, level);
                if (badge != null) {
                    pill.secondary(badge);
                    pill.secondaryColor(open ? EdenParchment.GOOD : EdenParchment.DANGER);
                    pill.cutSecondaryFirst(false);
                }
                List<Component> tip = new ArrayList<>();
                tip.add(difficultyName(id));
                if (level > 0) {
                    tip.add(tr("browser.required_level", level).copy()
                            .withStyle(open ? ChatFormatting.GREEN : ChatFormatting.RED));
                }
                pill.tooltip(tip.toArray(Component[]::new));
                if (id.equals(difficulty)) {
                    pill.style(EdenParchment.TAB_ACTIVE);
                }
                if (!open) {
                    pill.enabled(false);
                }
                seg.item(pill);
            }
            body.item(grid);
        }

        boolean any = false;
        for (HubPayloads.ModifierLine modifier : info.modifiers()) {
            if (!any) {
                body.item(section(tr("browser.modifiers")));
                any = true;
            }
            boolean isHardcore = HARDCORE.equals(modifier.id());
            boolean on = isHardcore ? hardcore : modifiers.contains(modifier.id());
            body.item(new ModifierRow(modifier, isHardcore, on, width, value -> {
                toggle(modifier, isHardcore, value);
                screen.onActivitiesData();
            }));
        }
        return body;
    }

    /** A modifier on or off; on turns off what it rules out (either way round), hardcore included. */
    private static void toggle(HubPayloads.ModifierLine modifier, boolean isHardcore, boolean value) {
        if (isHardcore) {
            hardcore = value;
        } else if (value) {
            modifiers.add(modifier.id());
        } else {
            modifiers.remove(modifier.id());
        }
        if (!value || info == null) {
            return;
        }
        for (HubPayloads.ModifierLine other : info.modifiers()) {
            if (!other.id().equals(modifier.id()) && (modifier.conflicts().contains(other.id())
                    || other.conflicts().contains(modifier.id()))) {
                if (HARDCORE.equals(other.id())) {
                    hardcore = false;
                } else {
                    modifiers.remove(other.id());
                }
            }
        }
    }

    /**
     * A 16-unit modifier row (the concept's): a checkbox, the name, the reward effect on the right in green. Both are cut
     * with "…" when long; the tooltip has the full name, effect, description and what it rules out.
     */
    private static final class ModifierRow extends Widget {
        private final HubPayloads.ModifierLine modifier;
        private final boolean hardcoreRow;
        private final boolean on;
        private final java.util.function.Consumer<Boolean> onChange;

        ModifierRow(HubPayloads.ModifierLine modifier, boolean hardcoreRow, boolean on, float width,
                    java.util.function.Consumer<Boolean> onChange) {
            super(width, MOD_H);
            this.modifier = modifier;
            this.hardcoreRow = hardcoreRow;
            this.on = on;
            this.onChange = onChange;
            sizing(Sizing.fixed(width), Sizing.fixed(MOD_H));
            tooltip(() -> {
                List<Component> lines = new ArrayList<>();
                lines.add(modifier.name().copy().withStyle(ChatFormatting.BOLD));
                if (!modifier.rewards().getString().isEmpty()) {
                    lines.add(modifier.rewards().copy().withStyle(ChatFormatting.GREEN));
                }
                if (!modifier.description().getString().isEmpty()) {
                    lines.add(modifier.description().copy().withStyle(ChatFormatting.GRAY));
                }
                if (!modifier.conflicts().isEmpty() && info != null) {
                    List<String> names = new ArrayList<>();
                    for (HubPayloads.ModifierLine other : info.modifiers()) {
                        if (modifier.conflicts().contains(other.id()) || other.conflicts().contains(modifier.id())) {
                            names.add(other.name().getString());
                        }
                    }
                    if (!names.isEmpty()) {
                        lines.add(tr("browser.conflicts", String.join(", ", names)).copy()
                                .withStyle(ChatFormatting.DARK_GRAY));
                    }
                }
                return lines;
            });
        }

        @Override
        protected void rebuild() {
            int accent = hardcoreRow ? EdenParchment.DANGER : EdenParchment.ACCENT;
            add(Shapes.rect(0, 0, width, height).fill(on ? (0x55000000 | (accent & 0xFFFFFF))
                    : hovered ? 0x22000000 | (EdenParchment.INK & 0xFFFFFF) : EdenParchment.ROW_BG_ALT));
            if (on) {
                add(Shapes.rect(0, 0, 2, height).fill(accent | 0xFF000000));
            }
            // The checkbox.
            float box = 9;
            float bx = 4;
            float by = (height - box) / 2;
            add(Shapes.rect(bx, by, box, box).fill(EdenParchment.PANEL));
            add(Shapes.rect(bx + 0.5f, by + 0.5f, box - 1, box - 1).stroke(Stroke.of(EdenParchment.HAIRLINE_HI, 1)));
            if (on) {
                add(Shapes.rect(bx + 2, by + 2, box - 4, box - 4).fill(accent | 0xFF000000));
            }
            var font = Minecraft.getInstance().font;
            float textX = bx + box + 4;
            float available = width - textX - 4;
            // The effect keeps up to 45% of the room; the name takes the rest.
            float effectWidth = Math.min(font.width(modifier.rewards()), available * 0.45f);
            float nameWidth = Math.max(10, available - effectWidth - (effectWidth > 0 ? 6 : 0));
            add(TextNode.of(modifier.name()).at(textX, textY(height)).maxWidth((int) nameWidth).ellipsis(true)
                    .color(EdenParchment.INK)).pickable(false);
            if (effectWidth > 0) {
                add(TextNode.of(modifier.rewards()).at(width - 4, textY(height)).align(TextNode.Align.RIGHT)
                        .maxWidth((int) effectWidth).ellipsis(true).color(EdenParchment.GOOD)).pickable(false);
            }
        }

        @Override
        public boolean onMouseDown(float x, float y, int button) {
            if (button != 0) {
                return false;
            }
            UiSounds.click();
            onChange.accept(!on);
            return true;
        }

        @Override
        public Cursor cursor() {
            return Cursor.HAND;
        }
    }

    /**
     * The bar fixed under the columns: visibility, minimum level, and the primary button (Створити лобі / Зберегти
     * зміни, or Відкрити for an activity the hub can't make lobbies of).
     */
    private static Flex actionBar(HubScreen screen, HubPayloads.ActivitiesData data, @Nullable ActivityEntry entry,
                                  float width) {
        Flex bar = Flex.row().gap(4).padding(Insets.of(3, 4, 3, 4)).alignItems(Align.CENTER);
        bar.sizing(Sizing.fill(), Sizing.content());
        bar.background(EdenParchment.framed(EdenParchment.ROW_BG, EdenParchment.HAIRLINE));
        if (entry == null || info == null) {
            bar.item(EdenParchment.text(Component.literal(" "), EdenParchment.INK_DIM));
            return bar;
        }
        boolean closed = HubState.closedActivities.contains(entry.id().toString()) && !HubState.closedBypass;
        if (!info.creatable()) {
            bar.spacer();
            Component open = tr("browser.open");
            Button button = EdenParchment.button(open, EdenParchment.fitWidth(open, 80), 16, () -> {
                ActivityReturn.arm();
                ClientPlayNetworking.send(new HubPayloads.ActivityOpen(entry.id()));
            });
            button.style(EdenParchment.TAB_ACTIVE);
            button.enabled(entry.unlocked() && !closed);
            bar.item(button);
            return bar;
        }
        boolean editable = !editing || info.hasCurrent() || prefilled;
        if (editable) {
            var tabs = EdenParchment.tabs(List.of(0, 1, 2), value -> tr("browser.visibility." + value), visibility,
                    value -> {
                        visibility = value;
                        screen.onActivitiesData();
                    }, 36, "activities/panel_visibility").height(14);
            tabs.tabTooltip(0, tr("browser.visibility_hint.0"));
            tabs.tabTooltip(1, tr("browser.visibility_hint.1"));
            tabs.tabTooltip(2, tr("browser.visibility_hint.2"));
            bar.item(tabs);
            if (info.levelsActive() && data.gated()) {
                bar.item(EdenParchment.text(tr("browser.min_level_field"), EdenParchment.INK_MID));
                NumberField field = new NumberField(34, minLevel, value -> minLevel = (int) value).range(0, 1000).step(1);
                field.onCommit(value -> minLevel = (int) value);
                field.sizing(Sizing.fixed(34), Sizing.fixed(14));
                field.tooltip(tr("browser.min_level_field_hint"));
                bar.item(field);
            }
        }
        bar.spacer();
        Component action = tr(editing ? "browser.save" : "browser.create");
        Button submit = EdenParchment.button(action, EdenParchment.fitWidth(action, 80), 16, () -> {
            if (panelActivity == null) {
                return;
            }
            if (!editing) {
                awaitingLobby = true;
                ActivityReturn.arm();
            }
            ClientPlayNetworking.send(new HubPayloads.LobbyCreate(panelActivity, difficulty, hardcore,
                    new ArrayList<>(modifiers), visibility, minLevel, editing));
        });
        submit.style(EdenParchment.TAB_ACTIVE);
        if (!editable || !entry.unlocked() || closed) {
            submit.enabled(false);
            submit.tooltip(closed ? tr("closed") : !entry.unlocked() ? tr("min_level", entry.unlockLevel())
                    : tr("browser.edit_unknown"));
        }
        bar.item(submit);
        return bar;
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

    /** A dungeon's, raid's or arena's color for pictures without art (the concept's). */
    private static int typeColor(int type) {
        return switch (type) {
            case 0 -> 0xFF5C7A4A;
            case 1 -> 0xFF6B5A8A;
            case 2 -> 0xFF3A6A8A;
            default -> 0xFF8A6A3A;
        };
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
