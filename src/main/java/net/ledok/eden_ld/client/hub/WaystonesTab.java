package net.ledok.eden_ld.client.hub;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.ledok.eden_ld.client.EdenParchment;
import net.ledok.eden_ld.hub.HubPayloads;
import net.ledok.eden_ld.hub.HubPayloads.WaystoneEntry;
import net.ledok.eden_ld.hub.HubPayloads.WaystonesData;
import net.ledok.vectorlib.client.canvas.layout.Align;
import net.ledok.vectorlib.client.canvas.layout.Flex;
import net.ledok.vectorlib.client.canvas.layout.Insets;
import net.ledok.vectorlib.client.canvas.layout.Justify;
import net.ledok.vectorlib.client.canvas.layout.Sizing;
import net.ledok.vectorlib.client.canvas.CanvasNode;
import net.ledok.vectorlib.client.canvas.DragDrop;
import net.ledok.vectorlib.client.canvas.widget.Button;
import net.ledok.vectorlib.client.canvas.widget.ContextMenu;
import net.ledok.vectorlib.client.canvas.widget.Label;
import net.ledok.vectorlib.client.canvas.widget.Modal;
import net.ledok.vectorlib.client.canvas.widget.Reorder;
import net.ledok.vectorlib.client.canvas.widget.ScrollPanel;
import net.ledok.vectorlib.client.canvas.widget.TextField;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The Waystones tab: favorites (left, in the player's own order) and every destination grouped into
 * Mine / Friends' / Global / Activated (right, each section folding), one search over both, and a fixed
 * status line at the bottom that counts down Eden's hub cooldown and PvP block and briefly shows refusals —
 * it never shifts the layout. Favorites are dragged into order; a waystone dragged from the right onto the
 * favorites becomes one there, and a favorite dragged out to the right stops being one. Teleports follow
 * Eden's rules, not the Waystones mod's costs.
 */
final class WaystonesTab {

    private static final float GAP = 8;
    /** A column's narrowest width (the divider between Favorites and All stops there). */
    private static final float COLUMN_MIN = 150;
    /** ScrollPanel scrollbar lane (default bar 5 + 1). */
    private static final float LANE = 6;
    /** How long a refusal reason replaces the countdown on the status line. */
    private static final long MESSAGE_MILLIS = 5000;
    private static final int[] SECTIONS = {
            WaystoneEntry.MINE, WaystoneEntry.GUILD, WaystoneEntry.FRIEND, WaystoneEntry.GLOBAL, WaystoneEntry.ACTIVATED};

    private static String query = "";
    private static TextField searchField;
    private static Label statusLine;
    /** Whether the last build had teleports locked by a timer (a rebuild re-enables buttons). */
    private static boolean builtLocked;

    private WaystonesTab() {
    }

    private static Component tr(String key, Object... args) {
        return Component.translatable("gui.eden_ld.hub.waystones." + key, args);
    }

    private static HubScreen currentScreen;
    /** The two columns (drop areas for dragging between them). */
    private static Flex favoritesColumn;
    private static Flex allColumn;

    static void build(HubScreen screen, Flex content) {
        currentScreen = screen;
        WaystonesData data = HubState.waystonesData;
        statusLine = null;
        if (data == null) {
            Flex center = Flex.column().justify(Justify.CENTER).alignItems(Align.CENTER);
            center.sizing(Sizing.fill(), Sizing.expand());
            center.item(EdenParchment.text(Component.translatable("gui.eden_ld.hub.home.loading"),
                    EdenParchment.INK_DIM));
            content.item(center);
            return;
        }

        boolean hadFocus = searchField != null && searchField.isFocused();
        // Favorites | all, half and half until the player drags the divider.
        var split = screen.split(net.ledok.vectorlib.client.canvas.widget.SplitView.row().ratio(0.5f),
                "waystones/columns", COLUMN_MIN, COLUMN_MIN, GAP);
        float[] widths = split.sizesFor(HubScreen.innerWidth());
        float favoritesRow = widths[0] - LANE;
        float allRow = widths[1] - LANE;
        Flex favoritesList = Flex.column().gap(2);
        Flex allList = Flex.column().gap(2);
        TextField search = new TextField(HubScreen.innerWidth(), query, text -> {
            query = text;
            fill(favoritesList, allList, data, favoritesRow, allRow);
        });
        search.sizing(Sizing.fill(), Sizing.fixed(16));
        search.hint(tr("search").getString());
        searchField = search;
        content.item(search);

        long favorites = data.waystones().stream().filter(WaystoneEntry::favorite).count();
        favoritesColumn = column("favorites", tr("favorites", favorites), favoritesList, widths[0]);
        allColumn = column("all", tr("all", data.waystones().size()), allList, widths[1]);
        split.first().item(favoritesColumn);
        split.second().item(allColumn);
        content.item(split);
        fill(favoritesList, allList, data, favoritesRow, allRow);
        // A favorite dragged out onto the right column stops being one (outlined while it would).
        DragDrop.zone(allColumn, payload -> payload instanceof Reorder.Item item && item.value() instanceof WaystoneEntry,
                payload -> ClientPlayNetworking.send(new HubPayloads.WaystoneFavorite(
                        ((WaystoneEntry) ((Reorder.Item) payload).value()).id(), false)));

        statusLine = Label.of(HubScreen.innerWidth(), Component.empty());
        content.item(statusLine);
        builtLocked = timersLocked(data);
        updateStatus();

        if (hadFocus) {
            screen.canvas().focus(search);
        }
    }

    /** Small dialog for the player's own name of a waystone (Waystones' alias, shared with its menus). */
    private static void openRename(WaystoneEntry waystone) {
        if (currentScreen == null) {
            return;
        }
        boolean aliased = !waystone.originalName().getString().isEmpty();
        Component original = aliased ? waystone.originalName() : waystone.name();

        Flex dialog = Flex.column().gap(6).padding(Insets.of(8, 10, 8, 10));
        dialog.sizing(Sizing.fixed(200), Sizing.content());
        dialog.background(EdenParchment.panel());
        dialog.item(EdenParchment.text(tr("rename"), EdenParchment.INK));
        dialog.item(EdenParchment.text(tr("rename_hint", original), EdenParchment.INK_DIM, 180));

        TextField field = new TextField(180, aliased ? waystone.name().getString() : "", null);
        field.maxLength(32);
        field.hint(original.getString());
        dialog.item(field);

        Modal[] modal = new Modal[1];
        Runnable save = () -> {
            ClientPlayNetworking.send(new HubPayloads.WaystoneRename(waystone.id(), field.text()));
            modal[0].close();
        };
        field.onSubmit(save);
        // Save and Cancel only: saving an empty name brings the original back (also in the row's
        // right-click menu), so no third button has to squeeze in.
        Flex buttons = Flex.row().gap(6).justify(net.ledok.vectorlib.client.canvas.layout.Justify.END);
        buttons.sizing(Sizing.fixed(180), Sizing.content());
        buttons.item(EdenParchment.fitButton(tr("save"), 56, 14, save));
        buttons.item(EdenParchment.fitButton(Component.translatable("gui.eden_ld.hub.friends.cancel"), 56, 14,
                () -> modal[0].close()));
        dialog.item(buttons);

        modal[0] = Modal.open(currentScreen.canvas(), dialog);
        modal[0].closeOnClickOutside(true);
        currentScreen.canvas().focus(field);
    }

    /** Forgetting takes it off the player's list until they activate it again: confirm first. */
    private static void confirmForget(WaystoneEntry waystone) {
        if (currentScreen == null) {
            return;
        }
        Flex dialog = Flex.column().gap(8).padding(Insets.of(8, 10, EdenParchment.tileEdges() ? 12 : 8, 10))
                .alignItems(Align.CENTER);
        dialog.sizing(Sizing.fixed(200), Sizing.content());
        dialog.background(EdenParchment.panel());
        dialog.item(EdenParchment.text(tr("forget_question", waystone.name()), EdenParchment.INK, 180));
        dialog.item(EdenParchment.text(tr("forget_hint"), EdenParchment.INK_DIM, 180));
        Modal[] modal = new Modal[1];
        Flex buttons = Flex.row().gap(6).justify(net.ledok.vectorlib.client.canvas.layout.Justify.END);
        buttons.sizing(Sizing.fixed(180), Sizing.content());
        buttons.item(EdenParchment.fitButton(tr("forget"), 56, 14, () -> {
            ClientPlayNetworking.send(new HubPayloads.WaystoneForget(waystone.id()));
            modal[0].close();
        }));
        buttons.item(EdenParchment.fitButton(Component.translatable("gui.eden_ld.hub.friends.cancel"), 56, 14,
                () -> modal[0].close()));
        dialog.item(buttons);
        modal[0] = Modal.open(currentScreen.canvas(), dialog);
        modal[0].closeOnClickOutside(true);
    }

    /** Called every client tick while the tab is open: live countdowns, re-enable at zero. */
    static void tick(HubScreen screen) {
        WaystonesData data = HubState.waystonesData;
        if (data == null || statusLine == null) {
            return;
        }
        if (builtLocked && !timersLocked(data)) {
            screen.onWaystonesData();
            return;
        }
        updateStatus();
    }

    private static long elapsed() {
        return System.currentTimeMillis() - HubState.waystonesReceivedAt;
    }

    private static long cooldownLeft(WaystonesData data) {
        return Math.max(0, data.cooldownMillis() - elapsed());
    }

    private static long pvpLeft(WaystonesData data) {
        return Math.max(0, data.pvpMillis() - elapsed());
    }

    private static boolean timersLocked(WaystonesData data) {
        return cooldownLeft(data) > 0 || pvpLeft(data) > 0;
    }

    private static void updateStatus() {
        WaystonesData data = HubState.waystonesData;
        if (statusLine == null || data == null) {
            return;
        }
        Component text;
        int color;
        if (HubState.waystoneMessage != null
                && System.currentTimeMillis() - HubState.waystoneMessageAt < MESSAGE_MILLIS) {
            text = HubState.waystoneMessage;
            color = EdenParchment.DANGER;
        } else if (data.busy()) {
            text = tr("busy");
            color = EdenParchment.WARN;
        } else if (pvpLeft(data) > 0) {
            text = tr("status.pvp", seconds(pvpLeft(data)));
            color = EdenParchment.DANGER;
        } else if (cooldownLeft(data) > 0) {
            text = tr("status.cooldown", seconds(cooldownLeft(data)));
            color = EdenParchment.INK_DIM;
        } else {
            text = tr("status.ready");
            color = EdenParchment.GOOD;
        }
        statusLine.text(text);
        statusLine.color(color);
    }

    private static String seconds(long millis) {
        long total = (millis + 999) / 1000;
        return total >= 60 ? String.format("%d:%02d", total / 60, total % 60) : total + "s";
    }

    private static Flex column(String key, Component title, Flex list, float width) {
        Flex column = Flex.column().gap(3);
        column.sizing(Sizing.fill(), Sizing.fill());
        column.item(EdenParchment.text(title, EdenParchment.INK_MID));
        ScrollPanel panel = HubScroll.panel("waystones/" + key, width, 100, list);
        panel.sizing(Sizing.fill(), Sizing.expand());
        column.item(panel);
        return column;
    }

    private static void fill(Flex favoritesList, Flex allList, WaystonesData data, float favoritesRow, float allRow) {
        favoritesList.clear();
        allList.clear();
        String needle = query.trim().toLowerCase(Locale.ROOT);
        List<WaystoneEntry> matching = new ArrayList<>();
        for (WaystoneEntry waystone : data.waystones()) {
            if (needle.isEmpty() || waystone.name().getString().toLowerCase(Locale.ROOT).contains(needle)
                    || waystone.originalName().getString().toLowerCase(Locale.ROOT).contains(needle)
                    || waystone.ownerName().toLowerCase(Locale.ROOT).contains(needle)) {
                matching.add(waystone);
            }
        }

        // Favorites in the player's own order; ones not placed yet follow by name.
        java.util.Map<java.util.UUID, Integer> rank = new java.util.HashMap<>();
        for (int i = 0; i < data.favoriteOrder().size(); i++) {
            rank.putIfAbsent(data.favoriteOrder().get(i), i);
        }
        List<WaystoneEntry> favorites = matching.stream().filter(WaystoneEntry::favorite)
                .sorted(java.util.Comparator.<WaystoneEntry>comparingInt(w -> rank.getOrDefault(w.id(), Integer.MAX_VALUE))
                        .thenComparing(w -> w.name().getString().toLowerCase(Locale.ROOT)))
                .toList();
        for (int i = 0; i < favorites.size(); i++) {
            favoritesList.item(row(favorites.get(i), data, i % 2 == 1, favoritesRow));
        }
        if (favorites.isEmpty()) {
            favoritesList.item(EdenParchment.text(needle.isEmpty() ? tr("no_favorites") : tr("no_matches"),
                    EdenParchment.INK_DIM, (int) favoritesRow));
        }
        if (needle.isEmpty()) {
            // Drag favorites into order; drop a waystone from the right column in to make it a favorite there.
            Reorder.attach(favoritesList, favorites, (from, to) -> {
                List<WaystoneEntry> order = new ArrayList<>(favorites);
                WaystoneEntry moved = order.remove((int) from);
                order.add(to > from ? to - 1 : to, moved);
                sendOrder(order);
            }).accept(payload -> payload instanceof WaystoneEntry, (payload, at) -> {
                WaystoneEntry waystone = (WaystoneEntry) payload;
                List<WaystoneEntry> order = new ArrayList<>(favorites);
                int old = indexOf(order, waystone);
                if (old >= 0) {
                    order.remove(old);
                    if (old < at) {
                        at--;
                    }
                } else {
                    ClientPlayNetworking.send(new HubPayloads.WaystoneFavorite(waystone.id(), true));
                }
                order.add(Math.max(0, Math.min(order.size(), at)), waystone);
                sendOrder(order);
            }).ghost(payload -> payload instanceof Reorder.Item item && item.value() instanceof WaystoneEntry waystone
                    ? ghost(waystone) : null)
                    .dropArea(favoritesColumn);
        }

        boolean any = false;
        for (int category : SECTIONS) {
            List<WaystoneEntry> section = matching.stream().filter(w -> w.category() == category).toList();
            if (section.isEmpty()) {
                continue;
            }
            if (any) {
                allList.item(EdenParchment.spacer(2));
            }
            any = true;
            // Each section folds (its heading is the toggle), and stays folded on this computer.
            var fold = HubFolds.section(tr("section." + category, section.size()), "waystones/" + category);
            fold.body().gap(2);
            allList.item(fold);
            for (int i = 0; i < section.size(); i++) {
                WaystoneEntry waystone = section.get(i);
                Flex row = row(waystone, data, i % 2 == 1, allRow);
                // Picked up from here, it can be dropped on the favorites.
                DragDrop.source(row, new DragDrop.Source() {
                    @Override
                    public Object payload() {
                        return waystone;
                    }

                    @Override
                    public CanvasNode ghost(Object payload) {
                        return WaystonesTab.ghost(waystone);
                    }
                });
                fold.body().item(row);
            }
        }
        if (!any) {
            allList.item(EdenParchment.text(needle.isEmpty() ? tr("no_waystones") : tr("no_matches"),
                    EdenParchment.INK_DIM, (int) allRow));
        }
    }

    private static void sendOrder(List<WaystoneEntry> favorites) {
        ClientPlayNetworking.send(new HubPayloads.WaystoneOrder(favorites.stream().map(WaystoneEntry::id).toList()));
    }

    private static int indexOf(List<WaystoneEntry> list, WaystoneEntry waystone) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id().equals(waystone.id())) {
                return i;
            }
        }
        return -1;
    }

    /** What follows the pointer while a waystone is dragged: its name on a chip. */
    private static CanvasNode ghost(WaystoneEntry waystone) {
        return DragDrop.chip(waystone.name(), EdenParchment.THEME);
    }

    private static Flex row(WaystoneEntry waystone, WaystonesData data, boolean alt, float width) {
        Flex row = Flex.row().gap(4).padding(Insets.of(2, 3, 2, 3)).alignItems(Align.CENTER);
        row.sizing(Sizing.fixed(width), Sizing.content());
        row.background(EdenParchment.framed(alt ? EdenParchment.ROW_BG_ALT : EdenParchment.ROW_BG, EdenParchment.HAIRLINE));

        Button star = new Button(12, 12, Component.literal(waystone.favorite() ? "★" : "☆"),
                () -> ClientPlayNetworking.send(new HubPayloads.WaystoneFavorite(waystone.id(), !waystone.favorite())));
        star.tooltip(tr(waystone.favorite() ? "unfavorite" : "favorite"));
        row.item(star);

        Component teleportLabel = tr("teleport");
        float buttonWidth = EdenParchment.fitWidth(teleportLabel, 50);
        // Padding, star, Teleport, and the 4-unit gaps between star, text, spacer and Teleport.
        float textWidth = width - 6 - 12 - buttonWidth - 4 * 3;
        boolean aliased = !waystone.originalName().getString().isEmpty();
        Component place = waystone.distance() >= 0
                ? tr("location_distance", FriendsTab.dimensionName(waystone.dimension()), waystone.distance())
                : FriendsTab.dimensionName(waystone.dimension());
        boolean someoneElses = waystone.category() == WaystoneEntry.FRIEND || waystone.category() == WaystoneEntry.GUILD;
        Component where = someoneElses && !waystone.ownerName().isEmpty()
                ? tr("owned_by", waystone.ownerName(), place)
                : place;
        // Rename and the original name live in the right-click menu; the hover names the stone in
        // full (and its real name when renamed).
        // Global and guild-shared stones are always listed, so only the others can be forgotten.
        boolean forgettable = waystone.category() != WaystoneEntry.GLOBAL && waystone.category() != WaystoneEntry.GUILD;
        InfoText text = new InfoText(currentScreen, textWidth, waystone.name(), where).menu(() -> {
            List<ContextMenu.Item> items = new ArrayList<>();
            items.add(new ContextMenu.Item(tr("menu.rename"), () -> openRename(waystone), true));
            items.add(new ContextMenu.Item(tr("menu.original"), () -> ClientPlayNetworking.send(
                    new HubPayloads.WaystoneRename(waystone.id(), "")), aliased));
            items.add(new ContextMenu.Item(tr(waystone.favorite() ? "unfavorite" : "favorite"), () -> ClientPlayNetworking.send(
                    new HubPayloads.WaystoneFavorite(waystone.id(), !waystone.favorite())), true));
            if (forgettable) {
                items.add(new ContextMenu.Item(tr("menu.forget"), () -> confirmForget(waystone), true));
            }
            return items;
        });
        text.tooltip(aliased
                ? new Component[]{waystone.name(), tr("original_name", waystone.originalName()), tr("row_hint")}
                : new Component[]{waystone.name(), where, tr("row_hint")});
        row.item(text);
        row.spacer();

        Button teleport = new Button(buttonWidth, 14, teleportLabel, () -> {
            HubState.waystoneMessage = null;
            ClientPlayNetworking.send(new HubPayloads.WaystoneTeleport(waystone.id()));
        });
        Component blocked = data.busy() ? tr("busy")
                : pvpLeft(data) > 0 ? tr("blocked.pvp")
                : cooldownLeft(data) > 0 ? tr("blocked.cooldown")
                : null;
        teleport.enabled(blocked == null);
        if (blocked != null) {
            teleport.tooltip(blocked);
        }
        row.item(teleport);
        return row;
    }
}
