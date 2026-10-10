package net.ledok.eden_ld.client.hub;


import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.ledok.eden_ld.client.EdenParchment;
import net.ledok.eden_ld.hub.HubPayloads.FriendAction;
import net.ledok.eden_ld.hub.HubPayloads.FriendAction.Action;
import net.ledok.eden_ld.hub.HubPayloads.FriendEntry;
import net.ledok.eden_ld.hub.HubPayloads.FriendsData;
import net.ledok.eden_ld.hub.HubPayloads.PartyAction;
import net.ledok.eden_ld.hub.party.PartyManager;
import net.ledok.eden_ld.hub.HubPayloads.RequestEntry;
import net.ledok.eden_ld.hub.HubPayloads.ServerPlayerEntry;
import net.ledok.vectorlib.client.canvas.layout.Align;
import net.ledok.vectorlib.client.canvas.layout.Flex;
import net.ledok.vectorlib.client.canvas.layout.Insets;
import net.ledok.vectorlib.client.canvas.layout.Justify;
import net.ledok.vectorlib.client.canvas.layout.Sizing;
import net.ledok.vectorlib.client.canvas.widget.Button;
import net.ledok.vectorlib.client.canvas.widget.Label;
import net.ledok.vectorlib.client.canvas.widget.Modal;
import net.ledok.vectorlib.client.canvas.widget.ScrollPanel;
import net.ledok.vectorlib.client.canvas.widget.TextField;
import net.minecraft.network.chat.Component;

import java.util.Locale;
import java.util.UUID;

/**
 * The Friends tab: this server's online players with an always-visible search (left),
 * the friends list (center, widest), and a collapsible requests panel toggled by a
 * slim bar on the right edge — open by itself while incoming requests wait. Every
 * action is a payload; the server answers with fresh {@link FriendsData}.
 */
final class FriendsTab {

    // Fixed side columns leave the rest of the tab's width to the friends list.
    private static final float PLAYERS_WIDTH = 124;
    private static final float REQUESTS_WIDTH = 112;
    /** Wide enough that a two-digit count clears the button's border art. */
    private static final float BAR_WIDTH = 14;
    private static final float FRIEND_COLUMN_GAP = 3;
    /** A friend card's narrowest width: the list gets another column whenever one more fits. */
    private static final float FRIEND_CARD_MIN = 120;
    private static final int FRIEND_COLUMNS_MAX = 4;
    private static final float GAP = 6;
    /**
     * A ScrollPanel's scrollbar lane (default bar 5 + 1). List columns are this much wider
     * than their rows and sit flush against the toggle bars, so the lane lives there and
     * the cards line up with the search fields above them.
     */
    private static final float LANE = 6;

    private static String searchQuery = "";
    private static TextField searchField;
    private static String friendQuery = "";
    private static TextField friendSearchField;
    /** Null follows the incoming requests (open while any wait); set once the player toggles. */
    private static Boolean requestsOpen;
    private static int seenIncoming;
    private static boolean playersOpen = true;

    private FriendsTab() {
    }

    private static Component tr(String key, Object... args) {
        return Component.translatable("gui.eden_ld.hub.friends." + key, args);
    }

    private static void send(Action action, UUID target) {
        ClientPlayNetworking.send(FriendAction.of(action, target));
    }

