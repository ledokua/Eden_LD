package net.ledok.eden_ld.client.hub;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.ledok.eden_ld.api.hub.EdenReportsClientApi;
import net.ledok.eden_ld.client.EdenParchment;
import net.ledok.eden_ld.hub.HubPayloads;
import net.ledok.eden_ld.hub.HubPayloads.ReportCandidate;
import net.ledok.eden_ld.hub.HubPayloads.ReportEntry;
import net.ledok.vectorlib.client.canvas.layout.Align;
import net.ledok.vectorlib.client.canvas.layout.Flex;
import net.ledok.vectorlib.client.canvas.layout.Insets;
import net.ledok.vectorlib.client.canvas.layout.Justify;
import net.ledok.vectorlib.client.canvas.layout.Sizing;
import net.ledok.vectorlib.client.canvas.widget.Button;
import net.ledok.vectorlib.client.canvas.widget.Dropdown;
import net.ledok.vectorlib.client.canvas.widget.Label;
import net.ledok.vectorlib.client.canvas.widget.ScrollPanel;
import net.ledok.vectorlib.client.canvas.widget.TextArea;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * The «Звернення» tab: send staff a bug report, a complaint about a player, an idea or anything else, and see every
 * submission with its status (Нове / Розглядається / Прийнято / Відхилено) and staff's reply — other mods'
 * suggestions (Codex entries, recipes) among them. A complaint says up front what's attached to it (recent chat and
 * positions). Opened from the hub, the inventory's button and {@code /report [player]}.
 */
public final class ReportsTab {

    /** Eden's kinds, in the form's order (the server's {@code ReportDesk.PLAYER_KINDS}). */
    private static final List<String> KINDS = List.of("eden_ld:bug", "eden_ld:complaint", "eden_ld:idea", "eden_ld:other");
    private static final String COMPLAINT = "eden_ld:complaint";
    private static final int MAX_TEXT = 500;
    private static final float LANE = 6;
    private static final int[] STATUS_COLORS = {0xFF3A6EA5, 0xFFB07A1E, 0xFF3C8A3C, 0xFFA03A32};

    /** The form, kept while the hub is open (rebuilds keep what was typed). */
    private static String kind = KINDS.get(0);
    private static UUID target;
    private static String text = "";
    /** A submission is on its way: the form empties once the server's list holds one more. */
    private static int sentAt = -1;
    /** The submission whose conversation is open (-1: the form and the list), and the reply being typed. */
    private static long openThread = -1;
    private static String reply = "";
    /** A reply is on its way: the box empties once the thread holds one more message. */
    private static int replySentAt = -1;

    private ReportsTab() {
    }

    private static Component tr(String key, Object... args) {
        return Component.translatable("gui.eden_ld.hub.reports." + key, args);
    }

    /** {@code /report <player>}: the form as a complaint about them (matched by name once the list arrives). */
    static void preselect(String name) {
        kind = COMPLAINT;
        target = null;
        pendingName = name;
    }

    private static String pendingName;

    /** Fresh data: a pending complaint name finds its player; a sent submission empties the form. */
    public static void onData(HubPayloads.ReportsData data) {
        if (pendingName != null) {
            for (ReportCandidate candidate : data.players()) {
                if (candidate.name().equalsIgnoreCase(pendingName)) {
                    target = candidate.uuid();
                }
            }
            pendingName = null;
        }
        if (sentAt >= 0 && data.entries().size() > sentAt) {
            text = "";
            target = null;
            sentAt = -1;
        }
        // A staff reply arriving while its thread is open is read at once.
        ReportEntry shown = openThread >= 0 ? entry(data, openThread) : null;
        if (shown != null && shown.unread()) {
            ClientPlayNetworking.send(new HubPayloads.ReportThreadAction(shown.id(), HubPayloads.ReportThreadAction.READ, ""));
        }
        if (replySentAt >= 0) {
            ReportEntry open = entry(data, openThread);
            if (open == null || open.messages().size() > replySentAt) {
                reply = "";
                replySentAt = -1;
            }
        }
    }

    private static ReportEntry entry(HubPayloads.ReportsData data, long id) {
        for (ReportEntry entry : data.entries()) {
            if (entry.id() == id) {
                return entry;
            }
        }
        return null;
    }

    /** Submissions with staff replies the player hasn't read (the tab's badge). */
    static int unread() {
        HubPayloads.ReportsData data = HubState.reportsData;
        if (data == null) {
            return 0;
        }
        int unread = 0;
        for (ReportEntry entry : data.entries()) {
            if (entry.unread()) {
                unread++;
            }
        }
        return unread;
    }

