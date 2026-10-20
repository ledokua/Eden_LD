package net.ledok.eden_ld.client.hub;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.ledok.eden_ld.client.profile.ProfileBackgrounds;
import net.ledok.eden_ld.hub.HubPayloads;
import net.ledok.eden_ld.hub.activity.ActivityLiveStatus;
import net.ledok.eden_ld.hub.activity.ActivitySource.AdminAction;
import net.ledok.eden_ld.hub.moderation.AdminTools;
import net.ledok.vectorlib.client.canvas.CanvasGroup;
import net.ledok.vectorlib.client.canvas.Shapes;
import net.ledok.vectorlib.client.canvas.Stroke;
import net.ledok.vectorlib.client.canvas.TextNode;
import net.ledok.vectorlib.client.canvas.layout.Align;
import net.ledok.vectorlib.client.canvas.layout.Flex;
import net.ledok.vectorlib.client.canvas.layout.Insets;
import net.ledok.vectorlib.client.canvas.layout.Justify;
import net.ledok.vectorlib.client.canvas.layout.Sizing;
import net.ledok.vectorlib.client.canvas.widget.Button;
import net.ledok.vectorlib.client.canvas.widget.Label;
import net.ledok.vectorlib.client.canvas.widget.Modal;
import net.ledok.vectorlib.client.canvas.widget.NumberField;
import net.ledok.vectorlib.client.canvas.widget.ScrollPanel;
import net.ledok.vectorlib.client.canvas.widget.TextField;
import net.ledok.vectorlib.client.canvas.widget.Toggle;
import net.ledok.vectorlib.client.canvas.widget.UiSounds;
import net.ledok.vectorlib.client.canvas.widget.Widget;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.locale.Language;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import static net.ledok.eden_ld.client.hub.ModerationScreen.ACCENT;
import static net.ledok.eden_ld.client.hub.ModerationScreen.BAD;
import static net.ledok.eden_ld.client.hub.ModerationScreen.DIM;
import static net.ledok.eden_ld.client.hub.ModerationScreen.GOOD;
import static net.ledok.eden_ld.client.hub.ModerationScreen.LINE;
import static net.ledok.eden_ld.client.hub.ModerationScreen.PANEL;
import static net.ledok.eden_ld.client.hub.ModerationScreen.TEXT;
import static net.ledok.eden_ld.client.hub.ModerationScreen.small;
import static net.ledok.eden_ld.client.hub.ModerationScreen.text;

/**
 * The Moderation window's Activities section: every dungeon, raid and arena (from Arenas_LD and
 * datapack files), and for the one picked whether the hub shows it, whether it's closed for
 * maintenance, its card art (a profile background) and the level each difficulty needs. New
 * activities are hidden until shown here. Changes save themselves a moment after the last one, like
 * Backgrounds.
 * <p>
 * The controller actions (teleport, the source's admin screen, rename, end runs, forget) show only
 * where the activity's source supports them, the op-only ones only for ops. While the section is open
 * it polls the server about every 2 s ({@link HubPayloads.ActivityLiveRequest}): the list's markers
 * (running, cooling down, ⚠, closed) and the picked one's live status and run history. A poll only
 * rebuilds the live blocks when something other than a timer changed; timers count on by themselves.
 */
final class ActivitiesSection {

    private static final float LIST = 190;
    private static final float ROW_H = 24;
    private static final float ART_W = 62;
    private static final float ART_H = 34;
    private static final long SAVE_DELAY = 600;
    private static final long POLL_MILLIS = 2000;
    private static final int WARN = 0xFFE0C850;
    /** Keys that tick on their own between polls: left out when deciding whether to rebuild. */
    private static final Set<String> TIMERS = Set.of("elapsed", "left", "cooldown", "ago");

    private final ModerationScreen screen;
    private final float width;
    /** id → the activity as the server sent it, with this window's edits on top. */
    private final Map<String, CompoundTag> activities = new LinkedHashMap<>();
    /** id → the list's markers from the last poll. */
    private final Map<String, CompoundTag> summaries = new HashMap<>();
    private boolean loaded;
    /** The admin is an op: rename and the source's admin screen show. */
    private boolean op;
    @Nullable
    private String selected;
    @Nullable
    private String pending;
    private long changedAt;

    /** The picked activity's live status and history from the last poll, and when it arrived. */
    @Nullable
    private CompoundTag live;
    private long liveAt;
    private long polledAt;
    /** The detail's live blocks (status and warnings; history), refilled when a poll changes them. */
    @Nullable
    private Flex liveBox;
    @Nullable
    private Flex historyBox;
    private float detailWidth;

    ActivitiesSection(ModerationScreen screen, float width) {
        this.screen = screen;
        this.width = width;
    }

    /** Picks an activity before the list arrives (the window reopened after a source's admin screen). */
    void select(String id) {
        selected = id.isEmpty() ? null : id;
    }

    void request() {
        PlayerTabs.request(AdminTools.ACTIVITIES, Util.NIL_UUID);
        // The next frame polls the live data too.
        polledAt = 0;
    }

