package net.ledok.eden_ld.hub.moderation;

import java.util.List;
import java.util.UUID;

/**
 * A player's report to staff (Moderation → Reports): what was filed, and its handling. Plain Java, kept by
 * {@link Reports}; {@code time} and {@code handledAt} are epoch millis.
 */
public final class Report {

    final long id;
    final long time;
    final UUID reporter;
    final String reporterName;
    /** What it's about, e.g. {@code duels_ld:wager}; {@code reference} the filer's own id in it, e.g. {@code #1042}. */
    final String category;
    final String reference;
    final String text;
    final List<String> log;
    boolean handled;
    String handledBy = "";
    long handledAt;
    String note = "";

    Report(long id, long time, UUID reporter, String reporterName, String category, String reference, String text,
           List<String> log) {
        this.id = id;
        this.time = time;
        this.reporter = reporter;
        this.reporterName = reporterName;
        this.category = category;
        this.reference = reference;
        this.text = text;
        this.log = List.copyOf(log);
    }

    public long id() {
        return id;
    }

    public long time() {
        return time;
    }

    public UUID reporter() {
        return reporter;
    }

    public boolean handled() {
        return handled;
    }
}
