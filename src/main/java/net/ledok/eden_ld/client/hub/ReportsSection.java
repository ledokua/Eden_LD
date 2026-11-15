package net.ledok.eden_ld.client.hub;

import net.ledok.eden_ld.hub.moderation.AdminTools;
import net.ledok.vectorlib.client.canvas.Shapes;
import net.ledok.vectorlib.client.canvas.Stroke;
import net.ledok.vectorlib.client.canvas.TextNode;
import net.ledok.vectorlib.client.canvas.layout.Align;
import net.ledok.vectorlib.client.canvas.layout.Flex;
import net.ledok.vectorlib.client.canvas.layout.Insets;
import net.ledok.vectorlib.client.canvas.layout.Sizing;
import net.ledok.vectorlib.client.canvas.widget.Button;
import net.ledok.vectorlib.client.canvas.widget.ScrollPanel;
import net.ledok.vectorlib.client.canvas.widget.TextField;
import net.ledok.vectorlib.client.canvas.widget.UiSounds;
import net.ledok.vectorlib.client.canvas.widget.Widget;
import net.minecraft.Util;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

import static net.ledok.eden_ld.client.hub.ModerationScreen.ACCENT;
import static net.ledok.eden_ld.client.hub.ModerationScreen.DIM;
import static net.ledok.eden_ld.client.hub.ModerationScreen.GOOD;
import static net.ledok.eden_ld.client.hub.ModerationScreen.LINE;
import static net.ledok.eden_ld.client.hub.ModerationScreen.PANEL;
import static net.ledok.eden_ld.client.hub.ModerationScreen.TEXT;
import static net.ledok.eden_ld.client.hub.ModerationScreen.small;
import static net.ledok.eden_ld.client.hub.ModerationScreen.text;

/**
 * The Moderation window's <b>Reports</b> section ({@code eden_ld.admin.reports}): players' reports other mods file
 * (Duels_LD's wager disputes, …) and players' own (the hub's «Звернення»: bugs, complaints, ideas). The list, newest
 * first, filtered by status and kind; the picked report's text, its log (evidence), the reply the player sees, a staff
 * note, the status, Save, and [Open in editor] for a mod's suggestion. The server keeps what each admin looks at (filter, pick), so
 * every answer is the whole view ({@code ReportDesk}).
 */
final class ReportsSection {

