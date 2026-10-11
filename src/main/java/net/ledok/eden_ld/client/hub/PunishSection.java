package net.ledok.eden_ld.client.hub;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.ledok.eden_ld.hub.HubPayloads;
import net.ledok.eden_ld.hub.HubPayloads.PunishPage;
import net.ledok.eden_ld.hub.HubPayloads.PunishPlayer;
import net.ledok.eden_ld.hub.HubPayloads.PunishView;
import net.ledok.vectorlib.client.canvas.Shapes;
import net.ledok.vectorlib.client.canvas.Stroke;
import net.ledok.vectorlib.client.canvas.TextNode;
import net.ledok.vectorlib.client.canvas.layout.Align;
import net.ledok.vectorlib.client.canvas.layout.Flex;
import net.ledok.vectorlib.client.canvas.layout.Insets;
import net.ledok.vectorlib.client.canvas.layout.Justify;
import net.ledok.vectorlib.client.canvas.layout.Sizing;
import net.ledok.vectorlib.client.canvas.widget.Button;
import net.ledok.vectorlib.client.canvas.widget.Modal;
import net.ledok.vectorlib.client.canvas.widget.ScrollPanel;
import net.ledok.vectorlib.client.canvas.widget.TextField;
import net.ledok.vectorlib.client.canvas.widget.Toggle;
import net.ledok.vectorlib.client.canvas.widget.UiSounds;
import net.ledok.vectorlib.client.canvas.widget.Widget;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

import static net.ledok.eden_ld.client.hub.ModerationScreen.BAD;
import static net.ledok.eden_ld.client.hub.ModerationScreen.DIM;
import static net.ledok.eden_ld.client.hub.ModerationScreen.GOOD;
import static net.ledok.eden_ld.client.hub.ModerationScreen.LINE;
import static net.ledok.eden_ld.client.hub.ModerationScreen.ACCENT;
import static net.ledok.eden_ld.client.hub.ModerationScreen.PANEL;
import static net.ledok.eden_ld.client.hub.ModerationScreen.TEXT;
import static net.ledok.eden_ld.client.hub.ModerationScreen.small;
import static net.ledok.eden_ld.client.hub.ModerationScreen.text;

/**
 * The Moderation window's BanHammer sections. <b>Players</b>: search everyone who has joined, and a
 * player's page — punish (ban, IP ban, mute, warn, kick; permanent or for a while, with a reason,
 * optionally silent), lift active punishments, and their history. Only what the admin's BanHammer
 * nodes allow is enabled. <b>Punishments</b>: everyone's latest punishments.
 */
final class PunishSection {