    /** Returns whether it was this section's data. */
    boolean onData(String area, CompoundTag tag) {
        if (!area.equals(AdminTools.ACTIVITIES)) {
            return false;
        }
        loaded = true;
        op = tag.getBoolean("op");
        CompoundTag kept = pending == null ? null : activities.get(pending);
        activities.clear();
        ListTag list = tag.getList("activities", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            activities.put(entry.getString("id"), entry);
        }
        // The one being edited keeps what's typed until it's saved.
        if (kept != null && activities.containsKey(pending)) {
            activities.put(pending, kept);
        }
        return true;
    }

    /** The live poll's answer: markers for every row, the picked one's status and history. */
    void onLive(CompoundTag tag, boolean showing) {
        summaries.clear();
        ListTag rows = tag.getList("rows", Tag.TAG_COMPOUND);
        for (int i = 0; i < rows.size(); i++) {
            summaries.put(rows.getCompound(i).getString("id"), rows.getCompound(i));
        }
        CompoundTag detail = tag.contains("detail", Tag.TAG_COMPOUND) ? tag.getCompound("detail") : null;
        boolean same = detail != null && live != null && withoutTimers(detail).equals(withoutTimers(live));
        live = detail;
        liveAt = System.currentTimeMillis();
        // Only timers moved: the open blocks read them from the new data by themselves.
        if (!same && showing) {
            refillLive();
        }
    }

    /** Autosave and the live poll, from the window's frame ({@code showing}: this section is open). */
    void tick(boolean showing) {
        if (pending != null && System.currentTimeMillis() - changedAt > SAVE_DELAY) {
            flush();
        }
        if (showing && loaded && System.currentTimeMillis() - polledAt > POLL_MILLIS) {
            poll();
        }
    }

    private void poll() {
        polledAt = System.currentTimeMillis();
        // An older server doesn't know the live payload: the section works without the live parts.
        if (ClientPlayNetworking.canSend(HubPayloads.ActivityLiveRequest.TYPE)) {
            ClientPlayNetworking.send(new HubPayloads.ActivityLiveRequest(selected == null ? "" : selected));
        }
    }

    void flush() {
        if (pending == null) {
            return;
        }
        CompoundTag entry = activities.get(pending);
        pending = null;
        if (entry == null) {
            return;
        }
        CompoundTag args = new CompoundTag();
        args.putString("id", entry.getString("id"));
        args.putBoolean("visible", entry.getBoolean("visible"));
        args.putBoolean("closed", entry.getBoolean("closed"));
        args.putString("card_art", entry.getString("card_art"));
        args.putInt("level", entry.getInt("level"));
        CompoundTag levels = new CompoundTag();
        ListTag difficulties = entry.getList("difficulties", Tag.TAG_COMPOUND);
        for (int i = 0; i < difficulties.size(); i++) {
            levels.putInt(difficulties.getCompound(i).getString("id"), difficulties.getCompound(i).getInt("level"));
        }
        args.put("levels", levels);
        PlayerTabs.action(AdminTools.ACTIVITIES, Util.NIL_UUID, "save", args);
    }

    private void changed(String id, boolean rebuild) {
        if (pending != null && !pending.equals(id)) {
            flush();
        }
        pending = id;
        changedAt = System.currentTimeMillis();
        if (rebuild) {
            screen.rebuildNow();
        }
    }

    /** A controller action on the activity; pending edits go first. */
    private void act(String action, String id, @Nullable CompoundTag extra) {
        flush();
        CompoundTag args = extra == null ? new CompoundTag() : extra;
        args.putString("id", id);
        PlayerTabs.action(AdminTools.ACTIVITIES, Util.NIL_UUID, action, args);
    }

    private static Component tr(String key, Object... args) {
        return Component.translatable("gui.eden_ld.moderation.activities." + key, args);
    }

    private static Component name(CompoundTag entry) {
        return PlayerTabs.json(entry.getString("name"));
    }

    private static Component type(CompoundTag entry) {
        return Component.translatable("gui.eden_ld.hub.activities.type." + entry.getInt("type"));
    }

    /** "Easy", or the raw id when there's no translation ("" and "_": no difficulty, arenas). */
    private static Component difficulty(String id) {
        if (id.isEmpty() || id.equals("_")) {
            return tr("all_difficulties");
        }
        String key = "gui.eden_ld.moderation.activities.difficulty." + id.toLowerCase(Locale.ROOT);
        return Language.getInstance().has(key) ? Component.translatable(key) : Component.literal(id);
    }

    /** The level it opens at: its first difficulty's, else its own. */
    private static int unlockLevel(CompoundTag entry) {
        ListTag difficulties = entry.getList("difficulties", Tag.TAG_COMPOUND);
        return difficulties.isEmpty() ? entry.getInt("level") : difficulties.getCompound(0).getInt("level");
    }

    private static Set<AdminAction> actions(CompoundTag entry) {
        return AdminAction.of(entry.getInt("actions"));
    }