    static void build(HubScreen screen, Flex content) {
        FriendsData data = HubState.friendsData;
        if (data == null) {
            Flex center = Flex.column().justify(Justify.CENTER).alignItems(Align.CENTER);
            center.sizing(Sizing.fill(), Sizing.expand());
            center.item(EdenParchment.text(Component.translatable("gui.eden_ld.hub.home.loading"),
                    EdenParchment.INK_DIM));
            content.item(center);
            return;
        }

        // A new incoming request re-arms the automatic open state (not one from an ignored player).
        int incoming = HubState.friendRequestCount();
        if (incoming > seenIncoming) {
            requestsOpen = null;
        }
        seenIncoming = incoming;
        boolean open = requestsOpen != null ? requestsOpen : incoming > 0;

        // [players] bar friends bar [requests]: hidden side panels (and a wider hub) buy more friend columns.
        float friendsWidth = HubScreen.innerWidth() - BAR_WIDTH * 2 - GAP - LANE
                - (playersOpen ? PLAYERS_WIDTH + LANE : 0)
                - (open ? REQUESTS_WIDTH + GAP : 0);
        int friendColumns = Math.max(1, Math.min(FRIEND_COLUMNS_MAX,
                (int) ((friendsWidth + FRIEND_COLUMN_GAP) / (FRIEND_CARD_MIN + FRIEND_COLUMN_GAP))));

        Flex columns = Flex.row().gap(GAP);
        columns.sizing(Sizing.fill(), Sizing.expand());
        // Column + bar pairs: fill the height so the lists and bars run top to bottom.
        Flex left = Flex.row();
        left.sizing(Sizing.content(), Sizing.fill());
        if (playersOpen) {
            left.item(playersColumn(screen, data));
        }
        left.item(playersBar(data));
        columns.item(left);
        Flex center = Flex.row();
        center.sizing(Sizing.content(), Sizing.fill());
        center.item(friendsColumn(screen, data, friendsWidth, friendColumns));
        center.item(requestsBar(data, open));
        columns.item(center);
        if (open) {
            columns.item(requestsColumn(data));
        }
        content.item(columns);
    }

    // ---------------------------------------------------------------- players on this server

    private static Flex playersColumn(HubScreen screen, FriendsData data) {
        Flex column = Flex.column().gap(3);
        column.sizing(Sizing.fixed(PLAYERS_WIDTH + LANE), Sizing.fill());
        column.item(EdenParchment.text(tr("on_server", data.players().size()), EdenParchment.INK_MID));

        // Survives tab rebuilds (pushes land while typing): same text, focus handed over.
        boolean hadFocus = searchField != null && searchField.isFocused();
        Flex list = Flex.column().gap(3);
        TextField search = new TextField(PLAYERS_WIDTH, searchQuery, query -> {
            searchQuery = query;
            fillPlayers(screen, list, data);
        });
        search.sizing(Sizing.fixed(PLAYERS_WIDTH), Sizing.fixed(16));
        search.hint(tr("search").getString());
        searchField = search;
        column.item(search);

        ScrollPanel panel = HubScroll.panel("friends/players", PLAYERS_WIDTH + LANE, 100, list);
        panel.sizing(Sizing.fixed(PLAYERS_WIDTH + LANE), Sizing.expand());
        column.item(panel);
        fillPlayers(screen, list, data);

        if (hadFocus) {
            screen.canvas().focus(search);
        }
        return column;
    }

    private static void fillPlayers(HubScreen screen, Flex list, FriendsData data) {
        list.clear();
        String needle = searchQuery.trim().toLowerCase(Locale.ROOT);
        int shown = 0;
        // By name as the server sends them, ignored players last.
        java.util.List<ServerPlayerEntry> players = new java.util.ArrayList<>(data.players());
        players.sort(java.util.Comparator.comparing((ServerPlayerEntry p) -> HubState.ignores(p.uuid())));
        for (ServerPlayerEntry player : players) {
            if (!needle.isEmpty() && !player.name().toLowerCase(Locale.ROOT).contains(needle)) {
                continue;
            }
            list.item(playerRow(screen, player, shown % 2 == 1));
            shown++;
        }
        if (shown == 0) {
            list.item(EdenParchment.text(tr(data.players().isEmpty() ? "no_players" : "no_matches"),
                    EdenParchment.INK_DIM, (int) PLAYERS_WIDTH));
        }
    }

