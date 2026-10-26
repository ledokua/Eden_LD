package net.ledok.eden_ld.hub.moderation;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
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

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Player reports from other mods ({@code EdenModerationApi.report}, e.g. Duels_LD's wager disputes): filing them
 * (rate limit and caps, {@link ReportRules}), telling staff who may see them ({@code eden_ld.admin.reports}) in chat —
 * at once, and on join while any are open — and the Moderation window's Reports section ({@link AdminTools} area
 * {@code reports}). Server thread.
 */
public final class ReportDesk {

    /** The command a staff chat line's [Open] runs: Moderation on the Reports section. */
    public static final String OPEN_COMMAND = "/hub reports";
    /** What {@code OpenHub} carries to open the Moderation window on Reports. */
    public static final String OPEN_TAB = "moderation:reports";
    /** The list's filters, in the window's order. */
    static final int OPEN = 0, HANDLED = 1, ALL = 2;
    /** Rows sent at most (newest first); the rest are counted. */
    private static final int ROWS = 500;

    /** Each admin's Reports view: the filter and the report picked (-1: the newest). */
    private record View(int filter, long selected) {
    }

    private static final View DEFAULT_VIEW = new View(OPEN, -1);
    private static final Map<UUID, View> VIEWS = new ConcurrentHashMap<>();

    private ReportDesk() {
    }

    public static void initialize() {
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> VIEWS.remove(handler.getPlayer().getUUID()));
        ServerLifecycleEvents.SERVER_STOPPED.register(stopped -> VIEWS.clear());
    }

    /** May see and handle reports. */
    public static boolean mayHandle(ServerPlayer player) {
        return LuckPermsCompat.hasPermission(player, EdenPermissions.REPORTS);
    }

    // ---------------------------------------------------------------- filing

    /** The new report's id, or -1 when refused (blank text, or the reporter's rate limit). */
    public static long file(MinecraftServer server, UUID reporter, String category, String reference, String text,
                            List<String> log) {
        String body = ReportRules.clip(text, ReportRules.MAX_TEXT);
        if (body.isEmpty()) {
            return -1;
        }
        long now = System.currentTimeMillis();
        Reports reports = Reports.get(server);
        if (!reports.mayFile(reporter, now)) {
            return -1;
        }
        Report report = reports.add(reporter, name(server, reporter), ReportRules.clip(category, ReportRules.MAX_LABEL),
                ReportRules.clip(reference, ReportRules.MAX_LABEL), body, ReportRules.log(log), now);
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
        return report.id;
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
    private static String name(MinecraftServer server, UUID player) {
        ServerPlayer online = server.getPlayerList().getPlayer(player);
        if (online != null) {
            return online.getGameProfile().getName();
        }
        var cache = server.getProfileCache();
        return cache == null ? player.toString()
                : cache.get(player).map(com.mojang.authlib.GameProfile::getName).orElse(player.toString());
    }

    // ---------------------------------------------------------------- the Moderation window

    /**
     * The admin's view: {@code filter}, {@code open} (open reports, for the filter's label), {@code total} (matching
     * the filter), {@code rows} (newest first, at most {@link #ROWS}), and the picked report's {@code detail} — kept
     * shown after it leaves the filter (just marked handled), so it can be reopened.
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
            if (!matches(view.filter(), report)) {
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
        tag.putInt("open", store.openCount());
        tag.putInt("total", total);
        tag.put("rows", rows);
        if (selected != null) {
            tag.put("detail", detail(selected));
        }
        return tag;
    }

    private static boolean matches(int filter, Report report) {
        return switch (filter) {
            case OPEN -> !report.handled;
            case HANDLED -> report.handled;
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
        row.putBoolean("handled", report.handled);
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
        return tag;
    }

    /**
     * {@code view} ({@code filter} and/or {@code id}: what the admin looks at — no notice), {@code handle} /
     * {@code reopen} (with the {@code note} as typed), {@code note} (only the note). Null when there's nothing to say.
     */
    @Nullable
    static Component act(ServerPlayer admin, String action, CompoundTag args) {
        UUID uuid = admin.getUUID();
        View view = VIEWS.getOrDefault(uuid, DEFAULT_VIEW);
        if (action.equals("view")) {
            int filter = args.contains("filter") ? Math.max(OPEN, Math.min(ALL, args.getInt("filter"))) : view.filter();
            // Another filter starts from its newest report.
            long selected = args.contains("id") ? args.getLong("id") : args.contains("filter") ? -1 : view.selected();
            VIEWS.put(uuid, new View(filter, selected));
            return null;
        }
        Reports store = Reports.get(admin.server);
        Report report = store.byId(args.getLong("id"));
        if (report == null) {
            return Component.translatable("message.eden_ld.reports.gone");
        }
        VIEWS.put(uuid, new View(view.filter(), report.id));
        String name = admin.getGameProfile().getName();
        String note = ReportRules.clip(args.getString("note"), ReportRules.MAX_NOTE);
        store.setNote(report, note);
        long now = System.currentTimeMillis();
        return switch (action) {
            case "handle" -> {
                store.setHandled(report, true, name, now);
                EdenLD.LOGGER.info("[Reports] {} marked #{} handled{}", name, report.id, note.isEmpty() ? "" : ": " + note);
                yield Component.translatable("message.eden_ld.reports.handled", report.id);
            }
            case "reopen" -> {
                store.setHandled(report, false, name, now);
                EdenLD.LOGGER.info("[Reports] {} reopened #{}", name, report.id);
                yield Component.translatable("message.eden_ld.reports.reopened", report.id);
            }
            default -> Component.translatable("message.eden_ld.reports.note_saved", report.id);
        };
    }
}