    private static final float LIST = 190;
    private static final float ROW_H = 24;
    /** The server's status filters ({@code ReportDesk}): to do, accepted, rejected, all, new, being looked at. */
    private static final java.util.List<Integer> FILTERS = java.util.List.of(0, 4, 5, 1, 2, 3);
    /** Kind filters: all, bugs, complaints, ideas, other, mods' reports and suggestions. */
    private static final java.util.List<Integer> KINDS = java.util.List.of(0, 1, 2, 3, 4, 5);
    private static final int[] STATUS_COLORS = {0xFF6FA8DC, 0xFFE0C850, 0xFF6CC070, 0xFFE06A5A};
    private static final int WARN = 0xFFE0C850;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZoneId.systemDefault());

    private final ModerationScreen screen;
    private final float width;
    @Nullable
    private CompoundTag data;
    /** The staff note as typed, for the report {@link #noteFor}; kept over the server's until sent. */
    private String note = "";
    private long noteFor = -1;
    private boolean noteDirty;
    /** The reply to the player and the status, as picked (sent with Save). */
    private String reply = "";
    private int status;
    /** The message being written (a reply or a note), and the list's player filter. */
    private String message = "";
    private String search = "";

    ReportsSection(ModerationScreen screen, float width) {
        this.screen = screen;
        this.width = width;
    }

    private static Component tr(String key, Object... args) {
        return Component.translatable("gui.eden_ld.moderation.reports." + key, args);
    }

    void request() {
        PlayerTabs.request(AdminTools.REPORTS, Util.NIL_UUID);
    }

    /** Returns whether it was this section's data. */
    boolean onData(String area, CompoundTag tag) {
        if (!area.equals(AdminTools.REPORTS)) {
            return false;
        }
        data = tag;
        CompoundTag detail = tag.getCompound("detail");
        long id = tag.contains("detail", Tag.TAG_COMPOUND) ? detail.getLong("id") : -1;
        if (id != noteFor || !noteDirty) {
            noteFor = id;
            note = detail.getString("note");
            reply = detail.getString("reply");
            status = detail.getInt("status");
            message = "";
            noteDirty = false;
        }
        return true;
    }

    /** What the admin looks at (filters and / or pick); the server answers with the view. */
    private void view(@Nullable Integer filter, @Nullable Long id) {
        view(filter, null, id);
    }

    private void view(@Nullable Integer filter, @Nullable Integer kind, @Nullable Long id) {
        CompoundTag args = new CompoundTag();
        if (filter != null) {
            args.putInt("filter", filter);
        }
        if (kind != null) {
            args.putInt("kind", kind);
        }
        if (id != null) {
            args.putLong("id", id);
        }
        PlayerTabs.action(AdminTools.REPORTS, Util.NIL_UUID, "view", args);
    }

    private void send(String action, long id) {
        CompoundTag args = new CompoundTag();
        args.putLong("id", id);
        args.putString("note", note);
        args.putString("reply", reply);
        args.putInt("status", status);
        noteDirty = false;
        PlayerTabs.action(AdminTools.REPORTS, Util.NIL_UUID, action, args);
    }

    // ---------------------------------------------------------------- layout

    void build(Flex main) {
        Flex header = Flex.row().gap(4).alignItems(Align.CENTER);
        header.sizing(Sizing.fill(), Sizing.content());
        header.item(text(Component.translatable("gui.eden_ld.moderation.section.reports"), TEXT));
        header.item(PlayerTabs.spacer());
        if (data != null) {
            int open = data.getInt("open");
            header.item(new net.ledok.vectorlib.client.canvas.widget.Dropdown<>(110, FILTERS, data.getInt("filter"),
                    value -> value == 0 ? tr("filter.0", open) : tr("filter." + value), value -> view(value, null, null)));
            header.item(new net.ledok.vectorlib.client.canvas.widget.Dropdown<>(110, KINDS, data.getInt("kind"),
                    value -> tr("kind." + value), value -> view(null, value, null)));
        }
        main.item(header);
        if (data == null) {
            main.item(text(Component.translatable("gui.eden_ld.hub.home.loading"), DIM));
            return;
        }

        Flex columns = Flex.row().gap(8);
        columns.sizing(Sizing.fill(), Sizing.expand());
        CompoundTag detail = data.contains("detail", Tag.TAG_COMPOUND) ? data.getCompound("detail") : null;
        long selected = detail == null ? -1 : detail.getLong("id");
        Flex rows = Flex.column().gap(2);
        ListTag list = data.getList("rows", Tag.TAG_COMPOUND);
        if (list.isEmpty()) {
            rows.item(text(tr("none"), DIM, (int) LIST - 10));
        }
        for (int i = 0; i < list.size(); i++) {
            CompoundTag row = list.getCompound(i);
            if (!search.isBlank() && !row.getString("reporter").toLowerCase(java.util.Locale.ROOT)
                    .contains(search.toLowerCase(java.util.Locale.ROOT).strip())) {
                continue;
            }
            rows.item(new Row(row, row.getLong("id") == selected));
        }
        if (data.getInt("total") > list.size()) {
            rows.item(text(tr("more", list.size(), data.getInt("total")), DIM, (int) LIST - 10));
        }
        ScrollPanel scroll = HubScroll.panel("mod/reports/list", LIST, 100, rows);
        scroll.sizing(Sizing.fixed(LIST), Sizing.expand());
        // Above the list: filter by the player's name (the server sends the newest 500).
        Flex left = Flex.column().gap(3);
        left.sizing(Sizing.fixed(LIST), Sizing.fill());
        // Applied on Enter: rebuilding on every key would take the field's focus away.
        TextField find = new TextField(LIST, search, value -> search = value);
        find.onSubmit(screen::rebuild);
        find.size(LIST, 16);
        find.hint(tr("search_hint").getString());
        left.item(find);
        left.item(scroll);
        columns.item(left);

        Flex right = Flex.column().gap(4);
        right.sizing(Sizing.expand(), Sizing.fill());
        if (detail != null) {
            buildDetail(right, detail);
        }
        columns.item(right);
        main.item(columns);
    }

    private void buildDetail(Flex box, CompoundTag report) {
        float w = width - LIST - 8;
        long id = report.getLong("id");
        boolean handled = report.getBoolean("handled");
        box.item(TextNode.of(Component.literal("#" + id + "  ").append(label(report)))
                .color(TEXT).shadow(false).maxWidth((int) w).ellipsis(true));
        long time = report.getLong("time");
        box.item(text(tr("from", report.getString("reporter"), PunishSection.ago(time),
                DATE.format(Instant.ofEpochMilli(time))), DIM, (int) w));
        int current = Math.max(0, Math.min(3, report.getInt("status")));
        Component statusLine = tr("status." + current);
        if (!report.getString("handled_by").isEmpty()) {
            statusLine = tr("status_by", statusLine, report.getString("handled_by"),
                    PunishSection.ago(report.getLong("handled_at")));
        }
        box.item(text(statusLine, STATUS_COLORS[current], (int) w));
        if (!report.getString("target").isEmpty()) {
            box.item(text(tr("target", report.getString("target")), TEXT, (int) w));
        }
        if (report.getInt("payload") > 0) {
            box.item(text(tr("payload", report.getInt("payload")), DIM, (int) w));
        }
        // The player's words and the mod's log scroll together: the text alone may run to 500 characters.
        Flex body = Flex.column().gap(2).padding(Insets.of(3, 4, 3, 4));
        body.item(text(Component.literal(report.getString("text")), TEXT, (int) w - 20));
        body.item(ModerationScreen.gap(3));
        ListTag log = report.getList("log", Tag.TAG_STRING);
        body.item(text(log.isEmpty() ? tr("log_empty") : tr("log", log.size()), DIM, (int) w - 20));
        for (int i = 0; i < log.size(); i++) {
            body.item(text(Component.literal(log.getString(i)), 0xFFC8CCD2, (int) w - 20));
        }
        ScrollPanel scroll = HubScroll.panel("mod/reports/detail/" + id, w, 60, body);
        scroll.sizing(Sizing.fill(), Sizing.fill());
        Flex framed = Flex.column();
        framed.sizing(Sizing.fill(), Sizing.expand());
        framed.background(r -> Shapes.rect(0, 0, r.width(), r.height()).fill(0xFF17191C).stroke(Stroke.of(LINE, 1)));
        framed.item(scroll);
        box.item(framed);

        // The conversation after the evidence (1.3.93): replies to the player, staff-only notes, system lines.
        ListTag messages = report.getList("messages", Tag.TAG_COMPOUND);
        if (!messages.isEmpty()) {
            body.item(ModerationScreen.gap(3));
            body.item(text(tr("thread", messages.size()), DIM, (int) w - 20));
        }
        for (int i = 0; i < messages.size(); i++) {
            CompoundTag message = messages.getCompound(i);
            int role = message.getInt("role");
            Component who = switch (role) {
                case 0 -> tr("role.player", message.getString("author"));
                case 1 -> tr("role.staff", message.getString("author"));
                case 2 -> tr("role.note", message.getString("author"));
                default -> Component.empty();
            };
            Component line = role == 3 ? Component.translatable("gui.eden_ld.hub.reports.system." + message.getString("text"))
                    : Component.empty().append(who).append(": " + message.getString("text"));
            int color = switch (role) {
                case 0 -> TEXT;
                case 1 -> 0xFF8FC8FF;
                case 2 -> WARN;
                default -> DIM;
            };
            body.item(text(line, color, (int) w - 20));
        }

        // Write: a reply the player sees (and gets by mail), or a note only staff see.
        TextField field = new TextField(w, message, value -> message = value);
        field.size(w, 16);
        field.hint(tr("message_hint").getString());
        field.maxLength(net.ledok.eden_ld.hub.moderation.ReportRules.MAX_REPLY);
        box.item(field);
        Flex write = Flex.row().gap(4).alignItems(Align.CENTER);
        write.sizing(Sizing.fill(), Sizing.content());
        java.util.List<Integer> templates = java.util.List.of(0, 1, 2, 3, 4);
        write.item(new net.ledok.vectorlib.client.canvas.widget.Dropdown<>(76, templates, 0,
                value -> value == 0 ? tr("templates") : tr("template." + value), value -> {
                    if (value > 0) {
                        message = tr("template." + value + ".text").getString();
                        screen.rebuild();
                    }
                }));
        write.item(PlayerTabs.spacer());
        var noteButton = small("gui.eden_ld.moderation.reports.add_note", 50, () -> sendMessage(id, true));
        noteButton.tooltip(tr("add_note.hint"));
        write.item(noteButton);
        var sendButton = small("gui.eden_ld.moderation.reports.send_reply", 70, () -> sendMessage(id, false));
        sendButton.tooltip(tr("send_reply.hint"));
        write.item(sendButton);
        box.item(write);

        // Tools: take it, the reporter's page, teleports to the evidence's positions.
        Flex tools = Flex.row().gap(4).alignItems(Align.CENTER);
        tools.sizing(Sizing.fill(), Sizing.content());
        String assignee = report.getString("assignee");
        String me = net.minecraft.client.Minecraft.getInstance().getUser().getName();
        tools.item(small(assignee.equals(me) ? "gui.eden_ld.moderation.reports.unassign"
                : "gui.eden_ld.moderation.reports.assign", 56, () -> send("assign", id)));
        if (report.hasUUID("reporter_uuid")) {
            java.util.UUID reporterId = report.getUUID("reporter_uuid");
            String reporterName = report.getString("reporter");
            tools.item(small("gui.eden_ld.moderation.reports.profile", 46, () -> screen.openPlayer(reporterId, reporterName)));
        }
        tools.item(PlayerTabs.spacer());
        if (report.getBoolean("has_reporter_pos")) {
            var tp = small("gui.eden_ld.moderation.reports.tp", 26, () -> teleport(id, false));
            tp.tooltip(tr("tp.hint"));
            tools.item(tp);
        }
        if (report.getBoolean("has_target_pos")) {
            var tpTarget = small("gui.eden_ld.moderation.reports.tp_target", 50, () -> teleport(id, true));
            tpTarget.tooltip(tr("tp_target.hint"));
            tools.item(tpTarget);
        }
        box.item(tools);

        boolean editor = report.getBoolean("editor");
        boolean reward = screen.mayMail();
        if (editor || reward) {
            Flex extra = Flex.row().gap(4).alignItems(Align.CENTER);
            extra.sizing(Sizing.fill(), Sizing.content());
            extra.item(PlayerTabs.spacer());
            if (reward) {
                // A reward (items, LC) goes as staff mail: the mail editor, already addressed and written.
                String reporter = report.getString("reporter");
                extra.item(small("gui.eden_ld.moderation.reports.reward", 60, () -> screen.rewardMail(reporter,
                        tr("reward_title", id).getString(), message)));
            }
            if (editor) {
                extra.item(small("gui.eden_ld.moderation.reports.editor", 90, () -> send("editor", id)));
            }
            box.item(extra);
        }

        Flex actions = Flex.row().gap(4).alignItems(Align.CENTER);
        actions.sizing(Sizing.fill(), Sizing.content());
        // Who took it shows in the list row and on the take/release button: this row stays narrow enough for Save.
        actions.item(PlayerTabs.spacer());
        actions.item(new net.ledok.vectorlib.client.canvas.widget.Dropdown<>(100, java.util.List.of(0, 1, 2, 3), status,
                value -> tr("status." + value), value -> {
                    status = value;
                    noteDirty = true;
                }));
        actions.item(small("gui.eden_ld.moderation.reports.save", 60, () -> {
            CompoundTag args = new CompoundTag();
            args.putLong("id", id);
            args.putInt("status", status);
            noteDirty = false;
            PlayerTabs.action(AdminTools.REPORTS, Util.NIL_UUID, "save", args);
        }));
        box.item(actions);
    }

    private void sendMessage(long id, boolean note) {
        if (message.isBlank()) {
            return;
        }
        CompoundTag args = new CompoundTag();
        args.putLong("id", id);
        args.putString("text", message);
        args.putBoolean("note", note);
        message = "";
        PlayerTabs.action(AdminTools.REPORTS, Util.NIL_UUID, "message", args);
    }

    private void teleport(long id, boolean target) {
        CompoundTag args = new CompoundTag();
        args.putLong("id", id);
        args.putBoolean("target", target);
        PlayerTabs.action(AdminTools.REPORTS, Util.NIL_UUID, "tp", args);
    }

    /**
     * The kind by name — Eden's own («Баг», «Скарга на гравця», …) or a mod's lang key
     * {@code gui.eden_ld.hub.reports.category.<ns>.<path>} (else the raw category) — and the reference: "Баг",
     * "Дуель зі ставкою · #1042".
     */
    private static Component label(CompoundTag report) {
        String category = report.getString("category");
        String reference = report.getString("reference");
        Component kind = category.startsWith("eden_ld:")
                ? Component.translatable("gui.eden_ld.hub.reports.kind." + category.substring("eden_ld:".length()))
                : Component.translatableWithFallback("gui.eden_ld.hub.reports.category." + category.replace(':', '.'),
                        category);
        return reference.isEmpty() ? kind : Component.empty().append(kind).append(" · " + reference);
    }

    /** A report in the list: #id and what it's about; who, when, and its state. */
    private final class Row extends Widget {
        private final CompoundTag row;
        private final boolean on;

        Row(CompoundTag row, boolean on) {
            super(LIST - 8, ROW_H);
            this.row = row;
            this.on = on;
            sizing(Sizing.fixed(LIST - 8), Sizing.fixed(ROW_H));
        }

        @Override
        protected void rebuild() {
            add(Shapes.rect(0, 0, width, height).fill(on ? PANEL : 0xFF22252A)
                    .stroke(Stroke.of(on ? ACCENT : hovered ? DIM : LINE, 1)));
            net.minecraft.client.gui.Font font = net.minecraft.client.Minecraft.getInstance().font;
            // Who took it, right-aligned on the first line: the second keeps clear of the status label.
            String assignee = row.getString("assignee");
            int assigneeWidth = 0;
            if (!assignee.isEmpty()) {
                Component taken = Component.literal("→ " + assignee);
                assigneeWidth = font.width(taken) + 6;
                add(TextNode.of(taken).at(width - 4, 3).align(TextNode.Align.RIGHT).color(DIM).shadow(false));
            }
            add(TextNode.of(Component.literal("#" + row.getLong("id") + "  ").append(label(row)))
                    .at(4, 3).maxWidth((int) width - 8 - assigneeWidth).ellipsis(true).color(TEXT).shadow(false));
            int rowStatus = Math.max(0, Math.min(3, row.getInt("status")));
            Component state = tr("status." + rowStatus);
            float stateWidth = font.width(state) * 0.8f;
            add(TextNode.of(state).at(width - 4, 13).align(TextNode.Align.RIGHT).color(STATUS_COLORS[rowStatus])
                    .shadow(false).scale(0.8f));
            Component who = Component.literal((row.getBoolean("waiting") ? "● " : "") + row.getString("reporter")
                    + " · ").append(PunishSection.ago(row.getLong("time")));
            // maxWidth counts in the node's scaled units: this cuts the line before the status label.
            add(TextNode.of(who).at(4, 13).maxWidth((int) (width - 14 - stateWidth)).ellipsis(true)
                    .color(DIM).shadow(false).scale(0.8f));
        }

        @Override
        public boolean onMouseDown(float x, float y, int button) {
            if (button != 0) {
                return false;
            }
            UiSounds.click();
            view(null, row.getLong("id"));
            return true;
        }

        @Override
        public Cursor cursor() {
            return Cursor.HAND;
        }
    }
}