    /**
     * Someone on this server, as the same card as a friend (green: they're here): the relation on the
     * second line; Add / Accept and the party invite on right-click.
     */
    private static PlayerCard playerRow(HubScreen screen, ServerPlayerEntry player, boolean alt) {
        Component relation = switch (player.relation()) {
            case ServerPlayerEntry.FRIEND -> tr("card.friend");
            case ServerPlayerEntry.OUTGOING -> tr("card.pending");
            case ServerPlayerEntry.INCOMING -> tr("card.incoming");
            default -> tr("status.on_server");
        };
        boolean canInvite = player.inviteState() == PartyManager.InviteState.OK.ordinal();
        Component hover = canInvite ? relation : relation.copy().append(" · ").append(inviteState(player.inviteState()));
        PlayerCard card = new PlayerCard(screen, player.uuid(), player.name(), FriendEntry.ON_SERVER, player.background(),
                player.profile(), relation, hover, PLAYERS_WIDTH, alt);
        switch (player.relation()) {
            case ServerPlayerEntry.NONE -> card.action(tr("menu.add"), () -> send(Action.ADD, player.uuid()), true);
            case ServerPlayerEntry.INCOMING -> {
                card.action(tr("menu.accept"), () -> send(Action.ACCEPT, player.uuid()), true);
                card.action(tr("decline"), () -> send(Action.DECLINE, player.uuid()), true);
            }
            case ServerPlayerEntry.OUTGOING -> card.action(tr("cancel_request"), () -> send(Action.CANCEL, player.uuid()), true);
            default -> { }
        }
        card.action(tr("menu.invite"),
                () -> ClientPlayNetworking.send(PartyAction.of(PartyAction.Action.INVITE, player.uuid())), canInvite);
        card.action(Ignoring.menuLabel(player.uuid()), () -> Ignoring.toggle(player.uuid()), true);
        return card;
    }

    /** Why a party invite can't be sent (a {@code PartyManager.InviteState} ordinal). */
    private static Component inviteState(int state) {
        return tr("invite_state." + PartyManager.InviteState.values()[
                Math.floorMod(state, PartyManager.InviteState.values().length)].name().toLowerCase(Locale.ROOT));
    }

    // ---------------------------------------------------------------- friends list

    private static Flex friendsColumn(HubScreen screen, FriendsData data, float width, int gridColumns) {
        Flex column = Flex.column().gap(3);
        column.sizing(Sizing.fixed(width + LANE), Sizing.fill());
        column.item(EdenParchment.text(tr("friends", data.friends().size()), EdenParchment.INK_MID));

        boolean hadFocus = friendSearchField != null && friendSearchField.isFocused();
        Flex list = Flex.column().gap(3);
        TextField search = new TextField(width, friendQuery, query -> {
            friendQuery = query;
            fillFriends(screen, list, data, width, gridColumns);
        });
        search.sizing(Sizing.fixed(width), Sizing.fixed(16));
        search.hint(tr("search_friends").getString());
        friendSearchField = search;
        column.item(search);

        if (!data.loaded()) {
            column.item(EdenParchment.text(tr("unavailable"), EdenParchment.WARN, (int) width));
        }

        ScrollPanel panel = HubScroll.panel("friends/friends", width + LANE, 100, list);
        panel.sizing(Sizing.fixed(width + LANE), Sizing.expand());
        column.item(panel);
        fillFriends(screen, list, data, width, gridColumns);

        if (hadFocus) {
            screen.canvas().focus(search);
        }
        return column;
    }

    /** The Ignored group's key, beside the presences: online friends the player ignores, before Offline. */
    private static final int IGNORED = -1;