    static void build(HubScreen screen, Flex content) {
        HubPayloads.ReportsData data = HubState.reportsData;
        if (data == null) {
            Flex center = Flex.column().justify(Justify.CENTER).alignItems(Align.CENTER);
            center.sizing(Sizing.fill(), Sizing.expand());
            center.item(EdenParchment.text(Component.translatable("gui.eden_ld.hub.home.loading"), EdenParchment.INK_DIM));
            content.item(center);
            return;
        }
        float width = HubScreen.innerWidth();
        ReportEntry open = openThread >= 0 ? entry(data, openThread) : null;
        if (open != null) {
            thread(screen, content, open, width);
            return;
        }
        openThread = -1;
        if (data.allowed()) {
            content.item(form(screen, data, width));
            content.item(EdenParchment.hairline());
        } else {
            content.item(EdenParchment.text(tr("not_allowed"), EdenParchment.INK_DIM, (int) width));
        }
        content.item(EdenParchment.text(tr("mine"), EdenParchment.INK));
        if (data.entries().isEmpty()) {
            content.item(EdenParchment.text(tr("none"), EdenParchment.INK_DIM, (int) width));
            return;
        }
        Flex list = Flex.column().gap(3);
        for (ReportEntry entry : data.entries()) {
            list.item(card(screen, entry, width - LANE));
        }
        ScrollPanel panel = HubScroll.panel("reports", width, 80, list);
        panel.sizing(Sizing.fill(), Sizing.expand());
        content.item(panel);
    }

    // ---------------------------------------------------------------- the form

    private static Flex form(HubScreen screen, HubPayloads.ReportsData data, float width) {
        Flex form = Flex.column().gap(4);
        form.sizing(Sizing.fill(), Sizing.content());

        // Eden's kinds, then other mods' suggestion starters the player may use (EdenReportsClientApi).
        List<String> choices = new ArrayList<>(KINDS);
        for (EdenReportsClientApi.Starter starter : EdenReportsClientApi.starters()) {
            if (available(starter)) {
                choices.add(starter.category());
            }
        }
        if (!choices.contains(kind)) {
            kind = KINDS.get(0);
        }
        Flex row = Flex.row().gap(6).alignItems(Align.CENTER);
        row.item(EdenParchment.text(tr("kind"), EdenParchment.INK_MID));
        Dropdown<String> kinds = new Dropdown<>(130, choices, kind, value -> kindName(value), value -> {
            kind = value;
            screen.onReportsData();
        });
        row.item(kinds);
        boolean complaint = kind.equals(COMPLAINT);
        if (complaint) {
            row.item(EdenParchment.text(tr("player"), EdenParchment.INK_MID));
            List<UUID> ids = new ArrayList<>();
            ids.add(null);
            data.players().forEach(candidate -> ids.add(candidate.uuid()));
            if (target != null && !ids.contains(target)) {
                target = null;
            }
            Dropdown<UUID> players = new Dropdown<>(130, ids, target, id -> playerName(data, id), id -> {
                target = id;
                screen.onReportsData();
            });
            players.searchable(true);
            row.item(players);
        }
        form.item(row);

        EdenReportsClientApi.Starter starter = starter(kind);
        if (starter != null) {
            // A mod's own editor files this kind: a hint and the button that opens it (back to the hub on close).
            form.item(EdenParchment.text(tr("starter_hint"), EdenParchment.INK_DIM, (int) width));
            Flex open = Flex.row().gap(6).alignItems(Align.CENTER);
            open.spacer();
            open.item(EdenParchment.fitButton(tr("open_editor"), 90, 14, () -> {
                try {
                    starter.open().accept(screen);
                } catch (RuntimeException e) {
                    net.ledok.eden_ld.EdenLD.LOGGER.error("[Reports] The {} editor failed to open", starter.category(), e);
                }
            }));
            form.item(open);
            return form;
        }

        if (complaint) {
            // Said plainly, so nobody is surprised (LeDok, 2026-11-15).
            form.item(EdenParchment.text(tr("evidence"), EdenParchment.INK_DIM, (int) width));
        }
        TextArea area = new TextArea(width, 46, text, value -> text = value);
        area.hint(tr("hint." + kindKey(kind)).getString()).maxLength(MAX_TEXT);
        form.item(area);

        Flex actions = Flex.row().gap(6).alignItems(Align.CENTER);
        actions.spacer();
        Button send = EdenParchment.fitButton(tr("send"), 70, 14, () -> send(data));
        actions.item(send);
        form.item(actions);
        return form;
    }

