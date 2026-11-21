package net.ledok.eden_ld.hub.moderation;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.ledok.eden_ld.EdenLD;
import net.ledok.eden_ld.command.EdenPermissions;
import net.ledok.eden_ld.compat.LuckPermsCompat;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * One queue for everything players send staff: their own bug reports, complaints about players, ideas and anything
 * else (the hub tab «Звернення», the inventory button, {@code /report}), other mods' reports
 * ({@code EdenModerationApi.report}, e.g. Duels_LD's wager disputes) and suggestions ({@code EdenModerationApi.suggest},
 * e.g. Codex_LD entries, DataRewriter recipes). Filing (rate limit and caps, {@link ReportRules}), evidence for staff
 * (positions, version, and for a complaint what the reporter's chat showed, {@link ChatRecall}), telling staff
 * ({@code eden_ld.admin.reports}) in chat — at once, and on join while any are open — the Moderation window's Reports
 * section ({@link AdminTools} area {@code reports}: filters, status, reply, note, a suggestion's editor), and the
 * reporter's answer: the status and reply in their tab, and Eden mail when either changes. Server thread.
 */
public final class ReportDesk {

    public static final String OPEN_COMMAND = "/hub reports";
    /** The Moderation window on this section ({@code HubPayloads.OpenHub}). */
    public static final String OPEN_TAB = "moderation:reports";
    /** The player's own tab. */
    public static final String PLAYER_TAB = "reports";
    /** Who may file from the hub and {@code /report}: on unless LuckPerms sets it false. */
    public static final String SUBMIT_PERMISSION = "eden_ld.report.submit";

    /** The kinds players file themselves. */
    public static final String BUG = "eden_ld:bug", COMPLAINT = "eden_ld:complaint", IDEA = "eden_ld:idea",
            OTHER = "eden_ld:other";
    public static final List<String> PLAYER_KINDS = List.of(BUG, COMPLAINT, IDEA, OTHER);

    /** Status filters (the Moderation window): to do (new and being looked at), each status, everything. */
    static final int TO_DO = 0, ACCEPTED_ONLY = 1, REJECTED_ONLY = 2, ALL = 3, NEW_ONLY = 4, REVIEWING_ONLY = 5;
    /** Kind filters: everything, Eden's four kinds, other mods' reports and suggestions. */
    static final int ANY_KIND = 0, KIND_BUG = 1, KIND_COMPLAINT = 2, KIND_IDEA = 3, KIND_OTHER = 4, KIND_MODS = 5;

    private static final int ROWS = 500;
    /** The player's own list. */
    private static final int MINE = 100;
    private static final String VERSION = FabricLoader.getInstance().getModContainer(EdenLD.MOD_ID)
            .map(mod -> mod.getMetadata().getVersion().getFriendlyString()).orElse("?");

    private record View(int filter, int kind, long selected) {
    }

    private static final View DEFAULT_VIEW = new View(TO_DO, ANY_KIND, -1);
    private static final Map<UUID, View> VIEWS = new ConcurrentHashMap<>();

    /** Opens a suggestion of a category in its mod's review screen, for a staff member. */
    public interface Editor {
        void open(ServerPlayer staff, long id, UUID author, String authorName, String reference, String text, String payload);
    }

    private static final Map<String, Editor> EDITORS = new ConcurrentHashMap<>();
    /** Called when a report's status or reply changed: the player's tab follows ({@code ReportsHub}). */
    private static Consumer<UUID> changed = player -> { };

    private ReportDesk() {
    }

    public static void initialize() {
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                net.ledok.eden_ld.util.OnServerThread.run(server, () -> VIEWS.remove(handler.getPlayer().getUUID())));
        ServerLifecycleEvents.SERVER_STOPPED.register(stopped -> {
            VIEWS.clear();
            ChatRecall.clear();
        });
    }

    static void onChanged(Consumer<UUID> listener) {
        changed = listener;
    }

    /** May see and handle reports. */
    public static boolean mayHandle(ServerPlayer player) {
        return LuckPermsCompat.hasPermission(player, EdenPermissions.REPORTS);
    }

    /** May file from the hub and {@code /report}: everyone, unless LuckPerms takes it away. */
    public static boolean maySubmit(ServerPlayer player) {
        return LuckPermsCompat.hasPermissionOr(player, SUBMIT_PERMISSION, true);
    }

    // ---------------------------------------------------------------- filing

    /** The new report's id, or -1 when refused (blank text, the reporter's rate limit, or too many open reports). */
    public static long file(MinecraftServer server, UUID reporter, String category, String reference, String text,
                            List<String> log) {
        Report report = add(server, reporter, category, reference, text, log, null, "", "");
        return report == null ? -1 : report.id;
    }

    /** Files and tells staff; null when refused (blank text, the reporter's rate limit, or too many open reports). */
    @Nullable
    private static Report add(MinecraftServer server, UUID reporter, String category, String reference, String text,
                              List<String> log, @Nullable UUID target, String targetName, String payload) {
        String body = ReportRules.clip(text, ReportRules.MAX_TEXT);
        if (body.isEmpty()) {
            return null;
        }
        long now = System.currentTimeMillis();
        Reports reports = Reports.get(server);
        if (!reports.mayFile(reporter, now)) {
            return null;
        }
        // Open reports are never pruned any more (review finding 30), so the store's bound is here: past MAX_OPEN open
        // ones nothing more is filed. Players stop earlier (submit), so this only catches other mods' filings.
        int open = reports.openCount();
        if (!ReportRules.mayOpen(open, 0, false)) {
            alertFull(server, open, now);
            return null;
        }
        Report report = reports.add(reporter, name(server, reporter), ReportRules.clip(category, ReportRules.MAX_LABEL),
                ReportRules.clip(reference, ReportRules.MAX_LABEL), body, ReportRules.log(log), now);
        if (target != null || !payload.isEmpty()) {
            reports.details(report, target, targetName, payload.length() > ReportRules.MAX_PAYLOAD
                    ? payload.substring(0, ReportRules.MAX_PAYLOAD) : payload);
        }
        EdenLD.LOGGER.info("[Reports] #{} ({}) from {}", report.id, label(report), report.reporterName);
        Component line = Component.translatable("message.eden_ld.reports.new", report.id, label(report), report.reporterName)
                .withStyle(ChatFormatting.GOLD)
                .append(" ")
                .append(openButton());
        for (ServerPlayer staff : server.getPlayerList().getPlayers()) {
            if (mayHandle(staff)) {
                staff.sendSystemMessage(line);
            }
        }
        changed.accept(reporter);
        return report;
    }

    /**
     * A player's own submission from the hub ({@code kind} one of {@link #PLAYER_KINDS}; {@code target} a complaint's
     * player). Evidence is added for staff. Null when filed, else why not (for the player).
     */
    @Nullable
    public static Component submit(ServerPlayer player, String kind, @Nullable UUID target, String text) {
        if (!maySubmit(player)) {
            return Component.translatable("message.eden_ld.reports.not_allowed");
        }
        if (!PLAYER_KINDS.contains(kind)) {
            return Component.translatable("message.eden_ld.reports.bad_kind");
        }
        if (ReportRules.clip(text, ReportRules.MAX_TEXT).isEmpty()) {
            return Component.translatable("message.eden_ld.reports.empty");
        }
        MinecraftServer server = player.server;
        boolean complaint = kind.equals(COMPLAINT);
        if (complaint && (target == null || target.equals(player.getUUID()))) {
            return Component.translatable("message.eden_ld.reports.no_target");
        }
        // Caps on open reports (review finding 30): one player can't hold more than MAX_OPEN_PER_REPORTER, and players
        // stop PLAYER_HEADROOM short of the store's cap, so other mods' filings still fit.
        Reports store = Reports.get(server);
        int open = store.openCount();
        if (!ReportRules.mayOpen(open, 0, true)) {
            alertFull(server, open, System.currentTimeMillis());
            return Component.translatableWithFallback("message.eden_ld.reports.queue_full",
                    "Staff have too many open submissions right now. Please try again later.");
        }
        if (!ReportRules.mayOpen(open, ReportRules.openBy(store.all(), player.getUUID(), PLAYER_KINDS), true)) {
            return Component.translatableWithFallback("message.eden_ld.reports.too_many_open",
                    "You have %s open submissions. Wait for staff to answer them, or close the solved ones.",
                    ReportRules.MAX_OPEN_PER_REPORTER);
        }
        String targetName = complaint ? name(server, target) : "";
        List<String> evidence = new ArrayList<>();
        evidence.add("Reporter: " + player.getGameProfile().getName() + " @ " + where(player));
        evidence.add("Time: " + Instant.now() + " (Eden_LD " + VERSION + ")");
        if (complaint) {
            ServerPlayer online = server.getPlayerList().getPlayer(target);
            evidence.add("Target: " + targetName + (online != null ? " @ " + where(online) : " — offline"));
            List<String> chat = ChatRecall.recall(player.getUUID(), targetName);
            evidence.add("Chat the reporter received (last " + chat.size() + " lines):");
            evidence.addAll(chat);
        }
        Report report = add(server, player.getUUID(), kind, complaint ? targetName : "", text, evidence,
                complaint ? target : null, targetName, "");
        if (report == null) {
            return Component.translatable("message.eden_ld.reports.too_many", ReportRules.RATE_LIMIT);
        }
        return null;
    }

    /**
     * Another mod's suggestion ({@code EdenModerationApi.suggest}): {@code payload} its proposed content, opaque here.
     * The new id, or -1 when refused: blank text, the rate limit, or an open suggestion of the same category and
     * reference from this player.
     */
    public static long suggest(MinecraftServer server, UUID author, String category, String reference, String text,
                               String payload, List<String> log) {
        Reports reports = Reports.get(server);
        if (reports.hasOpen(author, category, reference)) {
            return -1;
        }
        Report report = add(server, author, category, reference, text, log, null, "", payload == null ? "" : payload);
        return report == null ? -1 : report.id;
    }

    /** A mod's review screen for its category ({@code EdenModerationApi.registerSuggestionEditor}). */
    public static void registerEditor(String category, Editor editor) {
        EDITORS.put(category, editor);
    }

    /**
     * The owning mod's verdict on a suggestion: accepted or rejected with {@code reason} (shown to the author and
     * mailed). False when there's no such report.
     */
    public static boolean resolve(MinecraftServer server, long id, boolean accepted, String reason, String by) {
        Reports store = Reports.get(server);
        Report report = store.byId(id);
        if (report == null) {
            return false;
        }
        update(server, store, report, accepted ? Report.ACCEPTED : Report.REJECTED,
                ReportRules.clip(reason, ReportRules.MAX_REPLY), null, by);
        return true;
    }

    /** "dimension x y z". */
    private static String where(ServerPlayer player) {
        return player.level().dimension().location() + " " + player.blockPosition().getX() + " "
                + player.blockPosition().getY() + " " + player.blockPosition().getZ();
    }

    // ---------------------------------------------------------------- staff alerts

    /** When staff were last told the store is full of open reports (at most every ten minutes). */
    private static volatile long lastFullAlert;

    /** A filing was refused because too many reports are open (review finding 30): the log and online staff hear it. */
    private static void alertFull(MinecraftServer server, int open, long now) {
        if (now - lastFullAlert < ReportRules.RATE_WINDOW_MILLIS) {
            return;
        }
        lastFullAlert = now;
        EdenLD.LOGGER.warn("[Reports] {} reports are open: new filings are refused until staff handle some.", open);
        Component line = Component.translatableWithFallback("message.eden_ld.reports.store_full",
                        "%s submissions are open: new ones are refused until some are handled.", open)
                .withStyle(ChatFormatting.RED)
                .append(" ")
                .append(openButton());
        for (ServerPlayer staff : server.getPlayerList().getPlayers()) {
            if (mayHandle(staff)) {
                staff.sendSystemMessage(line);
            }
        }
    }

    /** "1 open report [Open]" / "N open reports [Open]" to staff who may handle them, while any are open. */
    public static void joinLine(ServerPlayer player) {
        if (!mayHandle(player)) {
            return;
        }
        int open = Reports.get(player.server).openCount();
        if (open > 0) {
            player.sendSystemMessage(Component.translatable(open == 1 ? "message.eden_ld.reports.open_one"
                            : "message.eden_ld.reports.open_many", open)
                    .withStyle(ChatFormatting.GOLD)
                    .append(" ")
                    .append(openButton()));
        }
    }

    private static Component openButton() {
        return Component.translatable("message.eden_ld.reports.open_button")
                .withStyle(ChatFormatting.AQUA, ChatFormatting.UNDERLINE)
                .withStyle(style -> style.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, OPEN_COMMAND))
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                Component.translatable("message.eden_ld.reports.open_hover"))));
    }

    /** "duels_ld:wager #1042", or the category alone. */
    private static String label(Report report) {
        return report.reference.isEmpty() ? report.category : report.category + " " + report.reference;
    }

    /** The player's name: online, else the server's profile cache, else the uuid. */
    static String name(MinecraftServer server, UUID player) {
        ServerPlayer online = server.getPlayerList().getPlayer(player);
        if (online != null) {
            return online.getGameProfile().getName();
        }
        var cache = server.getProfileCache();
        return cache == null ? player.toString()
                : cache.get(player).map(com.mojang.authlib.GameProfile::getName).orElse(player.toString());
    }

    // ---------------------------------------------------------------- the conversation (1.3.93)

    /** Messages one report may hold; and the reporter's pause between their own messages. */
    static final int MAX_MESSAGES = 60;
    private static final long PLAYER_PAUSE_MILLIS = 10_000;
    private static final Map<UUID, Long> LAST_PLAYER_MESSAGE = new ConcurrentHashMap<>();

    /** The reporter writes in their own open submission. Null when added, else why not. */
    @Nullable
    public static Component playerMessage(ServerPlayer player, long id, String text) {
        // eden_ld.report.submit covers replies too (review finding 31): with it taken away a player could still ping
        // every staff member from their open threads. Closing and reading stay allowed.
        if (!maySubmit(player)) {
            return Component.translatable("message.eden_ld.reports.not_allowed");
        }
        Reports store = Reports.get(player.server);
        Report report = store.byId(id);
        if (report == null || !report.reporter.equals(player.getUUID())) {
            return Component.translatable("message.eden_ld.reports.gone");
        }
        if (report.handled()) {
            return Component.translatable("message.eden_ld.reports.closed");
        }
        String body = ReportRules.clip(text, ReportRules.MAX_TEXT);
        if (body.isEmpty()) {
            return Component.translatable("message.eden_ld.reports.empty");
        }
        if (report.messages.size() >= MAX_MESSAGES) {
            return Component.translatable("message.eden_ld.reports.thread_full");
        }
        long now = System.currentTimeMillis();
        Long last = LAST_PLAYER_MESSAGE.get(player.getUUID());
        if (last != null && now - last < PLAYER_PAUSE_MILLIS) {
            return Component.translatable("message.eden_ld.reports.slow_down");
        }
        LAST_PLAYER_MESSAGE.put(player.getUUID(), now);
        store.addMessage(report, new Report.Message(now, player.getGameProfile().getName(), Report.PLAYER, body));
        store.markRead(report, now);
        Component line = Component.translatable("message.eden_ld.reports.player_reply", report.id, report.reporterName)
                .withStyle(ChatFormatting.GOLD).append(" ").append(openButton());
        for (ServerPlayer staff : player.server.getPlayerList().getPlayers()) {
            if (mayHandle(staff)) {
                staff.sendSystemMessage(line);
            }
        }
        changed.accept(report.reporter);
        return null;
    }

    /** The reporter closes their own submission ("Питання вирішено"). Null when closed, else why not. */
    @Nullable
    public static Component playerClose(ServerPlayer player, long id) {
        Reports store = Reports.get(player.server);
        Report report = store.byId(id);
        if (report == null || !report.reporter.equals(player.getUUID())) {
            return Component.translatable("message.eden_ld.reports.gone");
        }
        if (report.handled()) {
            return null;
        }
        long now = System.currentTimeMillis();
        String name = player.getGameProfile().getName();
        store.addMessage(report, new Report.Message(now, name, Report.SYSTEM, "closed_by_player"));
        store.setStatus(report, Report.ACCEPTED, name, now);
        store.markRead(report, now);
        EdenLD.LOGGER.info("[Reports] {} closed their #{}", name, report.id);
        changed.accept(report.reporter);
        return null;
    }

    /** The reporter opened the thread: staff replies so far are read. */
    public static void playerRead(ServerPlayer player, long id) {
        Reports store = Reports.get(player.server);
        Report report = store.byId(id);
        if (report != null && report.reporter.equals(player.getUUID()) && report.unreadForPlayer()) {
            store.markRead(report, System.currentTimeMillis());
            changed.accept(report.reporter);
        }
    }

    // ---------------------------------------------------------------- the reporter's side

    /** The player's own submissions, newest first: what their tab shows. */
    static List<Report> mine(MinecraftServer server, UUID player) {
        return Reports.get(server).byReporter(player, MINE);
    }

    /** Status and/or reply changed: stored, the reporter's tab follows, and they get mail. */
    private static void update(MinecraftServer server, Reports store, Report report, int status, @Nullable String reply,
                               @Nullable String note, String by) {
        long now = System.currentTimeMillis();
        boolean statusChanged = store.setStatus(report, status, by, now);
        boolean replyChanged = reply != null && store.setReply(report, reply);
        if (note != null) {
            store.setNote(report, note);
        }
        if (statusChanged || replyChanged) {
            EdenLD.LOGGER.info("[Reports] {} set #{} to status {}{}", by, report.id, report.status,
                    report.reply.isEmpty() ? "" : " (reply: " + report.reply + ")");
            net.ledok.eden_ld.hub.mail.MailHub.reportMail(report.reporter, report.id, report.status,
                    excerpt(report.text), report.reply);
            changed.accept(report.reporter);
        }
    }

    private static String excerpt(String text) {
        String line = text.replace('\n', ' ');
        return line.length() > 80 ? line.substring(0, 79) + "…" : line;
    }

    // ---------------------------------------------------------------- the Moderation window

    /**
     * The admin's view: {@code filter} and {@code kind}, {@code open} (to-do count), {@code total} (matching),
     * {@code rows} (newest first, at most {@link #ROWS}), and the picked report's {@code detail} — kept shown after it
     * leaves the filter (just handled), so it can be changed again.
     */
    static CompoundTag data(ServerPlayer admin, MinecraftServer server) {
        View view = VIEWS.getOrDefault(admin.getUUID(), DEFAULT_VIEW);
        Reports store = Reports.get(server);
        List<Report> all = store.all();
        ListTag rows = new ListTag();
        int total = 0;
        Report first = null;
        for (int i = all.size() - 1; i >= 0; i--) {
            Report report = all.get(i);
            if (!matches(view.filter(), report) || !matchesKind(view.kind(), report)) {
                continue;
            }
            total++;
            if (first == null) {
                first = report;
            }
            if (rows.size() < ROWS) {
                rows.add(row(report));
            }
        }
        Report selected = view.selected() >= 0 ? store.byId(view.selected()) : null;
        if (selected == null) {
            selected = first;
        }
        CompoundTag tag = new CompoundTag();
        tag.putInt("filter", view.filter());
        tag.putInt("kind", view.kind());
        tag.putInt("open", store.openCount());
        tag.putInt("total", total);
        tag.put("rows", rows);
        if (selected != null) {
            tag.put("detail", detail(selected));
        }
        return tag;
    }

    /**
     * Staff to where the reporter (or a complaint's target) was when it was filed: the evidence's
     * "Reporter: name @ dimension x y z" / "Target: …" line.
     */
    @Nullable
    private static Component teleport(ServerPlayer admin, Report report, boolean target) {
        String prefix = target ? "Target: " : "Reporter: ";
        for (String line : report.log) {
            if (!line.startsWith(prefix) || !line.contains(" @ ")) {
                continue;
            }
            String[] parts = line.substring(line.indexOf(" @ ") + 3).trim().split(" ");
            if (parts.length < 4) {
                break;
            }
            net.minecraft.resources.ResourceLocation dimension = net.minecraft.resources.ResourceLocation.tryParse(parts[0]);
            var level = dimension == null ? null : admin.server.getLevel(net.minecraft.resources.ResourceKey.create(
                    net.minecraft.core.registries.Registries.DIMENSION, dimension));
            try {
                if (level != null) {
                    admin.teleportTo(level, Integer.parseInt(parts[1]) + 0.5, Integer.parseInt(parts[2]),
                            Integer.parseInt(parts[3]) + 0.5, admin.getYRot(), admin.getXRot());
                    EdenLD.LOGGER.info("[Reports] {} teleported to #{}'s {}", admin.getGameProfile().getName(), report.id,
                            target ? "target" : "reporter");
                    return null;
                }
            } catch (NumberFormatException ignored) {
                break;
            }
        }
        return Component.translatable("message.eden_ld.reports.no_position");
    }

    private static boolean matches(int filter, Report report) {
        return switch (filter) {
            case TO_DO -> !report.handled();
            case ACCEPTED_ONLY -> report.status == Report.ACCEPTED;
            case REJECTED_ONLY -> report.status == Report.REJECTED;
            case NEW_ONLY -> report.status == Report.NEW;
            case REVIEWING_ONLY -> report.status == Report.REVIEWING;
            default -> true;
        };
    }

    private static boolean matchesKind(int kind, Report report) {
        return switch (kind) {
            case KIND_BUG -> report.category.equals(BUG);
            case KIND_COMPLAINT -> report.category.equals(COMPLAINT);
            case KIND_IDEA -> report.category.equals(IDEA);
            case KIND_OTHER -> report.category.equals(OTHER);
            case KIND_MODS -> !PLAYER_KINDS.contains(report.category);
            default -> true;
        };
    }

    private static CompoundTag row(Report report) {
        CompoundTag row = new CompoundTag();
        row.putLong("id", report.id);
        row.putLong("time", report.time);
        row.putString("reporter", report.reporterName);
        row.putString("category", report.category);
        row.putString("reference", report.reference);
        row.putBoolean("handled", report.handled());
        row.putInt("status", report.status);
        row.putString("assignee", report.assignee);
        row.putBoolean("waiting", !report.handled() && report.waitingForStaff());
        return row;
    }

    private static CompoundTag detail(Report report) {
        CompoundTag tag = row(report);
        tag.putUUID("reporter_uuid", report.reporter);
        tag.putString("text", report.text);
        ListTag log = new ListTag();
        report.log.forEach(line -> log.add(StringTag.valueOf(line)));
        tag.put("log", log);
        tag.putString("handled_by", report.handledBy);
        tag.putLong("handled_at", report.handledAt);
        tag.putString("note", report.note);
        tag.putString("reply", report.reply);
        tag.putString("target", report.targetName);
        tag.putInt("payload", report.payload.length());
        tag.putBoolean("editor", EDITORS.containsKey(report.category));
        ListTag messages = new ListTag();
        // Before 1.3.93 a report had one note and one reply: shown as the thread's first messages.
        if (!report.note.isEmpty()) {
            messages.add(message(new Report.Message(report.handledAt, report.handledBy, Report.NOTE, report.note)));
        }
        if (!report.reply.isEmpty()) {
            messages.add(message(new Report.Message(report.handledAt, report.handledBy, Report.STAFF, report.reply)));
        }
        report.messages.forEach(message -> messages.add(message(message)));
        tag.put("messages", messages);
        tag.putBoolean("has_reporter_pos", hasPosition(report, "Reporter: "));
        tag.putBoolean("has_target_pos", hasPosition(report, "Target: "));
        return tag;
    }

    private static CompoundTag message(Report.Message message) {
        CompoundTag tag = new CompoundTag();
        tag.putLong("time", message.time());
        tag.putString("author", message.author());
        tag.putInt("role", message.role());
        tag.putString("text", message.text());
        return tag;
    }

    private static boolean hasPosition(Report report, String prefix) {
        for (String line : report.log) {
            if (line.startsWith(prefix) && line.contains(" @ ")) {
                return true;
            }
        }
        return false;
    }

    /**
     * {@code view} ({@code filter}, {@code kind} and/or {@code id}: what the admin looks at — no notice); {@code save}
     * ({@code status}, {@code reply}, {@code note}); the older {@code handle} / {@code reopen} / {@code note};
     * {@code editor} (the suggestion's review screen). Null when there's nothing to say.
     */
    @Nullable
    static Component act(ServerPlayer admin, String action, CompoundTag args) {
        UUID uuid = admin.getUUID();
        View view = VIEWS.getOrDefault(uuid, DEFAULT_VIEW);
        if (action.equals("view")) {
            int filter = args.contains("filter") ? Math.max(TO_DO, Math.min(REVIEWING_ONLY, args.getInt("filter")))
                    : view.filter();
            int kind = args.contains("kind") ? Math.max(ANY_KIND, Math.min(KIND_MODS, args.getInt("kind"))) : view.kind();
            // Another filter starts from its newest report.
            boolean refiltered = args.contains("filter") || args.contains("kind");
            long selected = args.contains("id") ? args.getLong("id") : refiltered ? -1 : view.selected();
            VIEWS.put(uuid, new View(filter, kind, selected));
            return null;
        }
        Reports store = Reports.get(admin.server);
        Report report = store.byId(args.getLong("id"));
        if (report == null) {
            return Component.translatable("message.eden_ld.reports.gone");
        }
        VIEWS.put(uuid, new View(view.filter(), view.kind(), report.id));
        String name = admin.getGameProfile().getName();
        // Fields the client didn't send stay as they are (1.3.93's Save sends only the status).
        String note = args.contains("note") ? ReportRules.clip(args.getString("note"), ReportRules.MAX_NOTE) : null;
        return switch (action) {
            case "save" -> {
                String reply = args.contains("reply") ? ReportRules.clip(args.getString("reply"), ReportRules.MAX_REPLY) : null;
                int status = args.contains("status") ? args.getInt("status") : report.status;
                update(admin.server, store, report, status, reply, note, name);
                yield Component.translatable("message.eden_ld.reports.saved", report.id);
            }
            case "message" -> {
                // A reply to the player (mailed) or a staff-only note; with "status", that too.
                String text = ReportRules.clip(args.getString("text"), ReportRules.MAX_REPLY);
                boolean noteOnly = args.getBoolean("note");
                if (text.isEmpty()) {
                    yield Component.translatable("message.eden_ld.reports.empty");
                }
                if (report.messages.size() >= MAX_MESSAGES) {
                    yield Component.translatable("message.eden_ld.reports.thread_full");
                }
                long now = System.currentTimeMillis();
                store.addMessage(report, new Report.Message(now, name, noteOnly ? Report.NOTE : Report.STAFF, text));
                if (!noteOnly) {
                    if (report.status == Report.NEW) {
                        store.setStatus(report, Report.REVIEWING, name, now);
                    }
                    EdenLD.LOGGER.info("[Reports] {} replied to #{}: {}", name, report.id, text);
                    net.ledok.eden_ld.hub.mail.MailHub.reportReplyMail(report.reporter, report.id, excerpt(report.text),
                            text);
                    changed.accept(report.reporter);
                }
                yield null;
            }
            case "assign" -> {
                store.setAssignee(report, report.assignee.equals(name) ? "" : name);
                yield null;
            }
            case "tp" -> teleport(admin, report, args.getBoolean("target"));
            case "handle" -> {
                update(admin.server, store, report, Report.ACCEPTED, null, note, name);
                yield Component.translatable("message.eden_ld.reports.handled", report.id);
            }
            case "reopen" -> {
                update(admin.server, store, report, Report.REVIEWING, null, note, name);
                yield Component.translatable("message.eden_ld.reports.reopened", report.id);
            }
            case "editor" -> {
                Editor editor = EDITORS.get(report.category);
                if (editor == null) {
                    yield Component.translatable("message.eden_ld.reports.no_editor", report.category);
                }
                try {
                    editor.open(admin, report.id, report.reporter, report.reporterName, report.reference, report.text,
                            report.payload);
                } catch (RuntimeException e) {
                    EdenLD.LOGGER.error("[Reports] The editor for {} failed on #{}", report.category, report.id, e);
                    yield Component.translatable("message.eden_ld.reports.editor_failed", report.category);
                }
                yield null;
            }
            default -> {
                if (note != null) {
                    store.setNote(report, note);
                }
                yield Component.translatable("message.eden_ld.reports.note_saved", report.id);
            }
        };
    }
}
