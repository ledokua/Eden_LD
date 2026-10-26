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
            if (!report.handled) {
                open++;
            }
        }
        return open;
    }

    /** Marks a report handled (by {@code by}) or open again; false when nothing changed. */
    boolean setHandled(Report report, boolean handled, String by, long now) {
        if (report.handled == handled) {
            return false;
        }
        report.handled = handled;
        report.handledBy = handled ? by : "";
        report.handledAt = handled ? now : 0;
        setDirty();
        return true;
    }

    void setNote(Report report, String note) {
        if (!report.note.equals(note)) {
            report.note = note;
            setDirty();
        }
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
            if (report.handled) {
                r.putBoolean("handled", true);
                r.putString("handled_by", report.handledBy);
                r.putLong("handled_at", report.handledAt);
            }
            if (!report.note.isEmpty()) {
                r.putString("note", report.note);
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
            report.handled = r.getBoolean("handled");
            report.handledBy = r.getString("handled_by");
            report.handledAt = r.getLong("handled_at");
            report.note = r.getString("note");
            data.reports.add(report);
            highest = Math.max(highest, report.id);
        }
        data.nextId = Math.max(tag.getLong("next_id"), highest + 1);
        return data;
    }
}
