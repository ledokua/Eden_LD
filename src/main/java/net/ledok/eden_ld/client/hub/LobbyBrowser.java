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
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * The Activities tab as a lobby browser (1.3.103; laid out as the Arenas UI rework concept's section 4 in 1.3.105, after
 * LeDok's TEST look): every lobby forming now that the player may see, with Arenas_LD 4.33.0+.
 * <ul>
 *   <li>The page's title row carries «Моє лобі» / «Змінити лобі» and «+ Створити лобі» (top right).</li>
 *   <li>Under the kind tabs, filters for activity, difficulty, hardcore, "only joinable", and the sort.</li>
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
    /** A modifier row whose effect goes under its name. */
    private static final float MOD_H2 = 25;
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
        hosted = null;
        panelOpen = false;
        editing = false;
        awaitingLobby = false;
        panelActivity = null;
        info = null;
    }

    // ---------------------------------------------------------------- the hosted lobby (1.3.110)

    /**
     * A lobby view another mod hands the hub (Arenas_LD 4.35.0+, {@link ArenasLobbyHosting}): it fills the Activities tab
     * under the title row («name» + ← Back), keeps its own tabs and action bar, and closes its server menu on close().
     */
    interface Hosted {
        CanvasNode node();

        void resize(float width, float height);

        boolean keyPressed(int key, int scanCode, int modifiers);

        boolean charTyped(char c, int modifiers);

        void mouseClicked(float x, float y);

        void close();

        boolean closed();
    }

    @Nullable
    private static Hosted hosted;
    private static Component hostedName = Component.empty();

    /**
     * A lobby opens (controller block, invite, badge, join, create, «Моє лобі»): it shows in the hub, opened on
     * Activities when it isn't up. False (Arenas shows its own screen) only on a server without Eden's hub.
     */
    static boolean host(ResourceLocation activity, Component name, Hosted view) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || !ClientPlayNetworking.canSend(HubPayloads.ActivitiesRequest.TYPE)) {
            return false;
        }
        Hosted old = hosted;
        if (old != null && old != view) {
            // Never closes the newer lobby's menu (Arenas checks).
            old.close();
        }
        hosted = view;
        hostedName = name;
        panelOpen = false;
        editing = false;
        awaitingLobby = false;
        if (minecraft.screen instanceof HubScreen hub) {
            hub.selectTab(HubTab.ACTIVITIES);
        } else {
            HubScreen.open("activities");
        }
        return true;
    }

    /** The Activities tab shows a hosted lobby. */
    static boolean hostedOpen() {
        return hosted != null;
    }

    /** ← Back, a tab switch or the hub closing: the view's menu closes (the lobby keeps running). */
    static void closeHosted() {
        Hosted view = hosted;
        hosted = null;
        if (view != null) {
            view.close();
        }
    }

    /** Every client tick: the view was closed (left, the lobby gone, a kick): the browser comes back. */
    static void tick() {
        Hosted view = hosted;
        if (view != null && view.closed()) {
            hosted = null;
            // Left the lobby (or it's gone): back to the lobby list (LeDok 2026-11-19, 1.3.124: «Покинути» left the
            // player on an empty «Ви не в лобі»). A run starting closes the hub anyway: Arenas_LD 4.36.1+ sends every
            // party member vanilla's container close, which closes the screen.
            if (Minecraft.getInstance().screen instanceof HubScreen hub && HubState.lastTab == HubTab.ACTIVITIES) {
                hub.onActivitiesData();
            }
        }
    }

    static boolean keyPressed(int key, int scanCode, int modifiers) {
        return hosted != null && hosted.keyPressed(key, scanCode, modifiers);
    }

    static boolean charTyped(char c, int modifiers) {
        return hosted != null && hosted.charTyped(c, modifiers);
    }

    static void clicked(float x, float y) {
        if (hosted != null) {
            hosted.mouseClicked(x, y);
        }
    }

    /** The tab's content while a lobby is hosted: the title row names it, with ← Back to the browser. */
    static void buildHosted(HubScreen screen, Flex content) {
        Hosted view = hosted;
        if (view == null) {
            return;
        }
        Component back = tr("browser.back");
        Button button = EdenParchment.button(back, EdenParchment.fitWidth(back, 50), 14, () -> {
            closeHosted();
            screen.onActivitiesData();
        });
        button.tooltip(tr("browser.back_hint"));
        screen.titleActions(EdenParchment.text(hostedName, EdenParchment.INK), button);
        content.item(new HostPanel(view));
    }

    /** Fills the tab and passes its size on to the view (which lays itself out). */
    private static final class HostPanel extends net.ledok.vectorlib.client.canvas.CanvasGroup
            implements net.ledok.vectorlib.client.canvas.layout.Resizable {
        private final Hosted view;
        private float width = -1;
        private float height = -1;

        HostPanel(Hosted view) {
            this.view = view;
            sizing(Sizing.fill(), Sizing.expand());
            CanvasNode node = view.node();
            // The hub rebuilds its content often; the view's one node moves to the new panel.
            node.removeFromParent();
            add(node);
        }

        @Override
        public void resize(float width, float height) {
            if (width == this.width && height == this.height) {
                return;
            }
            this.width = width;
            this.height = height;
            view.resize(width, height);
        }

        @Override
        public net.ledok.vectorlib.client.canvas.Rect bounds() {
            return new net.ledok.vectorlib.client.canvas.Rect(0, 0, Math.max(0, width), Math.max(0, height));
        }
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
            boolean filtered = !activityFilter.isEmpty() || !difficultyFilter.isEmpty() || hardcoreFilter != 0 || joinableOnly;
            Flex empty = filtered
                    ? HubScreen.emptyState(HubTab.ACTIVITIES, tr("browser.no_matches_title"), tr("browser.no_matches"))
                    : HubScreen.emptyState(HubTab.ACTIVITIES, tr("lobbies.none_title"), tr("browser.none"));
            empty.id(HubSpotlights.ACTIVITIES_CARDS);
            if (filtered) {
                empty.item(EdenParchment.fitButton(tr("browser.clear_filters"), 80, 14, () -> {
                    activityFilter = "";
                    difficultyFilter = "";
                    hardcoreFilter = 0;
                    joinableOnly = false;
                    screen.onActivitiesData();
                }));
            } else {
                // A second, big create button right where the player is looking (1.3.124).
                empty.item(EdenParchment.spacer(4));
                empty.item(createButton(screen, kind, 140, 22));
            }
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

    /** The title row's «+ Створити лобі»: taller and wider than the other title buttons, in the accent skin. */
    private static Button createButton(HubScreen screen, int kind) {
        return createButton(screen, kind, 90, 16);
    }

    /**
     * «+ Створити лобі» at a size: the title row's, or the big one in an empty list (LeDok 2026-11-19, 1.3.124: new
     * players didn't find it).
     */
    private static Button createButton(HubScreen screen, int kind, float minWidth, float height) {
        Component label = tr("browser.create_plus");
        Button create = EdenParchment.button(label, EdenParchment.fitWidth(label, minWidth) + 8, height,
                () -> openPanel(screen, null, false, kind));
        create.style(EdenParchment.TAB_ACTIVE);
        create.tooltip(tr("browser.create_hint"));
        return create;
    }

    /** The filter row wraps its auxiliary controls when the localized labels need more space. */
    private static Flex filters(HubScreen screen, HubPayloads.ActivitiesData data, Map<String, ActivityEntry> byId,
                                int kind, float width) {
        Flex block = Flex.column().gap(3);
        block.sizing(Sizing.fill(), Sizing.content());
        block.id(HubSpotlights.ACTIVITIES_SORT);
        Flex row = Flex.row().gap(3).alignItems(Align.CENTER);
        row.sizing(Sizing.fill(), Sizing.content());
        block.item(row);

        List<String> activityOptions = new ArrayList<>();
        activityOptions.add("");
        for (ActivityEntry entry : sortedEntries(data, kind)) {
            activityOptions.add(entry.id().toString());
        }
        if (!activityOptions.contains(activityFilter)) {
            activityFilter = "";
        }
        Function<String, Component> activityName = id -> id.isEmpty() ? tr("browser.any_activity")
                : byId.containsKey(id) ? byId.get(id).name() : Component.literal(id);
        List<String> difficultyOptions = new ArrayList<>();
        difficultyOptions.add("");
        difficultyOptions.addAll(DIFFICULTIES);
        Function<String, Component> difficultyLabel = id -> id.isEmpty() ? tr("browser.any_difficulty") : difficultyName(id);
        Function<Integer, Component> hardcoreName = value -> tr("browser.hardcore." + value);
        Function<Sort, Component> sortName = candidate -> tr("browser.sort." + candidate.name().toLowerCase(Locale.ROOT));
        Component joinableLabel = tr("browser.joinable");
        float toggleWidth = Minecraft.getInstance().font.width(joinableLabel) + 22;
        float sortWidth = filterWidth(List.of(Sort.values()), sortName, 62, 110);
        float activityWidth = filterWidth(activityOptions, activityName, 100, 170);
        float difficultyWidth = filterWidth(difficultyOptions, difficultyLabel, 70, 130);
        float hardcoreWidth = filterWidth(HARDCORE_FILTERS, hardcoreName, 70, 140);
        boolean wrap = activityWidth + difficultyWidth + hardcoreWidth + toggleWidth + sortWidth + 15 > width;
        if (wrap) {
            activityWidth = Math.min(220, width - difficultyWidth - hardcoreWidth - 6);
        }

        row.item(filterDropdown(activityWidth, activityOptions, activityFilter, activityName, id -> {
            activityFilter = id;
            screen.onActivitiesData();
        }));
        row.item(filterDropdown(difficultyWidth, difficultyOptions, difficultyFilter, difficultyLabel, id -> {
            difficultyFilter = id;
            screen.onActivitiesData();
        }));
        row.item(filterDropdown(hardcoreWidth, HARDCORE_FILTERS, hardcoreFilter, hardcoreName, value -> {
            hardcoreFilter = value;
            screen.onActivitiesData();
        }));

        Flex actions = row;
        if (wrap) {
            actions = Flex.row().gap(3).alignItems(Align.CENTER);
            actions.sizing(Sizing.fill(), Sizing.content());
            block.item(actions);
        }
        Toggle joinable = new Toggle(toggleWidth, joinableLabel, joinableOnly, value -> {
            joinableOnly = value;
            screen.onActivitiesData();
        });
        joinable.tooltip(tr("browser.joinable_hint"));
        actions.item(joinable);
        actions.spacer();
        actions.item(filterDropdown(sortWidth, List.of(Sort.values()), sort, sortName, candidate -> {
            sort = candidate;
            screen.onActivitiesData();
        }));
        return block;
    }

    private static <T> float filterWidth(List<T> options, Function<T, Component> label, float min, float max) {
        var font = Minecraft.getInstance().font;
        int widest = options.stream().map(label).mapToInt(font::width).max().orElse(0);
        return clamp(widest + 20, min, max);
    }

    private static <T> Dropdown<T> filterDropdown(float width, List<T> options, T selected, Function<T, Component> label,
                                                  Consumer<T> onSelect) {
        var font = Minecraft.getInstance().font;
        int textWidth = (int) width - 20;
        Dropdown<T> dropdown = new Dropdown<>(width, options, selected, value -> {
            Component text = label.apply(value);
            if (font.width(text) <= textWidth) {
                return text;
            }
            String cut = font.plainSubstrByWidth(text.getString(), textWidth - font.width("…"));
            return Component.literal(cut + "…");
        }, onSelect);
        dropdown.sizing(Sizing.fixed(width), Sizing.fixed(16));
        dropdown.tooltip(() -> List.of(label.apply(dropdown.selected())));
        return dropdown;
    }

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
        lines.item(line(first, EdenParchment.INK, textWidth));

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
        int[] instances = HubState.activityInstances.get(row.activity());
        if (instances != null && !lobby.member()) {
            if (!rest.getSiblings().isEmpty()) {
                rest.append(" · ");
            }
            rest.append(tr("browser.instances", instances[0], instances[1]).copy()
                    .withColor((instances[0] > 0 ? EdenParchment.INK_DIM : EdenParchment.DANGER) & 0xFFFFFF));
        }
        if (lobby.member()) {
            if (!rest.getSiblings().isEmpty()) {
                rest.append(" · ");
            }
            rest.append(tr("lobbies.yours").copy().withColor(EdenParchment.GOOD & 0xFFFFFF));
        }
        if (!rest.getSiblings().isEmpty()) {
            float used = pills.measure(-1, -1)[0] + (pills.children().isEmpty() ? 0 : 2);
            pills.item(line(rest, EdenParchment.INK_DIM, Math.max(12, textWidth - used)));
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

    static void closePanel(HubScreen screen) {
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

    /**
     * Text wrapped to {@code width} (LeDok 2026-11-19, 1.3.124: wrap, don't cut with "…"), one single-line node per line,
     * split here so each line's height is known before layout (a wrapped TextNode was once measured one line short and
     * the next line drew over it, 1.3.106). The whole text is the tooltip too.
     */
    private static Flex wrapped(Component text, int color, float width) {
        Flex column = Flex.column().gap(1);
        var font = Minecraft.getInstance().font;
        for (net.minecraft.network.chat.FormattedText part : font.getSplitter().splitLines(text, Math.max(20, (int) width),
                net.minecraft.network.chat.Style.EMPTY)) {
            column.item(EdenParchment.text(Component.literal(part.getString()), color));
        }
        column.tooltip(text);
        return column;
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
            // Free places right under it (LeDok's mock-up «INSTANCES 1/10», 1.3.124), red when none is free.
            Component instances = instancesLine(entry.id().toString());
            if (instances != null) {
                int[] count = HubState.activityInstances.get(entry.id().toString());
                var line = EdenParchment.text(instances, count[0] > 0 ? EdenParchment.INK_DIM : EdenParchment.DANGER);
                line.tooltip(tr("browser.instances_hint"));
                list.item(line);
            }
        }
        return list;
    }

    /** «Вільно: 1/10» for an activity whose instances the server reported; null when unknown. */
    @Nullable
    private static Component instancesLine(String activity) {
        int[] count = HubState.activityInstances.get(activity);
        return count == null ? null : tr("browser.instances", count[0], count[1]);
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
        body.item(wrapped(entry.name(), EdenParchment.INK, width));
        body.item(wrapped(tr("browser.type." + entry.type()), EdenParchment.INK_MID, width));
        if (info != null && info.maxMembers() > 0) {
            body.item(wrapped(tr("browser.max_members", info.maxMembers()), EdenParchment.INK_MID, width));
        }
        Component instances = instancesLine(entry.id().toString());
        if (instances != null) {
            int[] count = HubState.activityInstances.get(entry.id().toString());
            body.item(wrapped(instances, count[0] > 0 ? EdenParchment.INK_MID : EdenParchment.DANGER, width));
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
            body.item(wrapped(level > 0 ? tr("browser.required_level_yours", level, data.level())
                    : tr("browser.level_none", data.level()), meets ? EdenParchment.GOOD : EdenParchment.DANGER, width));
            // Every difficulty's level, ✓ or ✗ against the player's (LeDok: the drops' space goes to this, 1.3.109).
            if (info != null && !info.difficulties().isEmpty()) {
                body.item(section(tr("browser.levels")));
                for (int i = 0; i < info.difficulties().size(); i++) {
                    int needed = i < info.levels().size() ? info.levels().get(i) : 0;
                    boolean has = needed <= 0 || data.level() >= needed;
                    Component tier = difficultyName(info.difficulties().get(i));
                    body.item(wrapped(tr(has ? "browser.level_row_ok" : "browser.level_row_no", tier,
                                    needed > 0 ? tr("browser.level_badge", needed) : tr("browser.level_free")),
                            has ? EdenParchment.GOOD : EdenParchment.DANGER, width));
                }
            }
        }
        // A win's base loot rolls (LeDok, 1.3.118): the controller's own, or the server's default.
        if (info != null && (!Double.isNaN(info.lootRolls()) || info.lootRollsDefault())) {
            Component rolls = Double.isNaN(info.lootRolls()) ? tr("browser.loot_rolls_default_only")
                    : tr(info.lootRollsDefault() ? "browser.loot_rolls_default" : "browser.loot_rolls",
                            formatRolls(info.lootRolls()));
            Flex rollsLine = wrapped(rolls, EdenParchment.INK_MID, width);
            rollsLine.tooltip(rolls, tr("browser.loot_rolls_hint"));
            body.item(rollsLine);
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

    /** 3 → "3", 2.5 → "2.5". */
    private static String formatRolls(double rolls) {
        return rolls == Math.rint(rolls) ? Long.toString((long) rolls)
                : String.format(Locale.ROOT, "%.1f", rolls);
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
            // The widest button's label and level badge with the skin's padding (1.3.124: «Норм… 5+» was cut), then
            // as many per row as fit: all, two, or one.
            float widest = 0;
            for (int i = 0; i < count; i++) {
                int level = i < info.levels().size() ? info.levels().get(i) : 0;
                Component badge = levelBadge(data, level);
                widest = Math.max(widest, font.width(difficultyName(info.difficulties().get(i))) + 24
                        + (badge == null ? 0 : font.width(badge) + 10));
            }
            int perRow = count * widest + 2 * (count - 1) <= width ? count : 2 * widest + 2 <= width ? 2 : 1;
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
        MOD_ROWS.clear();
        for (HubPayloads.ModifierLine modifier : info.modifiers()) {
            if (!any) {
                body.item(section(tr("browser.modifiers")));
                any = true;
            }
            boolean isHardcore = HARDCORE.equals(modifier.id());
            // A click redraws the rows in place, not the page (1.3.111: a rebuilt page put the hover glow on the row
            // where the click would have been before its scroll position came back).
            ModifierRow row = new ModifierRow(modifier, isHardcore, width, value -> {
                toggle(modifier, isHardcore, value);
                for (ModifierRow shown : MOD_ROWS) {
                    shown.refresh();
                }
            });
            MOD_ROWS.add(row);
            body.item(row);
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
    /** The modifier rows on screen: a click refreshes them all (one may switch others off). */
    private static final List<ModifierRow> MOD_ROWS = new ArrayList<>();

    private static final class ModifierRow extends Widget {
        private final HubPayloads.ModifierLine modifier;
        private final boolean hardcoreRow;
        private final java.util.function.Consumer<Boolean> onChange;

        ModifierRow(HubPayloads.ModifierLine modifier, boolean hardcoreRow, float width,
                    java.util.function.Consumer<Boolean> onChange) {
            super(width, rowHeight(modifier, width));
            this.modifier = modifier;
            this.hardcoreRow = hardcoreRow;
            this.onChange = onChange;
            sizing(Sizing.fixed(width), Sizing.fixed(rowHeight(modifier, width)));
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

        /** Name and effect don't fit on one line side by side: two lines. */
        private static boolean twoLines(HubPayloads.ModifierLine modifier, float width) {
            var font = Minecraft.getInstance().font;
            float available = width - (4 + 9 + 4) - 4;
            float effect = font.width(modifier.rewards());
            return effect > 0 && font.width(modifier.name()) + 6 + effect > available;
        }

        private static float rowHeight(HubPayloads.ModifierLine modifier, float width) {
            return twoLines(modifier, width) ? MOD_H2 : MOD_H;
        }

        /** Read live: another row's click may have switched this one off. */
        private boolean on() {
            return hardcoreRow ? hardcore : modifiers.contains(modifier.id());
        }

        @Override
        protected void rebuild() {
            boolean on = on();
            int accent = hardcoreRow ? EdenParchment.DANGER : EdenParchment.ACCENT;
            add(Shapes.rect(0, 0, width, height).fill(on ? (0x55000000 | (accent & 0xFFFFFF))
                    : hovered ? 0x22000000 | (EdenParchment.INK & 0xFFFFFF) : EdenParchment.ROW_BG_ALT));
            if (on) {
                add(Shapes.rect(0, 0, 2, height).fill(accent | 0xFF000000));
            }
            if (focused) {
                add(Shapes.rect(0.5f, 0.5f, width - 1, height - 1).stroke(Stroke.of(EdenParchment.ACCENT, 1)));
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
            if (twoLines(modifier, width)) {
                // Name over its effect (1.3.124: the effect was cut with "…"); still "…" past the whole width.
                add(TextNode.of(modifier.name()).at(textX, 3).maxWidth((int) available).ellipsis(true)
                        .color(EdenParchment.INK)).pickable(false);
                add(TextNode.of(modifier.rewards()).at(textX, 14).maxWidth((int) available).ellipsis(true)
                        .color(EdenParchment.GOOD)).pickable(false);
                return;
            }
            // One line only when name and effect both fit whole (twoLines), so the effect gets all it needs (1.3.125: a
            // 45% cap still cut effects like «+15% досвіду · +25% валюти» that fit beside their name).
            float effectWidth = font.width(modifier.rewards());
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
            toggle();
            return true;
        }

        @Override
        public boolean focusable() {
            return true;
        }

        @Override
        public boolean onKeyPressed(int key, int scanCode, int modifiers) {
            if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER || key == GLFW.GLFW_KEY_SPACE) {
                toggle();
                return true;
            }
            return false;
        }

        private void toggle() {
            UiSounds.click();
            onChange.accept(!on());
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
            Component hint = entry == null ? tr("browser.pick") : Component.translatable("gui.eden_ld.hub.home.loading");
            bar.item(EdenParchment.text(hint, EdenParchment.INK_DIM, (int) width - 8));
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