    /** "8:12", or "1:02:05" past an hour. */
    static String clock(long millis) {
        long seconds = Math.max(0, millis) / 1000;
        return seconds >= 3600 ? String.format("%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
                : String.format("%d:%02d", seconds / 60, seconds % 60);
    }

    /** A copy without the keys that tick by themselves (they change on every poll). */
    private static Tag withoutTimers(Tag tag) {
        if (tag instanceof CompoundTag compound) {
            CompoundTag copy = new CompoundTag();
            for (String key : compound.getAllKeys()) {
                if (!TIMERS.contains(key)) {
                    copy.put(key, withoutTimers(compound.get(key)));
                }
            }
            return copy;
        }
        if (tag instanceof ListTag list) {
            ListTag copy = new ListTag();
            list.forEach(item -> copy.add(withoutTimers(item)));
            return copy;
        }
        return tag;
    }

    // ---------------------------------------------------------------- layout

    void build(Flex main) {
        main.item(text(Component.translatable("gui.eden_ld.moderation.section.activities"), TEXT));
        liveBox = null;
        historyBox = null;
        if (!loaded) {
            main.item(text(Component.translatable("gui.eden_ld.hub.home.loading"), DIM));
            return;
        }
        if (selected == null || !activities.containsKey(selected)) {
            selected = activities.isEmpty() ? null : activities.keySet().iterator().next();
        }
        Flex columns = Flex.row().gap(8);
        columns.sizing(Sizing.fill(), Sizing.expand());

        Flex rows = Flex.column().gap(2);
        if (activities.isEmpty()) {
            rows.item(text(tr("none"), DIM, (int) LIST - 10));
        }
        for (CompoundTag entry : activities.values()) {
            rows.item(new Row(entry));
        }
        // Kept scroll positions: every save brings the server's list back and rebuilds the section.
        ScrollPanel list = HubScroll.panel("mod/activities/list", LIST, 100, rows);
        list.sizing(Sizing.fixed(LIST), Sizing.fill());
        columns.item(list);

        Flex detail = Flex.column().gap(5);
        if (selected != null) {
            buildDetail(detail, activities.get(selected));
        }
        ScrollPanel detailScroll = HubScroll.panel("mod/activities/" + selected, width - LIST - 8, 100, detail);
        detailScroll.sizing(Sizing.expand(), Sizing.fill());
        columns.item(detailScroll);
        main.item(columns);
    }

    private void buildDetail(Flex detail, CompoundTag entry) {
        // Room for the scroll bar.
        float w = width - LIST - 8 - 8;
        detailWidth = w;
        String id = entry.getString("id");

        // The card as the hub draws it (art, name; dimmed would be "locked").
        float cardW = Math.min(w, 150);
        float cardH = Math.round(cardW * 74 / 135f);
        CanvasGroup card = new CanvasGroup();
        String art = entry.getString("card_art");
        if (!art.isEmpty() && ProfileBackgrounds.get(art) != null) {
            card.add(ProfileBackgrounds.card(art, cardW, cardH));
        } else {
            card.add(Shapes.rect(0, 0, cardW, cardH).fill(0xFF2C3036));
        }
        card.add(TextNode.of(name(entry)).at(5, 5).maxWidth((int) cardW - 10).ellipsis(true).color(0xFFFFFFFF).shadow(true));
        card.add(Shapes.rect(0, 0, cardW, cardH).stroke(Stroke.of(LINE, 1)));
        Flex framed = Flex.column();
        framed.sizing(Sizing.fixed(cardW), Sizing.fixed(cardH));
        framed.item(card);
        detail.item(framed);

        detail.item(TextNode.of(Component.empty().append(name(entry)).append(
                        Component.literal("  " + id).withStyle(s -> s.withColor(DIM & 0xFFFFFF))))
                .color(TEXT).shadow(false).maxWidth((int) w).ellipsis(true));
        String source = entry.getString("source");
        detail.item(text(source.isEmpty() ? type(entry)
                : Component.empty().append(type(entry)).append(" · ").append(
                tr("source", source)), DIM, (int) w));
        Flex actions = actionsRow(entry);
        if (actions != null) {
            detail.item(actions);
        }

        Toggle shown = new Toggle(w, tr("shown"), entry.getBoolean("visible"), value -> {
            entry.putBoolean("visible", value);
            changed(id, true);
        });
        shown.tooltip(lines(tr("shown_hint")));
        detail.item(shown);
        Toggle closed = new Toggle(w, tr("closed"), entry.getBoolean("closed"), value -> {
            entry.putBoolean("closed", value);
            changed(id, true);
        });
        closed.tooltip(lines(tr("closed_hint")));
        detail.item(closed);

        // Live status and warnings (refilled by the poll).
        liveBox = Flex.column().gap(3);
        liveBox.sizing(Sizing.fixed(w), Sizing.content());
        fillLive(liveBox, entry, w);
        detail.item(liveBox);

        // Required levels: one per difficulty (arenas have none: one for the whole activity).
        detail.item(text(tr("levels"), TEXT, (int) w));
        ListTag difficulties = entry.getList("difficulties", Tag.TAG_COMPOUND);
        if (difficulties.isEmpty()) {
            detail.item(levelRow(tr("level_all"), entry.getInt("level"), value -> {
                // Fields report their value again when rebuilt: only a real change saves.
                if (value != entry.getInt("level")) {
                    entry.putInt("level", value);
                    changed(id, false);
                }
            }));
        }
        for (int i = 0; i < difficulties.size(); i++) {
            CompoundTag difficulty = difficulties.getCompound(i);
            detail.item(levelRow(difficulty(difficulty.getString("id")), difficulty.getInt("level"), value -> {
                if (value != difficulty.getInt("level")) {
                    difficulty.putInt("level", value);
                    changed(id, false);
                }
            }));
        }
        detail.item(text(tr("levels_hint"), DIM, (int) w));

        // Card art: any profile background (they're hidden from the profile picker until opened there).
        detail.item(text(tr("card_art"), TEXT, (int) w));
        List<Widget> arts = new ArrayList<>();
        arts.add(new ArtChoice(entry, ""));
        for (ProfileBackgrounds.Background background : ProfileBackgrounds.all()) {
            arts.add(new ArtChoice(entry, background.id().toString()));
        }
        detail.item(ModerationScreen.wrapped(arts, w, 3));
        detail.item(text(tr("card_art_hint"), DIM, (int) w));

        // Run history (Eden's own: works for every source).
        historyBox = Flex.column().gap(2);
        historyBox.sizing(Sizing.fixed(w), Sizing.content());
        fillHistory(historyBox, entry, w);
        detail.item(historyBox);
    }

    /** One tooltip line per sentence (tooltips don't wrap). */
    private static Component[] lines(Component text) {
        return java.util.Arrays.stream(text.getString().split("\n")).map(Component::literal).toArray(Component[]::new);
    }

    /** Teleport, Admin screen and ✎ — each only when the source supports it (the last two for ops). */
    @Nullable
    private Flex actionsRow(CompoundTag entry) {
        Set<AdminAction> supported = actions(entry);
        String id = entry.getString("id");
        Flex row = Flex.row().gap(4).alignItems(Align.CENTER);
        if (supported.contains(AdminAction.TELEPORT)) {
            Button teleport = small("gui.eden_ld.moderation.activities.teleport", 50, () -> {
                act("teleport", id, null);
                Minecraft.getInstance().setScreen(null);
            });
            teleport.tooltip(tr("teleport_hint"));
            row.item(teleport);
        }
        if (op && supported.contains(AdminAction.OPEN_ADMIN)) {
            Button admin = small("gui.eden_ld.moderation.activities.admin_screen", 60, () -> {
                ModerationReturn.arm(screen.parent(), id);
                act("admin", id, null);
            });
            admin.tooltip(lines(tr("admin_screen_hint")));
            row.item(admin);
        }
        if (op && supported.contains(AdminAction.RENAME)) {
            Button rename = ModerationScreen.icon("✎", () -> rename(entry));
            HubIcons.button(rename, "rename");
            rename.tooltip(tr("rename"));
            row.item(rename);
        }
        return row.children().isEmpty() ? null : row;
    }

    /** The rename dialog (the source keeps the name; up to 48 characters). */
    private void rename(CompoundTag entry) {
        flush();
        Flex dialog = Flex.column().gap(6).padding(Insets.of(8, 10, 8, 10));
        dialog.sizing(Sizing.fixed(220), Sizing.content());
        dialog.background(r -> Shapes.rect(0, 0, r.width(), r.height()).fill(PANEL).stroke(Stroke.of(LINE, 1)));
        dialog.item(text(tr("rename_title"), TEXT, 200));
        TextField field = new TextField(200, name(entry).getString(), value -> {
        });
        field.size(200, 16);
        field.maxLength(48);
        field.hint(tr("rename_hint").getString());
        dialog.item(field);
        Modal[] modal = new Modal[1];
        Runnable apply = () -> {
            String value = field.text().strip();
            if (!value.isEmpty()) {
                CompoundTag args = new CompoundTag();
                args.putString("name", value);
                act("rename", entry.getString("id"), args);
            }
            modal[0].close();
        };
        field.onSubmit(apply);
        Flex buttons = Flex.row().gap(6).justify(Justify.END);
        buttons.sizing(Sizing.fill(), Sizing.content());
        buttons.item(new Button(70, 16, Component.translatable("gui.eden_ld.moderation.save"), apply));
        buttons.item(new Button(60, 16, Component.translatable("gui.eden_ld.moderation.cancel"), () -> modal[0].close()));
        dialog.item(buttons);
        modal[0] = Modal.open(screen.canvas(), dialog);
        modal[0].closeOnClickOutside(true);
        screen.canvas().focus(field);
    }

    private static Flex levelRow(Component label, int value, java.util.function.IntConsumer onChange) {
        Flex row = Flex.row().gap(4).alignItems(Align.CENTER);
        row.item(Label.of(70, label).color(TEXT).tooltipWhenCut(true));
        NumberField field = new NumberField(60, value, number -> onChange.accept((int) number));
        field.range(0, net.ledok.eden_ld.hub.activity.ActivitySettings.MAX_LEVEL).step(1);
        field.size(60, 14);
        row.item(field);
        return row;
    }

    // ---------------------------------------------------------------- live blocks

    /** The poll changed more than timers: the open detail's live blocks again. */
    private void refillLive() {
        CompoundTag entry = selected == null ? null : activities.get(selected);
        if (entry == null || liveBox == null || historyBox == null) {
            return;
        }
        liveBox.clear();
        fillLive(liveBox, entry, detailWidth);
        historyBox.clear();
        fillHistory(historyBox, entry, detailWidth);
        screen.relayout();
    }

    /** The last poll's detail, if it's the picked activity's. */
    @Nullable
    private CompoundTag liveFor(CompoundTag entry) {
        return live != null && live.getString("id").equals(entry.getString("id")) ? live : null;
    }

    /** Ticks since the poll arrived (timers count on between polls). */
    private long ticksSincePoll() {
        return (System.currentTimeMillis() - liveAt) / 50;
    }

    /** Instances, runs in progress, lobbies; warnings. Only for sources that report a status. */
    private void fillLive(Flex box, CompoundTag entry, float w) {
        Set<AdminAction> supported = actions(entry);
        if (!supported.contains(AdminAction.STATUS)) {
            return;
        }
        String id = entry.getString("id");
        box.item(text(tr("live"), TEXT, (int) w));
        CompoundTag detail = liveFor(entry);
        if (detail == null || !detail.contains("status", Tag.TAG_COMPOUND)) {
            box.item(text(Component.translatable("gui.eden_ld.hub.home.loading"), DIM, (int) w));
            return;
        }
        CompoundTag status = detail.getCompound("status");
        boolean canEnd = supported.contains(AdminAction.FORCE_END);
        // An unloaded controller: nothing is known about its instances and runs, which isn't "none".
        boolean unknown = (status.getInt("warnings") & ActivityLiveStatus.Warning.CONTROLLER_NOT_LOADED.bit()) != 0;

        // Instances: where it runs, a state chip, the cooldown left.
        ListTag instances = status.getList("instances", Tag.TAG_COMPOUND);
        box.item(text(tr("instances"), DIM, (int) w));
        if (instances.isEmpty()) {
            box.item(text(tr(unknown ? "unknown" : "no_instances"), DIM, (int) w));
        }
        for (int i = 0; i < instances.size(); i++) {
            int index = i;
            CompoundTag instance = instances.getCompound(i);
            ActivityLiveStatus.State state = state(instance.getInt("state"));
            Flex row = Flex.row().gap(4).alignItems(Align.CENTER);
            row.sizing(Sizing.fixed(w), Sizing.content());
            Component label = tr("state." + state.name().toLowerCase(Locale.ROOT));
            row.item(chip(label, stateColor(state)));
            // The chip: its label at 0.8 scale, padding 6, frame 2; then the row's 4 gap.
            float chipW = Minecraft.getInstance().font.width(label) * 0.8f + 8;
            row.item(new LiveLine(w - chipW - 4, () -> {
                CompoundTag now = liveInstance(entry, index);
                MutableComponent line = Component.literal(place(now));
                long ticks = now.getLong("cooldown") - ticksSincePoll();
                if (state == ActivityLiveStatus.State.COOLDOWN && ticks > 0) {
                    line.append(" · ").append(tr("time_left", clock(ticks * 50)));
                }
                if (now.getBoolean("removal")) {
                    line.append(" · ").append(tr("pending_removal"));
                }
                return line;
            }, TEXT));
            box.item(row);
        }

        // Runs in progress: difficulty, phase, time; the players; End run.
        ListTag runs = status.getList("runs", Tag.TAG_COMPOUND);
        box.item(text(tr("runs"), DIM, (int) w));
        if (runs.isEmpty()) {
            box.item(text(tr(unknown ? "unknown" : "no_runs"), DIM, (int) w));
        }
        for (int i = 0; i < runs.size(); i++) {
            int index = i;
            CompoundTag run = runs.getCompound(i);
            Flex row = PlayerTabs.row(w);
            Flex lines = Flex.column().gap(1);
            String players = String.join(", ", PlayerTabs.strings(run, "players"));
            Button end = !canEnd ? null : small("gui.eden_ld.moderation.activities.end_run", 50, () -> PlayerTabs.confirm(screen,
                    tr("end_run_confirm", name(entry), players.isEmpty() ? "—" : players),
                    "gui.eden_ld.moderation.activities.end_run", () -> {
                        CompoundTag args = new CompoundTag();
                        CompoundTag at = new CompoundTag();
                        at.putString("dim", run.getString("dim"));
                        at.putInt("x", run.getInt("x"));
                        at.putInt("y", run.getInt("y"));
                        at.putInt("z", run.getInt("z"));
                        args.put("instance", at);
                        act("force_end", id, args);
                    }));
            // The row's padding (8) and its gaps (5 each) around the lines, the spacer and the button.
            float lineW = w - 8 - 5 - (end == null ? 0 : 5 + end.width());
            lines.item(new LiveLine(lineW, () -> {
                CompoundTag now = liveRun(entry, index);
                MutableComponent line = Component.empty();
                if (!now.getString("difficulty").isEmpty()) {
                    line.append(difficulty(now.getString("difficulty"))).append(" · ");
                }
                Component phase = PlayerTabs.json(now.getString("phase"));
                if (!phase.getString().isEmpty()) {
                    line.append(phase).append(" · ");
                }
                line.append(clock((now.getLong("elapsed") + ticksSincePoll()) * 50));
                if (now.getLong("left") >= 0) {
                    line.append(" · ").append(tr("time_left",
                            clock(Math.max(0, now.getLong("left") - ticksSincePoll()) * 50)));
                }
                if (now.getBoolean("closing")) {
                    line.append(" · ").append(tr("run_closing"));
                }
                return line;
            }, TEXT));
            lines.item(TextNode.of(Component.literal(players.isEmpty() ? "—" : players)).color(DIM).shadow(false)
                    .maxWidth((int) lineW).ellipsis(true));
            row.item(lines);
            row.item(PlayerTabs.spacer());
            if (end != null) {
                row.item(end);
            }
            row.tooltip(Component.literal(place(run)));
            box.item(row);
        }
        box.item(text(tr("lobbies", status.getInt("lobbies"), status.getInt("queued")), DIM, (int) w));
        if (canEnd && !runs.isEmpty()) {
            box.item(small("gui.eden_ld.moderation.activities.end_all", 60, () -> PlayerTabs.confirm(screen,
                    tr("end_all_confirm", name(entry)), "gui.eden_ld.moderation.activities.end_all",
                    () -> act("force_end", id, null))));
        }

        // Warnings, in words; a lost controller can be forgotten.
        int warnings = status.getInt("warnings");
        if (warnings != 0) {
            box.item(text(tr("warnings"), WARN, (int) w));
            for (ActivityLiveStatus.Warning warning : ActivityLiveStatus.Warning.values()) {
                if ((warnings & warning.bit()) != 0) {
                    box.item(text(Component.literal("⚠ ").append(tr("warning." + warning.name().toLowerCase(Locale.ROOT))),
                            WARN, (int) w));
                }
            }
            if ((warnings & ActivityLiveStatus.Warning.CONTROLLER_MISSING.bit()) != 0
                    && supported.contains(AdminAction.FORGET)) {
                box.item(small("gui.eden_ld.moderation.activities.forget", 50, () -> PlayerTabs.confirm(screen,
                        tr("forget_confirm", name(entry)), "gui.eden_ld.moderation.activities.forget",
                        () -> act("forget", id, null))));
            }
        }
    }

    /** Per difficulty: runs, win rate, median and best clear (best wave for arenas); the last runs. */
    private void fillHistory(Flex box, CompoundTag entry, float w) {
        box.item(text(tr("history"), TEXT, (int) w));
        if (!ClientPlayNetworking.canSend(HubPayloads.ActivityLiveRequest.TYPE)) {
            // An older server: no poll goes out, so no history comes back.
            box.item(text(tr("history_unavailable"), DIM, (int) w));
            return;
        }
        CompoundTag detail = liveFor(entry);
        if (detail == null) {
            box.item(text(Component.translatable("gui.eden_ld.hub.home.loading"), DIM, (int) w));
            return;
        }
        CompoundTag history = detail.getCompound("history");
        ListTag stats = history.getList("stats", Tag.TAG_COMPOUND);
        ListTag recent = history.getList("recent", Tag.TAG_COMPOUND);
        if (stats.isEmpty() && recent.isEmpty()) {
            box.item(text(tr("no_history"), DIM, (int) w));
            return;
        }
        boolean arena = entry.getInt("type") == net.ledok.eden_ld.hub.activity.Activity.Type.ARENA.ordinal();
        for (int i = 0; i < stats.size(); i++) {
            CompoundTag d = stats.getCompound(i);
            MutableComponent line = Component.empty().append(difficulty(d.getString("difficulty")))
                    .append(" · ").append(tr("history_runs", d.getInt("runs")))
                    .append(" · ").append(tr("history_won", d.getInt("rate")));
            if (arena || d.getLong("median") < 0) {
                if (d.getInt("wave") > 0) {
                    line.append(" · ").append(tr("history_wave", d.getInt("wave")));
                }
            } else {
                line.append(" · ").append(tr("history_median", clock(d.getLong("median"))))
                        .append(" · ").append(tr("history_best", clock(d.getLong("best"))));
            }
            box.item(text(line, TEXT, (int) w));
        }
        if (recent.isEmpty()) {
            return;
        }
        box.item(text(tr("recent"), DIM, (int) w));
        for (int i = 0; i < recent.size(); i++) {
            int index = i;
            CompoundTag run = recent.getCompound(i);
            boolean forced = run.getBoolean("forced");
            boolean won = run.getBoolean("won");
            String players = String.join(", ", PlayerTabs.strings(run, "players"));
            LiveLine line = new LiveLine(w, () -> {
                CompoundTag now = liveRecent(entry, index);
                MutableComponent text = Component.empty()
                        .append(PunishSection.ago(liveAt - now.getLong("ago") * 1000L)).append(" · ");
                if (!now.getString("difficulty").isEmpty()) {
                    text.append(difficulty(now.getString("difficulty"))).append(" · ");
                }
                text.append(tr(forced ? "forced" : won ? "won" : "lost").copy()
                        .withStyle(s -> s.withColor((forced ? WARN : won ? GOOD : BAD) & 0xFFFFFF)));
                if (now.getLong("millis") > 0 && !arena) {
                    text.append(" · ").append(clock(now.getLong("millis")));
                }
                if (now.getInt("wave") > 0) {
                    text.append(" · ").append(tr("run_wave", now.getInt("wave")));
                }
                if (!players.isEmpty()) {
                    text.append(" · ").append(Component.literal(players).withStyle(s -> s.withColor(DIM & 0xFFFFFF)));
                }
                return text;
            }, TEXT);
            box.item(line);
        }
    }

    // The timers read the newest poll by position: a poll that changed anything else rebuilt the blocks.

    private CompoundTag liveInstance(CompoundTag entry, int index) {
        CompoundTag detail = liveFor(entry);
        return detail == null ? new CompoundTag()
                : detail.getCompound("status").getList("instances", Tag.TAG_COMPOUND).getCompound(index);
    }

    private CompoundTag liveRun(CompoundTag entry, int index) {
        CompoundTag detail = liveFor(entry);
        return detail == null ? new CompoundTag()
                : detail.getCompound("status").getList("runs", Tag.TAG_COMPOUND).getCompound(index);
    }

    private CompoundTag liveRecent(CompoundTag entry, int index) {
        CompoundTag detail = liveFor(entry);
        return detail == null ? new CompoundTag()
                : detail.getCompound("history").getList("recent", Tag.TAG_COMPOUND).getCompound(index);
    }

    /** "minecraft:overworld · 120 64 -35". */
    private static String place(CompoundTag tag) {
        return tag.getString("dim") + " · " + tag.getInt("x") + " " + tag.getInt("y") + " " + tag.getInt("z");
    }

    private static ActivityLiveStatus.State state(int ordinal) {
        ActivityLiveStatus.State[] states = ActivityLiveStatus.State.values();
        return ordinal >= 0 && ordinal < states.length ? states[ordinal] : ActivityLiveStatus.State.FREE;
    }

    private static int stateColor(ActivityLiveStatus.State state) {
        return switch (state) {
            case FREE -> GOOD;
            case RUNNING -> ACCENT;
            case COOLDOWN -> WARN;
        };
    }

    /** A small framed label in {@code color}. */
    private static Flex chip(Component label, int color) {
        Flex chip = Flex.row().alignItems(Align.CENTER).padding(Insets.of(1, 3, 1, 3));
        chip.sizing(Sizing.content(), Sizing.content());
        chip.background(r -> Shapes.rect(0, 0, r.width(), r.height()).fill((color & 0xFFFFFF) | 0x30000000)
                .stroke(Stroke.of(color, 1)));
        chip.item(TextNode.of(label).color(color).shadow(false).scale(0.8f));
        return chip;
    }

    // ---------------------------------------------------------------- widgets

    /** One line of text that changes by itself (timers): rebuilt whenever the text does. */
    private static final class LiveLine extends Widget {
        private final Supplier<Component> text;
        private final int color;

        LiveLine(float width, Supplier<Component> text, int color) {
            super(width, 10);
            this.text = text;
            this.color = color;
            sizing(Sizing.fixed(width), Sizing.fixed(10));
            bind(() -> text.get());
            tooltip(() -> List.of(text.get()));
        }

        @Override
        protected void rebuild() {
            add(TextNode.of(text.get()).at(0, 1).maxWidth((int) width).ellipsis(true).color(color).shadow(false));
        }
    }

    /**
     * An activity in the list: name, then type · shown/hidden · the level it opens at, with the live
     * markers on the right (▶ runs, ⌛ cooling down, ⚠ warnings, Closed).
     */
    private final class Row extends Widget {
        private final CompoundTag entry;

        Row(CompoundTag entry) {
            super(LIST - 8, ROW_H);
            this.entry = entry;
            sizing(Sizing.fixed(LIST - 8), Sizing.fixed(ROW_H));
            bind(() -> summaries.get(entry.getString("id")));
            tooltip(() -> {
                List<Component> lines = new ArrayList<>(List.of(name(entry), Component.literal(entry.getString("id"))));
                CompoundTag summary = summaries.get(entry.getString("id"));
                if (summary != null && actions(entry).contains(AdminAction.STATUS)) {
                    lines.add(tr("row_live", summary.getInt("running"), summary.getInt("cooldown"),
                            summary.getInt("lobbies"), summary.getInt("queued")));
                }
                return lines;
            });
        }

        @Override
        protected void rebuild() {
            boolean on = entry.getString("id").equals(selected);
            boolean visible = entry.getBoolean("visible");
            add(Shapes.rect(0, 0, width, height).fill(on ? PANEL : 0xFF22252A)
                    .stroke(Stroke.of(on ? ACCENT : hovered ? DIM : LINE, 1)));
            add(TextNode.of(name(entry)).at(4, 3).maxWidth((int) width - 8).ellipsis(true).color(TEXT).shadow(false));
            Component markers = markers();
            float markersWidth = Minecraft.getInstance().font.width(markers) * 0.8f;
            if (markersWidth > 0) {
                add(TextNode.of(markers).at(width - 4, 13).align(TextNode.Align.RIGHT).color(TEXT).shadow(false).scale(0.8f));
            }
            Component state = Component.empty().append(type(entry)).append(" · ")
                    .append(tr(visible ? "state_shown" : "state_hidden"))
                    .append(" · ").append(tr("state_level", unlockLevel(entry)));
            float room = width - 8 - (markersWidth > 0 ? markersWidth + 4 : 0);
            add(TextNode.of(state).at(4, 13).maxWidth((int) (room / 0.8f)).ellipsis(true)
                    .color(visible ? GOOD : DIM).shadow(false).scale(0.8f));
        }

        /** Runs going, instances cooling down, warnings, closed (empty when nothing is up). */
        private Component markers() {
            CompoundTag summary = summaries.get(entry.getString("id"));
            MutableComponent markers = Component.empty();
            if (summary != null) {
                if (summary.getInt("running") > 0) {
                    markers.append(tr("mark_running", summary.getInt("running")).copy()
                            .withStyle(s -> s.withColor(GOOD & 0xFFFFFF))).append(" ");
                }
                if (summary.getInt("cooldown") > 0) {
                    markers.append(tr("mark_cooldown", summary.getInt("cooldown")).copy()
                            .withStyle(s -> s.withColor(WARN & 0xFFFFFF))).append(" ");
                }
                if (summary.getInt("warnings") != 0) {
                    markers.append(tr("mark_warning").copy().withStyle(s -> s.withColor(WARN & 0xFFFFFF))).append(" ");
                }
            }
            // The edited value: the marker follows the toggle at once.
            if (entry.getBoolean("closed")) {
                markers.append(tr("mark_closed").copy().withStyle(s -> s.withColor(BAD & 0xFFFFFF)));
            }
            return markers.getString().isBlank() ? Component.empty() : markers;
        }

        @Override
        public boolean onMouseDown(float x, float y, int button) {
            if (button != 0) {
                return false;
            }
            UiSounds.click();
            flush();
            selected = entry.getString("id");
            // Its live data at once, not at the next poll.
            polledAt = 0;
            screen.rebuildNow();
            return true;
        }

        @Override
        public Cursor cursor() {
            return Cursor.HAND;
        }
    }

    /** A card art choice: a background's thumbnail ("" = none); the picked one is outlined. */
    private final class ArtChoice extends Widget {
        private final CompoundTag entry;
        private final String art;

        ArtChoice(CompoundTag entry, String art) {
            super(ART_W, ART_H);
            this.entry = entry;
            this.art = art;
            sizing(Sizing.fixed(ART_W), Sizing.fixed(ART_H));
            ProfileBackgrounds.Background background = art.isEmpty() ? null : ProfileBackgrounds.get(art);
            tooltip(art.isEmpty() ? tr("no_art") : background == null ? Component.literal(art) : background.name());
        }

        @Override
        protected void rebuild() {
            boolean on = art.equals(entry.getString("card_art"));
            if (art.isEmpty()) {
                add(Shapes.rect(0, 0, width, height).fill(0xFF2C3036));
                add(TextNode.of(tr("no_art"))
                        .at(width / 2, height / 2 - 4).align(TextNode.Align.CENTER).maxWidth((int) width - 4)
                        .ellipsis(true).color(DIM).shadow(false).scale(0.8f));
            } else {
                add(ProfileBackgrounds.thumbnail(art, width, height, 0));
            }
            add(Shapes.rect(0.5f, 0.5f, width - 1, height - 1)
                    .stroke(Stroke.of(on ? ACCENT : hovered ? DIM : LINE, on ? 2 : 1)));
        }

        @Override
        public boolean onMouseDown(float x, float y, int button) {
            if (button != 0) {
                return false;
            }
            UiSounds.click();
            if (!art.equals(entry.getString("card_art"))) {
                entry.putString("card_art", art);
                changed(entry.getString("id"), true);
            }
            return true;
        }

        @Override
        public Cursor cursor() {
            return Cursor.HAND;
        }
    }
}