    private static final float LIST = 140;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());
    /** The kinds of punishment the form offers, as BanHammer names them. */
    private static final List<String> KINDS = List.of("ban", "ipban", "mute", "warn", "kick");
    private static final String[][] PRESETS = {{"1h", "1h"}, {"1d", "1d"}, {"7d", "7d"}, {"30d", "30d"}};
    static final String PUNISH = "punish";

    private final ModerationScreen screen;
    private final float width;

    private String query = "";
    @Nullable
    private HubPayloads.PunishPlayers results;
    @Nullable
    private UUID selected;
    private String selectedName = "";
    private boolean selectedOnline;
    private long selectedLastSeen;
    /** The open tab of the player page: {@link #PUNISH} or an {@code AdminTools} area. */
    @Nullable
    private String tab;
    /** The player's data per admin tab, as the server last sent it. */
    private final java.util.Map<String, net.minecraft.nbt.CompoundTag> tabData = new java.util.HashMap<>();
    @Nullable
    private PunishPage page;
    @Nullable
    private HubPayloads.PunishLog log;

    private String kind = "ban";
    /** Blank: permanent. */
    private String duration = "";
    private String reason = "";
    private boolean silent;

    @Nullable
    private Flex resultsBox;
    @Nullable
    private Flex pageBox;

    PunishSection(ModerationScreen screen, float width) {
        this.screen = screen;
        this.width = width;
    }

    // ---------------------------------------------------------------- server answers

    void onPlayers(HubPayloads.PunishPlayers players) {
        if (!players.query().equals(query)) {
            return;
        }
        results = players;
        fillResults();
        screen.relayout();
    }

    void onPage(PunishPage page) {
        if (!page.target().equals(selected)) {
            return;
        }
        this.page = page;
        selectedOnline = page.online();
        fillPage();
        screen.relayout();
    }

    /** An admin tab's data for the selected player. */
    void onAdminData(HubPayloads.AdminData data) {
        if (!data.target().equals(selected)) {
            return;
        }
        tabData.put(data.area(), data.data());
        if (data.area().equals(tab)) {
            fillPage();
            screen.relayout();
        }
    }

    /** The tabs this staff member has, in order. */
    private List<String> tabs() {
        int staff = screen.staff();
        List<String> tabs = new java.util.ArrayList<>();
        if ((staff & HubPayloads.HomeData.STAFF_PUNISH) != 0) {
            tabs.add(PUNISH);
        }
        if ((staff & (HubPayloads.HomeData.STAFF_THEMES | HubPayloads.HomeData.STAFF_BACKGROUNDS)) != 0) {
            tabs.add(net.ledok.eden_ld.hub.moderation.AdminTools.UNLOCKS);
        }
        if ((staff & HubPayloads.HomeData.STAFF_DAILIES) != 0) {
            tabs.add(net.ledok.eden_ld.hub.moderation.AdminTools.DAILIES);
        }
        if ((staff & HubPayloads.HomeData.STAFF_DUNGEONS) != 0) {
            tabs.add(net.ledok.eden_ld.hub.moderation.AdminTools.DUNGEONS);
        }
        if ((staff & HubPayloads.HomeData.STAFF_EMOJIS) != 0) {
            tabs.add(net.ledok.eden_ld.hub.moderation.AdminTools.EMOJIS);
        }
        return tabs;
    }

    private void openTab(String next) {
        tab = next;
        if (selected != null && !PUNISH.equals(next)) {
            PlayerTabs.request(next, selected);
        }
        fillPage();
        screen.relayout();
    }

    void onLog(HubPayloads.PunishLog log) {
        this.log = log;
    }

    /** Opens a player's page (from the log, or a click in the list). */
    void select(UUID player) {
        selected = player;
        page = null;
        tabData.clear();
        List<String> tabs = tabs();
        if (tab == null || !tabs.contains(tab)) {
            tab = tabs.isEmpty() ? null : tabs.get(0);
        }
        if (tabs.contains(PUNISH)) {
            ClientPlayNetworking.send(new HubPayloads.PunishPageRequest(player));
        }
        if (tab != null && !PUNISH.equals(tab)) {
            PlayerTabs.request(tab, player);
        }
    }

    void requestResults() {
        ClientPlayNetworking.send(new HubPayloads.PunishSearch(query));
    }

    // ---------------------------------------------------------------- players

    void buildPlayers(Flex main) {
        Flex columns = Flex.row().gap(8);
        columns.sizing(Sizing.fill(), Sizing.fill());

        Flex left = Flex.column().gap(4);
        left.sizing(Sizing.fixed(LIST), Sizing.fill());
        TextField search = new TextField(LIST, query, value -> {
            query = value;
            ClientPlayNetworking.send(new HubPayloads.PunishSearch(value));
        });
        search.size(LIST, 16);
        search.hint(Component.translatable("gui.eden_ld.moderation.search_players").getString());
        search.maxLength(16);
        left.item(search);
        resultsBox = Flex.column().gap(2);
        ScrollPanel list = new ScrollPanel(LIST, 100, resultsBox);
        list.sizing(Sizing.fixed(LIST), Sizing.expand());
        left.item(list);
        columns.item(left);

        pageBox = Flex.column().gap(4);
        pageBox.sizing(Sizing.expand(), Sizing.fill());
        columns.item(pageBox);
        main.item(columns);
        fillResults();
        fillPage();
    }

    private void fillResults() {
        if (resultsBox == null) {
            return;
        }
        resultsBox.clear();
        if (results == null) {
            resultsBox.item(text(Component.translatable("gui.eden_ld.hub.home.loading"), DIM));
            return;
        }
        if (results.players().isEmpty()) {
            resultsBox.item(text(Component.translatable(query.isBlank()
                    ? "gui.eden_ld.moderation.nobody_online" : "gui.eden_ld.moderation.nobody_found"), DIM, (int) LIST - 8));
        }
        for (PunishPlayer player : results.players()) {
            resultsBox.item(new PlayerRow(player));
        }
    }

    private float pageWidth() {
        return width - LIST - 8;
    }

    private void fillPage() {
        if (pageBox == null) {
            return;
        }
        pageBox.clear();
        float w = pageWidth();
        if (selected == null || tab == null) {
            pageBox.item(text(Component.translatable("gui.eden_ld.moderation.pick_player"), DIM, (int) w));
            return;
        }
        PunishPage p = PUNISH.equals(tab) ? page : null;

        Flex header = Flex.row().gap(6).alignItems(Align.CENTER);
        header.sizing(Sizing.fill(), Sizing.fixed(16));
        header.item(text(Component.literal(page != null ? page.name() : selectedName), TEXT));
        header.item(text(selectedOnline ? Component.translatable("gui.eden_ld.moderation.online")
                        : selectedLastSeen > 0 ? ago(selectedLastSeen) : Component.translatable("gui.eden_ld.moderation.offline"),
                selectedOnline ? GOOD : DIM));
        if (p != null) {
            long warns = p.active().stream().filter(v -> v.type().equals("warn")).count();
            if (warns > 0) {
                header.item(text(Component.translatable("gui.eden_ld.moderation.warnings", warns), 0xFFE0C850));
            }
        }
        Flex spacer = Flex.row();
        spacer.sizing(Sizing.expand(), Sizing.fixed(1));
        header.item(spacer);
        if (p != null && p.may("pardon") && !p.active().isEmpty()) {
            header.item(small("gui.eden_ld.moderation.pardon", 50, () -> confirm(
                    Component.translatable("gui.eden_ld.moderation.confirm_pardon", p.name()), "pardon", "", "")));
        }
        pageBox.item(header);

        List<String> tabs = tabs();
        if (tabs.size() > 1) {
            // Wraps onto a second line when the labels (Ukrainian) don't fit.
            List<Widget> buttons = new java.util.ArrayList<>();
            for (String t : tabs) {
                Button button = small("gui.eden_ld.moderation.tab." + t, 40, () -> openTab(t));
                if (t.equals(tab)) {
                    button.style(ModerationScreen.SELECTED);
                }
                buttons.add(button);
            }
            pageBox.item(ModerationScreen.wrapped(buttons, w, 3));
        }

        if (!PUNISH.equals(tab)) {
            net.minecraft.nbt.CompoundTag data = tabData.get(tab);
            if (data == null) {
                pageBox.item(text(Component.translatable("gui.eden_ld.hub.home.loading"), DIM));
                return;
            }
            Flex content = Flex.column().gap(3);
            float inner = w - 10;
            switch (tab) {
                case net.ledok.eden_ld.hub.moderation.AdminTools.UNLOCKS -> PlayerTabs.unlocks(content, data, selected, inner);
                case net.ledok.eden_ld.hub.moderation.AdminTools.DAILIES ->
                        PlayerTabs.dailies(screen, content, data, selected, inner, selectedName);
                case net.ledok.eden_ld.hub.moderation.AdminTools.DUNGEONS ->
                        PlayerTabs.dungeons(screen, content, data, selected, inner, selectedName);
                case net.ledok.eden_ld.hub.moderation.AdminTools.EMOJIS -> PlayerTabs.emojis(screen, content, data, selected, inner);
                default -> {
                }
            }
            // Kept across the rebuild each Unlock/Lock (and every other action) brings, per tab.
            ScrollPanel scroll = HubScroll.panel("moderation/player/" + tab, w, 100, content);
            scroll.sizing(Sizing.fill(), Sizing.expand());
            pageBox.item(scroll);
            return;
        }
        if (p == null) {
            pageBox.item(text(Component.translatable("gui.eden_ld.hub.home.loading"), DIM));
            return;
        }
        pageBox.item(form(p, w));

        Flex records = Flex.column().gap(2);
        records.item(text(Component.translatable("gui.eden_ld.moderation.active", p.active().size()), DIM));
        if (p.active().isEmpty()) {
            records.item(text(Component.translatable("gui.eden_ld.moderation.none"), DIM));
        }
        for (PunishView view : p.active()) {
            Flex row = Flex.row().gap(4).alignItems(Align.CENTER);
            row.item(new PunishRow(view, w - 70, false));
            String lift = liftCommand(view.type());
            Button button = small("gui.eden_ld.moderation.lift." + view.type(), 56, () -> confirm(
                    Component.translatable("gui.eden_ld.moderation.confirm_lift." + view.type(), p.name()), lift, "", ""));
            button.enabled(lift != null && p.may(lift));
            if (view.type().equals("warn")) {
                button.tooltip(Component.translatable("gui.eden_ld.moderation.lift_warn_hint"));
            }
            row.item(button);
            records.item(row);
        }
        records.item(ModerationScreen.gap(4));
        records.item(text(Component.translatable("gui.eden_ld.moderation.history"), DIM));
        if (!p.historyKept()) {
            records.item(text(Component.translatable("gui.eden_ld.moderation.no_history"), DIM, (int) w - 12));
        } else if (p.history().isEmpty()) {
            records.item(text(Component.translatable("gui.eden_ld.moderation.none"), DIM));
        }
        for (PunishView view : p.history()) {
            records.item(new PunishRow(view, w - 12, false));
        }
        ScrollPanel scroll = new ScrollPanel(w, 100, records);
        scroll.sizing(Sizing.fill(), Sizing.expand());
        pageBox.item(scroll);
    }

    /** The punish form: kind, duration, reason, silent, apply. */
    private Flex form(PunishPage p, float w) {
        Flex box = Flex.column().gap(4).padding(Insets.of(5, 6, 5, 6));
        box.sizing(Sizing.fill(), Sizing.content());
        box.background(r -> Shapes.rect(0, 0, r.width(), r.height()).fill(PANEL).stroke(Stroke.of(LINE, 1)));

        List<Widget> kinds = new java.util.ArrayList<>();
        for (String k : KINDS) {
            Button button = small("gui.eden_ld.moderation.kind." + k, 44, () -> {
                kind = k;
                if (!allowsPermanent(p) && duration.isBlank()) {
                    duration = "1d";
                }
                fillPage();
                screen.relayout();
            });
            boolean possible = p.may(permanent(k)) || (temporary(k) != null && p.may(temporary(k)));
            if (k.equals("kick")) {
                possible &= p.online();
            }
            button.enabled(possible);
            if (k.equals(kind)) {
                button.style(ModerationScreen.SELECTED);
            }
            kinds.add(button);
        }
        box.item(ModerationScreen.wrapped(kinds, w - 12, 3));

        if (!kind.equals("kick")) {
            List<Widget> times = new java.util.ArrayList<>();
            Button forever = small("gui.eden_ld.moderation.permanent", 50, () -> {
                duration = "";
                fillPage();
                screen.relayout();
            });
            forever.enabled(allowsPermanent(p));
            if (duration.isBlank()) {
                forever.style(ModerationScreen.SELECTED);
            }
            times.add(forever);
            boolean temp = temporary(kind) != null && p.may(temporary(kind));
            for (String[] preset : PRESETS) {
                Button button = new Button(26, 14, Component.literal(preset[0]), () -> {
                    duration = preset[1];
                    fillPage();
                    screen.relayout();
                });
                button.sizing(Sizing.fixed(26), Sizing.fixed(14));
                button.enabled(temp);
                if (duration.equals(preset[1])) {
                    button.style(ModerationScreen.SELECTED);
                }
                times.add(button);
            }
            TextField custom = new TextField(46, isPreset(duration) ? "" : duration, value -> duration = value.trim());
            custom.size(46, 14);
            custom.hint("2d12h");
            custom.maxLength(12);
            custom.tooltip(Component.translatable("gui.eden_ld.moderation.duration_hint"));
            times.add(custom);
            box.item(ModerationScreen.wrapped(times, w - 12, 3));
        }

        TextField why = new TextField(w - 12, reason, value -> reason = value);
        why.size(w - 12, 16);
        why.hint(Component.translatable("gui.eden_ld.moderation.reason_hint").getString());
        why.maxLength(200);
        box.item(why);

        Flex actions = Flex.row().gap(4).alignItems(Align.CENTER);
        actions.sizing(Sizing.fill(), Sizing.content());
        Toggle quiet = new Toggle(120, Component.translatable("gui.eden_ld.moderation.silent"), silent, value -> silent = value);
        quiet.tooltip(Component.translatable("gui.eden_ld.moderation.silent_hint"));
        actions.item(quiet);
        Flex spacer = Flex.row();
        spacer.sizing(Sizing.expand(), Sizing.fixed(1));
        actions.item(spacer);
        Button apply = small("gui.eden_ld.moderation.apply", 70, () -> {
            String command = command(p);
            if (command == null) {
                return;
            }
            Component what = command.startsWith("temp")
                    ? Component.translatable("gui.eden_ld.moderation.confirm_temp." + kind, p.name(), duration)
                    : Component.translatable("gui.eden_ld.moderation.confirm_kind." + kind, p.name());
            confirm(what, command, duration, reason);
        });
        apply.enabled(command(p) != null);
        actions.item(apply);
        box.item(actions);
        return box;
    }

    /** The command the form would run now, or null when the admin may not (or the duration is missing). */
    @Nullable
    private String command(PunishPage p) {
        if (kind.equals("kick")) {
            return p.may("kick") && p.online() ? "kick" : null;
        }
        if (duration.isBlank()) {
            return p.may(permanent(kind)) ? permanent(kind) : null;
        }
        String temp = temporary(kind);
        return temp != null && p.may(temp) ? temp : null;
    }

    private boolean allowsPermanent(PunishPage p) {
        return p.may(permanent(kind));
    }

    private static boolean isPreset(String value) {
        for (String[] preset : PRESETS) {
            if (preset[1].equals(value)) {
                return true;
            }
        }
        return false;
    }

    private static String permanent(String kind) {
        return switch (kind) {
            case "ipban" -> "ban-ip";
            default -> kind;
        };
    }

    @Nullable
    private static String temporary(String kind) {
        return switch (kind) {
            case "ban" -> "tempban";
            case "ipban" -> "tempban-ip";
            case "mute" -> "tempmute";
            case "warn" -> "tempwarn";
            default -> null;
        };
    }

    @Nullable
    private static String liftCommand(String type) {
        return switch (type) {
            case "ban" -> "unban";
            case "ipban" -> "unban-ip";
            case "mute" -> "unmute";
            case "warn" -> "unwarn";
            default -> null;
        };
    }

    private void confirm(Component question, String command, String duration, String reason) {
        UUID target = selected;
        if (target == null) {
            return;
        }
        Flex dialog = Flex.column().gap(6).padding(Insets.of(8, 10, 8, 10));
        dialog.sizing(Sizing.fixed(240), Sizing.content());
        dialog.background(r -> Shapes.rect(0, 0, r.width(), r.height()).fill(PANEL).stroke(Stroke.of(LINE, 1)));
        dialog.item(text(question, TEXT, 220));
        if (!reason.isBlank()) {
            dialog.item(text(Component.translatable("gui.eden_ld.moderation.reason", reason.trim()), DIM, 220));
        }
        if (silent) {
            dialog.item(text(Component.translatable("gui.eden_ld.moderation.silent"), DIM, 220));
        }
        Modal[] modal = new Modal[1];
        Flex buttons = Flex.row().gap(6).justify(Justify.END);
        buttons.sizing(Sizing.fill(), Sizing.content());
        buttons.item(new Button(70, 16, Component.translatable("gui.eden_ld.moderation.apply"), () -> {
            ClientPlayNetworking.send(new HubPayloads.PunishAction(target, command, duration, reason, silent));
            if (!command.startsWith("un") && !command.equals("pardon")) {
                this.reason = "";
            }
            modal[0].close();
        }));
        buttons.item(new Button(60, 16, Component.translatable("gui.eden_ld.moderation.cancel"), () -> modal[0].close()));
        dialog.item(buttons);
        modal[0] = Modal.open(screen.canvas(), dialog);
        modal[0].closeOnClickOutside(true);
    }

    // ---------------------------------------------------------------- log

    void buildLog(Flex main) {
        main.item(text(Component.translatable("gui.eden_ld.moderation.section.punish_log"), TEXT));
        if (log == null) {
            main.item(text(Component.translatable("gui.eden_ld.hub.home.loading"), DIM));
            return;
        }
        Flex list = Flex.column().gap(2);
        if (!log.historyKept()) {
            list.item(text(Component.translatable("gui.eden_ld.moderation.no_history"), DIM, (int) width - 12));
        } else if (log.entries().isEmpty()) {
            list.item(text(Component.translatable("gui.eden_ld.moderation.none"), DIM));
        }
        for (PunishView view : log.entries()) {
            list.item(new PunishRow(view, width - 12, true));
        }
        ScrollPanel scroll = new ScrollPanel(width, 100, list);
        scroll.sizing(Sizing.fill(), Sizing.expand());
        main.item(scroll);
    }

    // ---------------------------------------------------------------- rows

    static int color(String type) {
        return switch (type) {
            case "ban", "ipban" -> BAD;
            case "mute" -> 0xFFE09040;
            case "warn" -> 0xFFE0C850;
            default -> DIM;
        };
    }

    static String length(long seconds) {
        if (seconds < 0) {
            return "∞";
        }
        long days = seconds / 86_400, hours = seconds / 3_600 % 24, minutes = seconds / 60 % 60;
        StringBuilder out = new StringBuilder();
        if (days > 0) {
            out.append(days).append('d');
        }
        if (hours > 0) {
            out.append(hours).append('h');
        }
        if (minutes > 0 && days == 0) {
            out.append(minutes).append('m');
        }
        return out.isEmpty() ? seconds + "s" : out.toString();
    }

    /** "5 min ago", "3 h ago", "12 d ago". */
    static Component ago(long epochMillis) {
        long minutes = Math.max(0, (System.currentTimeMillis() - epochMillis) / 60_000);
        if (minutes < 60) {
            return Component.translatable("gui.eden_ld.moderation.ago_minutes", Math.max(1, minutes));
        }
        if (minutes < 60 * 48) {
            return Component.translatable("gui.eden_ld.moderation.ago_hours", minutes / 60);
        }
        return Component.translatable("gui.eden_ld.moderation.ago_days", minutes / (60 * 24));
    }

    /** One player in the search list; a green dot when online, else when they were last here. */
    private final class PlayerRow extends Widget {
        private final PunishPlayer player;

        PlayerRow(PunishPlayer player) {
            super(LIST - 8, 16);
            this.player = player;
            sizing(Sizing.fixed(LIST - 8), Sizing.fixed(16));
        }

        @Override
        protected void rebuild() {
            boolean on = player.uuid().equals(selected);
            add(Shapes.rect(0, 0, width, height).fill(on ? PANEL : 0xFF22252A)
                    .stroke(Stroke.of(on ? ACCENT : hovered ? DIM : LINE, 1)));
            add(Shapes.circle(8, 8, 2.5f).fill(player.online() ? GOOD : 0xFF555A62));
            Component ago = player.online() || player.lastSeen() <= 0 ? null : ago(player.lastSeen());
            int agoWidth = ago == null ? 0 : Minecraft.getInstance().font.width(ago);
            add(TextNode.of(Component.literal(player.name())).at(15, 4).maxWidth((int) width - 22 - agoWidth)
                    .ellipsis(true).color(TEXT).shadow(false));
            if (ago != null) {
                add(TextNode.of(ago).at(width - 4, 4).align(TextNode.Align.RIGHT).color(DIM).shadow(false));
            }
        }

        @Override
        public boolean onMouseDown(float x, float y, int button) {
            if (button != 0) {
                return false;
            }
            UiSounds.click();
            selectedName = player.name();
            selectedOnline = player.online();
            selectedLastSeen = player.lastSeen();
            select(player.uuid());
            fillResults();
            fillPage();
            screen.relayout();
            return true;
        }

        @Override
        public Cursor cursor() {
            return Cursor.HAND;
        }
    }

    /**
     * A punishment: kind, when, for how long, by whom, and the reason. In the log it names the
     * player too, and a click opens their page.
     */
    private final class PunishRow extends Widget {
        private final PunishView view;
        private final boolean log;

        PunishRow(PunishView view, float w, boolean log) {
            super(w, 24);
            this.view = view;
            this.log = log;
            sizing(Sizing.fixed(w), Sizing.fixed(24));
            tooltip(() -> view.reason().isBlank() ? List.of() : List.of(Component.literal(view.reason())));
        }

        @Override
        protected void rebuild() {
            add(Shapes.rect(0, 0, width, height).fill(0xFF22252A).stroke(Stroke.of(log && hovered ? DIM : LINE, 1)));
            add(Shapes.rect(0, 0, 2, height).fill(color(view.type())));
            Component kind = Component.translatable("gui.eden_ld.moderation.kind." + view.type())
                    .withStyle(s -> s.withColor(color(view.type()) & 0xFFFFFF));
            String when = DATE.format(Instant.ofEpochSecond(view.time()));
            Component info = view.type().equals("kick")
                    ? Component.translatable("gui.eden_ld.moderation.row_kick", when, view.admin())
                    : view.permanent()
                    ? Component.translatable("gui.eden_ld.moderation.row_permanent", when, view.admin())
                    : Component.translatable("gui.eden_ld.moderation.row_temp", when, length(view.duration()), view.admin());
            // One line: kind (and in the log, the player), then when / how long / by whom, dimmed.
            net.minecraft.network.chat.MutableComponent head = Component.empty().append(kind);
            if (log) {
                head.append(Component.literal("  " + view.playerName()).withStyle(s -> s.withColor(TEXT & 0xFFFFFF)));
            }
            head.append(Component.literal("   ").append(info).withStyle(s -> s.withColor(DIM & 0xFFFFFF)));
            add(TextNode.of(head).at(6, 3).maxWidth((int) width - 10).ellipsis(true).color(TEXT).shadow(false));
            add(TextNode.of(Component.literal(view.reason())).at(6, 13).maxWidth((int) width - 10).ellipsis(true)
                    .color(0xFFC8CCD2).shadow(false));
        }

        @Override
        public boolean onMouseDown(float x, float y, int button) {
            if (!log || button != 0) {
                return false;
            }
            UiSounds.click();
            screen.openPlayer(view.player(), view.playerName());
            return true;
        }

        @Override
        public Cursor cursor() {
            return log ? Cursor.HAND : Cursor.DEFAULT;
        }
    }

    /** From the log: show this player in the Players section. */
    void showPlayer(UUID player, String name) {
        selectedName = name;
        selectedOnline = false;
        selectedLastSeen = 0;
        query = name;
        results = new HubPayloads.PunishPlayers(name, List.of(new PunishPlayer(player, name, false, 0)));
        requestResults();
        select(player);
    }
}
