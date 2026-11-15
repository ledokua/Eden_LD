package net.ledok.eden_ld.hub.moderation;

import net.ledok.eden_ld.EdenData;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Every player report (Moderation → Reports), oldest first, at most {@link ReportRules#KEEP}. Kept in
 * {@code data/eden_ld/reports.dat}. Server thread.
 */
public final class Reports extends SavedData {

    private static final Factory<Reports> FACTORY = new Factory<>(Reports::new, Reports::load, null);

    private final List<Report> reports = new ArrayList<>();
    private long nextId = 1;

    public static Reports get(MinecraftServer server) {
        return EdenData.get(server, FACTORY, "reports");
    }

    /** Files a report (already checked and cut to the caps); returns it. */
    Report add(UUID reporter, String reporterName, String category, String reference, String text, List<String> log,
               long now) {
        Report report = new Report(nextId++, now, reporter, reporterName, category, reference, text, log);
        reports.add(report);
        ReportRules.prune(reports, now, ReportRules.KEEP);
        setDirty();
        return report;
    }

    boolean mayFile(UUID reporter, long now) {
        return ReportRules.mayFile(reports, reporter, now);
    }

    /** Oldest first, read-only. */
    List<Report> all() {
        return Collections.unmodifiableList(reports);
    }

    @Nullable
    Report byId(long id) {
        for (Report report : reports) {
            if (report.id == id) {
                return report;
            }
        }
        return null;
    }

    int openCount() {
        int open = 0;
        for (Report report : reports) {
            if (!report.handled()) {
                open++;
            }
        }
        return open;
    }

    /** Sets the status (by {@code by}); false when it was already that. */
    boolean setStatus(Report report, int status, String by, long now) {
        int valid = Report.validStatus(status);
        if (report.status == valid) {
            return false;
        }
        report.status = valid;
        report.handledBy = by;
        report.handledAt = now;
        setDirty();
        return true;
    }

    void setNote(Report report, String note) {
        if (!report.note.equals(note)) {
            report.note = note;
            setDirty();
        }
    }

    /** Staff's answer to the player; false when unchanged. */
    boolean setReply(Report report, String reply) {
        if (report.reply.equals(reply)) {
            return false;
        }
        report.reply = reply;
        setDirty();
        return true;
    }

    /** Adds the complaint target and the suggestion payload to a just-filed report. */
    void details(Report report, @Nullable UUID target, String targetName, String payload) {
        report.target = target;
        report.targetName = targetName;
        report.payload = payload;
        setDirty();
    }

    void addMessage(Report report, Report.Message message) {
        report.messages.add(message);
        setDirty();
    }

    void setAssignee(Report report, String assignee) {
        report.assignee = assignee;
        setDirty();
    }

    void markRead(Report report, long now) {
        report.readAt = now;
        setDirty();
    }

    /** Everything {@code reporter} filed, newest first. */
    List<Report> byReporter(UUID reporter, int max) {
        List<Report> mine = new ArrayList<>();
        for (int i = reports.size() - 1; i >= 0 && mine.size() < max; i--) {
            if (reports.get(i).reporter.equals(reporter)) {
                mine.add(reports.get(i));
            }
        }
        return mine;
    }

    /** Whether {@code reporter} has an unhandled report of {@code category} about {@code reference}. */
    boolean hasOpen(UUID reporter, String category, String reference) {
        for (Report report : reports) {
            if (!report.handled() && report.reporter.equals(reporter) && report.category.equals(category)
                    && report.reference.equals(reference)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putLong("next_id", nextId);
        ListTag list = new ListTag();
        for (Report report : reports) {
            CompoundTag r = new CompoundTag();
            r.putLong("id", report.id);
            r.putLong("time", report.time);
            r.putUUID("reporter", report.reporter);
            r.putString("reporter_name", report.reporterName);
            r.putString("category", report.category);
            r.putString("reference", report.reference);
            r.putString("text", report.text);
            ListTag log = new ListTag();
            report.log.forEach(line -> log.add(StringTag.valueOf(line)));
            r.put("log", log);
            r.putInt("status", report.status);
            if (!report.handledBy.isEmpty()) {
                r.putString("handled_by", report.handledBy);
                r.putLong("handled_at", report.handledAt);
            }
            if (!report.note.isEmpty()) {
                r.putString("note", report.note);
            }
            if (!report.reply.isEmpty()) {
                r.putString("reply", report.reply);
            }
            if (report.target != null) {
                r.putUUID("target", report.target);
                r.putString("target_name", report.targetName);
            }
            if (!report.payload.isEmpty()) {
                r.putString("payload", report.payload);
            }
            if (!report.messages.isEmpty()) {
                ListTag messages = new ListTag();
                for (Report.Message message : report.messages) {
                    CompoundTag m = new CompoundTag();
                    m.putLong("time", message.time());
                    m.putString("author", message.author());
                    m.putInt("role", message.role());
                    m.putString("text", message.text());
                    messages.add(m);
                }
                r.put("messages", messages);
            }
            if (!report.assignee.isEmpty()) {
                r.putString("assignee", report.assignee);
            }
            if (report.readAt > 0) {
                r.putLong("read_at", report.readAt);
            }
            list.add(r);
        }
        tag.put("reports", list);
        return tag;
    }

    private static Reports load(CompoundTag tag, HolderLookup.Provider registries) {
        Reports data = new Reports();
        ListTag list = tag.getList("reports", Tag.TAG_COMPOUND);
        long highest = 0;
        for (int i = 0; i < list.size(); i++) {
            CompoundTag r = list.getCompound(i);
            if (!r.hasUUID("reporter")) {
                continue;
            }
            List<String> log = new ArrayList<>();
            ListTag lines = r.getList("log", Tag.TAG_STRING);
            for (int j = 0; j < lines.size(); j++) {
                log.add(lines.getString(j));
            }
            Report report = new Report(r.getLong("id"), r.getLong("time"), r.getUUID("reporter"),
                    r.getString("reporter_name"), r.getString("category"), r.getString("reference"), r.getString("text"), log);
            // Before statuses: handled meant done (accepted).
            report.status = r.contains("status") ? Report.validStatus(r.getInt("status"))
                    : r.getBoolean("handled") ? Report.ACCEPTED : Report.NEW;
            report.handledBy = r.getString("handled_by");
            report.handledAt = r.getLong("handled_at");
            report.note = r.getString("note");
            report.reply = r.getString("reply");
            if (r.hasUUID("target")) {
                report.target = r.getUUID("target");
                report.targetName = r.getString("target_name");
            }
            report.payload = r.getString("payload");
            ListTag messages = r.getList("messages", Tag.TAG_COMPOUND);
            for (int j = 0; j < messages.size(); j++) {
                CompoundTag m = messages.getCompound(j);
                report.messages.add(new Report.Message(m.getLong("time"), m.getString("author"), m.getInt("role"),
                        m.getString("text")));
            }
            report.assignee = r.getString("assignee");
            report.readAt = r.getLong("read_at");
            data.reports.add(report);
            highest = Math.max(highest, report.id);
        }
        data.nextId = Math.max(tag.getLong("next_id"), highest + 1);
        return data;
    }
}