    /**
     * Friends grouped under In game / Online / Ignored / Offline headings, {@code gridColumns} per line.
     * Ignored friends who are offline stay under Offline (marked).
     */
    private static void fillFriends(HubScreen screen, Flex list, FriendsData data, float width, int gridColumns) {
        list.clear();
        if (data.loaded() && data.friends().isEmpty()) {
            list.item(EdenParchment.text(tr("no_friends"), EdenParchment.INK_DIM, (int) width));
            return;
        }
        FriendQuery query = FriendQuery.parse(friendQuery);
        float rowWidth = (width - FRIEND_COLUMN_GAP * (gridColumns - 1)) / gridColumns;
        // Narrow cells (any grid, or one column squeezed by both side panels) go compact.
        boolean compact = rowWidth < 180;
        boolean any = false;
        // The server sends friends sorted by presence (on server, online, offline).
        for (int group : new int[]{FriendEntry.ON_SERVER, FriendEntry.ONLINE, IGNORED, FriendEntry.OFFLINE}) {
            java.util.List<FriendEntry> section = new java.util.ArrayList<>();
            for (FriendEntry friend : data.friends()) {
                if (group(friend) == group && query.matches(friend)) {
                    section.add(friend);
                }
            }
            if (section.isEmpty()) {
                continue;
            }
            // Room above each heading (more between sections), so the first one doesn't hug the search field.
            list.item(EdenParchment.spacer(any ? 6 : 3));
            any = true;
            // Each section folds (its heading is the toggle), and stays folded on this computer.
            String key = group == IGNORED ? "ignored" : String.valueOf(group);
            var fold = HubFolds.section(Component.literal(tr("section." + key, section.size()).getString()
                    .toUpperCase(Locale.ROOT)), "friends/" + key);
            fold.body().gap(3);
            list.item(fold);
            Flex line = null;
            for (int i = 0; i < section.size(); i++) {
                boolean alt = (i / gridColumns) % 2 == 1;
                PlayerCard row = friendRow(screen, section.get(i), rowWidth, alt, compact);
                if (gridColumns == 1) {
                    fold.body().item(row);
                    continue;
                }
                if (i % gridColumns == 0) {
                    line = Flex.row().gap(FRIEND_COLUMN_GAP);
                    fold.body().item(line);
                }
                line.item(row);
            }
        }
        if (!any && !data.friends().isEmpty()) {
            list.item(EdenParchment.text(tr("no_friend_matches"), EdenParchment.INK_DIM, (int) width));
        }
    }

    /** Which group a friend shows under: their presence, or Ignored while they're online. */
    private static int group(FriendEntry friend) {
        return friend.presence() != FriendEntry.OFFLINE && HubState.ignores(friend.uuid()) ? IGNORED : friend.presence();
    }

    /** A friend, Steam-style (see {@link PlayerCard}): no buttons; invite, ignore and remove are on right-click. */
    private static PlayerCard friendRow(HubScreen screen, FriendEntry friend, float width, boolean alt, boolean compact) {
        PlayerCard card = new PlayerCard(screen, friend.uuid(), friend.name(), friend.presence(), friend.background(),
                friend.profile(), compact ? compactStatusText(friend) : statusText(friend), hoverStatusText(friend), width, alt);
        if (friend.presence() == FriendEntry.ON_SERVER) {
            boolean ok = friend.inviteState() == PartyManager.InviteState.OK.ordinal();
            card.action(tr("menu.invite"),
                    () -> ClientPlayNetworking.send(PartyAction.of(PartyAction.Action.INVITE, friend.uuid())), ok);
        }
        card.action(Ignoring.menuLabel(friend.uuid()), () -> Ignoring.toggle(friend.uuid()), true);
        card.action(tr("remove"), () -> confirmRemove(screen, friend), true);
        return card;
    }

    private static Component statusText(FriendEntry friend) {
        return switch (friend.presence()) {
            case FriendEntry.ON_SERVER -> dimensionName(friend.dimension());
            case FriendEntry.ONLINE -> friend.playing().isEmpty()
                    ? tr("status.online")
                    : tr("status.playing", friend.playing());
            default -> friend.lastSeen() > 0
                    ? tr("status.last_seen", ago(friend.lastSeen()))
                    : tr("status.offline");
        };
    }

    /** The hover card's line: where they are on this server, what they play, or when they were seen. */
    private static Component hoverStatusText(FriendEntry friend) {
        return friend.presence() == FriendEntry.ON_SERVER
                ? tr("status.here_in", dimensionName(friend.dimension()))
                : statusText(friend);
    }

    /** Narrow grid cells: drop the words around the part that matters. */
    private static Component compactStatusText(FriendEntry friend) {
        return switch (friend.presence()) {
            case FriendEntry.ON_SERVER -> dimensionName(friend.dimension());
            case FriendEntry.ONLINE -> friend.playing().isEmpty()
                    ? tr("status.online")
                    : Component.literal(friend.playing());
            default -> friend.lastSeen() > 0
                    ? tr("status.ago", ago(friend.lastSeen()))
                    : tr("status.offline");
        };
    }