    private static void send(HubPayloads.ReportsData data) {
        if (text.isBlank()) {
            return;
        }
        boolean complaint = kind.equals(COMPLAINT);
        if (complaint && target == null) {
            return;
        }
        sentAt = data.entries().size();
        ClientPlayNetworking.send(new HubPayloads.ReportSubmit(kind, complaint ? Optional.of(target) : Optional.empty(),
                text));
    }

    private static Component playerName(HubPayloads.ReportsData data, UUID id) {
        if (id == null) {
            return tr("pick_player");
        }
        for (ReportCandidate candidate : data.players()) {
            if (candidate.uuid().equals(id)) {
                return candidate.online() ? Component.literal(candidate.name())
                        : tr("left_recently", candidate.name());
            }
        }
        return Component.literal("?");
    }

    // ---------------------------------------------------------------- the list

    /** A submission in the list: #, kind, status, the start of its text, a new-reply mark, and Open. */
    private static Flex card(HubScreen screen, ReportEntry entry, float width) {
        Flex card = Flex.column().gap(2).padding(Insets.of(4, 6, 4, 6));
        card.sizing(Sizing.fixed(width), Sizing.content());
        card.background(EdenParchment.framed(entry.status() >= 2 ? EdenParchment.ROW_BG_ALT : EdenParchment.ROW_BG,
                EdenParchment.HAIRLINE));
        float inner = width - 12;
        card.item(head(entry, inner));

        Flex line = Flex.row().gap(6).alignItems(Align.CENTER);
        Component open = tr("open_thread");
        float openWidth = EdenParchment.fitWidth(open, 60);
        String first = entry.text().replace('\n', ' ');
        Label excerpt = Label.of(inner - openWidth - 6, Component.literal(first));
        excerpt.color(EdenParchment.INK_MID);
        excerpt.tooltipWhenCut(true);
        line.item(excerpt);
        line.spacer();
        line.item(EdenParchment.fitButton(open, 60, 14, () -> openThread(screen, entry)));
        card.item(line);

        Component meta = Component.literal(HubDates.full(entry.time()));
        if (entry.unread()) {
            meta = Component.empty().append(meta).append("  ").append(Component.translatable("gui.eden_ld.hub.reports.new_reply")
                    .withStyle(style -> style.withColor(EdenParchment.NOTIFY)));
        } else if (!entry.messages().isEmpty()) {
            meta = Component.empty().append(meta).append("  ").append(tr("messages", entry.messages().size() + 1));
        }
        card.item(EdenParchment.text(meta, EdenParchment.INK_DIM));
        return card;
    }

    /** "#2 · Баг · …" and the status, on one line. */
    private static Flex head(ReportEntry entry, float inner) {
        Flex head = Flex.row().gap(6).alignItems(Align.CENTER);
        Component title = Component.literal("#" + entry.id() + " · ").append(kindName(entry.category()))
                .append(entry.target().isEmpty() ? Component.empty() : Component.literal(" · " + entry.target()))
                .append(entry.reference().isEmpty() || !entry.target().isEmpty() ? Component.empty()
                        : Component.literal(" · " + entry.reference()));
        Component status = tr("status." + Math.max(0, Math.min(3, entry.status())));
        float statusWidth = EdenParchment.fitWidth(status, 40);
        Label label = Label.of(inner - statusWidth - 6, title);
        label.color(EdenParchment.INK);
        label.tooltipWhenCut(true);
        head.item(label);
        head.spacer();
        head.item(EdenParchment.text(status, STATUS_COLORS[Math.max(0, Math.min(3, entry.status()))]));
        return head;
    }

    private static void openThread(HubScreen screen, ReportEntry entry) {
        openThread = entry.id();
        reply = "";
        if (entry.unread()) {
            ClientPlayNetworking.send(new HubPayloads.ReportThreadAction(entry.id(), HubPayloads.ReportThreadAction.READ, ""));
        }
        screen.onReportsData();
    }

    // ---------------------------------------------------------------- the conversation