    /** "minecraft:the_nether" → a "dimension.minecraft.the_nether" translation if any mod ships one, else "The Nether". */
    static Component dimensionName(String id) {
        if (id.isEmpty()) {
            return tr("status.on_server");
        }
        net.minecraft.resources.ResourceLocation location = net.minecraft.resources.ResourceLocation.tryParse(id);
        if (location == null) {
            return Component.literal(id);
        }
        String key = "dimension." + location.getNamespace() + "." + location.getPath();
        if (net.minecraft.client.resources.language.I18n.exists(key)) {
            return Component.translatable(key);
        }
        String path = location.getPath();
        path = path.substring(path.lastIndexOf('/') + 1);
        StringBuilder pretty = new StringBuilder();
        for (String word : path.split("_")) {
            if (word.isEmpty()) {
                continue;
            }
            if (!pretty.isEmpty()) {
                pretty.append(' ');
            }
            pretty.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return Component.literal(pretty.toString());
    }

    /**
     * Friends search: words starting with {@code @} must match a modpack the friend is
     * playing (name or id, partial), the other words must all appear in the name.
     * "@garden mas" → ProstoMaslo while they play GardenMyth.
     */
    private record FriendQuery(java.util.List<String> names, java.util.List<String> packs) {
        static FriendQuery parse(String raw) {
            java.util.List<String> names = new java.util.ArrayList<>();
            java.util.List<String> packs = new java.util.ArrayList<>();
            for (String token : raw.trim().toLowerCase(Locale.ROOT).split("\\s+")) {
                if (token.startsWith("@")) {
                    if (token.length() > 1) {
                        packs.add(token.substring(1));
                    }
                } else if (!token.isEmpty()) {
                    names.add(token);
                }
            }
            return new FriendQuery(names, packs);
        }

        boolean matches(FriendEntry friend) {
            String name = friend.name().toLowerCase(Locale.ROOT);
            for (String part : names) {
                if (!name.contains(part)) {
                    return false;
                }
            }
            for (String pack : packs) {
                if (!friend.packSearch().contains(pack)) {
                    return false;
                }
            }
            return true;
        }
    }

    /** Coarse relative time: minutes, hours, then days. */
    private static Component ago(long epochMillis) {
        long minutes = Math.max(0, (System.currentTimeMillis() - epochMillis) / 60_000L);
        if (minutes < 1) {
            return tr("time.now");
        }
        if (minutes < 60) {
            return tr("time.minutes", minutes);
        }
        long hours = minutes / 60;
        if (hours < 24) {
            return tr("time.hours", hours);
        }
        return tr("time.days", hours / 24);
    }

    private static void confirmRemove(HubScreen screen, FriendEntry friend) {
        Flex dialog = Flex.column().gap(8).padding(Insets.of(8, 10, 8, 10)).alignItems(Align.CENTER);
        dialog.sizing(Sizing.fixed(180), Sizing.content());
        dialog.background(EdenParchment.panel());
        dialog.item(EdenParchment.text(tr("confirm_remove", friend.name()), EdenParchment.INK, 160));

        Modal[] modal = new Modal[1];
        Flex buttons = Flex.row().gap(6);
        buttons.item(EdenParchment.fitButton(tr("confirm_yes"), 70, 14, () -> {
            send(Action.REMOVE, friend.uuid());
            modal[0].close();
        }));
        buttons.item(EdenParchment.fitButton(tr("cancel"), 70, 14, () -> modal[0].close()));
        dialog.item(buttons);

        modal[0] = Modal.open(screen.canvas(), dialog);
        modal[0].closeOnClickOutside(true);
    }

    // ---------------------------------------------------------------- requests panel

    /** Left bar: hides/shows the "On this server" column. */
    private static Button playersBar(FriendsData data) {
        Button bar = new Button(BAR_WIDTH, 40, Component.literal(playersOpen ? "‹" : "›"), () -> {
            playersOpen = !playersOpen;
            if (net.minecraft.client.Minecraft.getInstance().screen instanceof HubScreen hub) {
                hub.onFriendsData();
            }
        });
        bar.sizing(Sizing.fixed(BAR_WIDTH), Sizing.fill());
        bar.tooltip(tr("players_toggle", data.players().size()));
        return bar;
    }

    /** Right bar: hides/shows the requests panel. */
    private static Button requestsBar(FriendsData data, boolean open) {
        int incoming = HubState.friendRequestCount();
        // Collapsed with requests waiting, the bar itself shows the count.
        String label = open ? "›" : incoming > 0 ? String.valueOf(incoming) : "‹";
        Button bar = new Button(BAR_WIDTH, 40, Component.literal(label), () -> {
            requestsOpen = !open;
            if (net.minecraft.client.Minecraft.getInstance().screen instanceof HubScreen hub) {
                hub.onFriendsData();
            }
        });
        bar.sizing(Sizing.fixed(BAR_WIDTH), Sizing.fill());
        bar.tooltip(tr("requests_toggle", incoming, data.outgoing().size()));
        return bar;
    }

    private static Flex requestsColumn(FriendsData data) {
        Flex list = Flex.column().gap(2);
        float panelWidth = REQUESTS_WIDTH - 6;
        float rowWidth = panelWidth - LANE;

        list.item(EdenParchment.text(tr("incoming", data.incoming().size()), EdenParchment.INK));
        // Requests from ignored players wait last, marked (they don't count toward the badge).
        java.util.List<RequestEntry> requests = new java.util.ArrayList<>(data.incoming());
        requests.sort(java.util.Comparator.comparing((RequestEntry r) -> HubState.ignores(r.uuid())));
        for (RequestEntry request : requests) {
            boolean ignored = HubState.ignores(request.uuid());
            Flex row = Flex.row().gap(2).alignItems(Align.CENTER);
            row.item(PlayerFace.of(request.uuid(), request.name(), 10));
            Label name = Label.of(rowWidth - 10 - 14 * 2 - 6, ignored
                    ? Component.literal(Ignoring.MARK).append(PlayerNames.of(request.uuid(), request.name()))
                    : PlayerNames.of(request.uuid(), request.name()));
            name.color(ignored ? EdenParchment.INK_DIM : EdenParchment.INK);
            name.pickable(true);
            name.tooltip(tr("sent_ago", ago(request.sentAt())));
            row.item(name);
            Button accept = new Button(14, 12, Component.literal("✔"),
                    () -> send(Action.ACCEPT, request.uuid()));
            accept.tooltip(tr("accept"));
            row.item(accept);
            Button decline = new Button(14, 12, Component.literal("✖"),
                    () -> send(Action.DECLINE, request.uuid()));
            decline.tooltip(tr("decline"));
            row.item(decline);
            list.item(row);
        }

        list.item(EdenParchment.spacer(2));
        list.item(EdenParchment.text(tr("outgoing", data.outgoing().size()), EdenParchment.INK_MID));
        for (RequestEntry request : data.outgoing()) {
            Flex row = Flex.row().gap(2).alignItems(Align.CENTER);
            row.item(PlayerFace.of(request.uuid(), request.name(), 10));
            Label name = Label.of(rowWidth - 10 - 14 - 4, PlayerNames.of(request.uuid(), request.name()));
            name.color(EdenParchment.INK_DIM);
            name.pickable(true);
            name.tooltip(tr("sent_ago", ago(request.sentAt())));
            row.item(name);
            Button cancel = new Button(14, 12, Component.literal("✖"),
                    () -> send(Action.CANCEL, request.uuid()));
            cancel.tooltip(tr("cancel_request"));
            row.item(cancel);
            list.item(row);
        }

        ScrollPanel panel = HubScroll.panel("friends/requests", panelWidth, 100, list);
        panel.sizing(Sizing.fixed(panelWidth), Sizing.fill());
        Flex column = Flex.column().padding(Insets.of(3, 3, 3, 3));
        column.sizing(Sizing.fixed(REQUESTS_WIDTH), Sizing.fill());
        column.background(EdenParchment.framed(EdenParchment.ROW_BG, EdenParchment.HAIRLINE));
        column.item(panel);
        return column;
    }
}