    /**
     * One submission as a conversation: the player's messages on the right, staff's on the left with their name, system
     * lines in the middle; then, while it's open, a reply box with Надіслати and «Питання вирішено».
     */
    private static void thread(HubScreen screen, Flex content, ReportEntry entry, float width) {
        Flex top = Flex.row().gap(6).alignItems(Align.CENTER);
        top.sizing(Sizing.fill(), Sizing.content());
        top.item(EdenParchment.fitButton(tr("back"), 40, 14, () -> {
            openThread = -1;
            screen.onReportsData();
        }));
        float headWidth = width - EdenParchment.fitWidth(tr("back"), 40) - 6;
        top.item(head(entry, headWidth));
        content.item(top);

        float bubble = (width - LANE) * 0.8f;
        Flex list = Flex.column().gap(4);
        list.item(message(new HubPayloads.ReportLine(entry.time(), "", 0, entry.text()), width - LANE, bubble));
        for (HubPayloads.ReportLine line : entry.messages()) {
            list.item(message(line, width - LANE, bubble));
        }
        ScrollPanel panel = HubScroll.panel("reports/thread/" + entry.id(), width, 80, list);
        panel.sizing(Sizing.fill(), Sizing.expand());
        // Newest at the bottom, as in a chat: start there.
        panel.scrollTo(1_000_000f);
        content.item(panel);

        if (entry.status() >= 2) {
            content.item(EdenParchment.text(tr("thread_closed"), EdenParchment.INK_DIM, (int) width));
            return;
        }
        TextArea area = new TextArea(width, 34, reply, value -> reply = value);
        area.hint(tr("reply_hint").getString()).maxLength(MAX_TEXT);
        content.item(area);
        Flex actions = Flex.row().gap(6).alignItems(Align.CENTER);
        actions.sizing(Sizing.fill(), Sizing.content());
        Button close = EdenParchment.fitButton(tr("close_thread"), 80, 14, () -> ClientPlayNetworking.send(
                new HubPayloads.ReportThreadAction(entry.id(), HubPayloads.ReportThreadAction.CLOSE, "")));
        close.tooltip(tr("close_thread.hint"));
        actions.item(close);
        actions.spacer();
        actions.item(EdenParchment.fitButton(tr("send"), 70, 14, () -> {
            if (!reply.isBlank()) {
                replySentAt = entry.messages().size();
                ClientPlayNetworking.send(new HubPayloads.ReportThreadAction(entry.id(),
                        HubPayloads.ReportThreadAction.WRITE, reply));
            }
        }));
        content.item(actions);
    }

    /** A message: the player's own on the right, staff's on the left (their name above), a system line centered. */
    private static Flex message(HubPayloads.ReportLine line, float width, float bubble) {
        Flex row = Flex.row().gap(0);
        row.sizing(Sizing.fixed(width), Sizing.content());
        if (line.role() == 3) {
            row.spacer();
            row.item(EdenParchment.text(tr("system." + line.text()), EdenParchment.INK_DIM));
            row.spacer();
            return row;
        }
        boolean mine = line.role() == 0;
        Flex box = Flex.column().gap(1).padding(Insets.of(3, 5, 3, 5));
        box.sizing(Sizing.fixed(bubble), Sizing.content());
        box.background(EdenParchment.framed(mine ? EdenParchment.ROW_BG : EdenParchment.PANEL_2, EdenParchment.HAIRLINE));
        Component who = mine ? tr("you") : tr("staff_name", line.author());
        box.item(EdenParchment.text(Component.empty().append(who).append(" · " + HubDates.full(line.time())),
                mine ? EdenParchment.INK_DIM : EdenParchment.ACCENT));
        box.item(EdenParchment.text(Component.literal(line.text()), EdenParchment.INK, (int) bubble - 10));
        if (mine) {
            row.spacer();
            row.item(box);
        } else {
            row.item(box);
            row.spacer();
        }
        return row;
    }

    private static boolean available(EdenReportsClientApi.Starter starter) {
        try {
            return starter.available().getAsBoolean();
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static EdenReportsClientApi.Starter starter(String category) {
        for (EdenReportsClientApi.Starter starter : EdenReportsClientApi.starters()) {
            if (starter.category().equals(category)) {
                return starter;
            }
        }
        return null;
    }

    /**
     * Eden's kinds by name; a starter's by its own label; any other mod's category by its lang key
     * {@code gui.eden_ld.hub.reports.category.<ns>.<path>}.
     */
    private static Component kindName(String category) {
        if (KINDS.contains(category)) {
            return tr("kind." + kindKey(category));
        }
        EdenReportsClientApi.Starter starter = starter(category);
        if (starter != null) {
            return Component.translatable(starter.labelKey());
        }
        return Component.translatableWithFallback("gui.eden_ld.hub.reports.category." + category.replace(':', '.'),
                category);
    }

    private static String kindKey(String category) {
        int colon = category.indexOf(':');
        return (colon < 0 ? category : category.substring(colon + 1)).toLowerCase(Locale.ROOT);
    }
}
