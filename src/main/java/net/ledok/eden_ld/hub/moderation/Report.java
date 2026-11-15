package net.ledok.eden_ld.hub.moderation;

import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;

/**
 * Something a player sent staff (Moderation → Reports; the player's hub tab «Звернення»): a bug, a complaint about a
 * player, an idea, anything else, or another mod's report or suggestion. Plain Java, kept by {@link Reports};
 * {@code time} and {@code statusAt} are epoch millis.
 */
public final class Report {

    /** Statuses, as stored and sent: new, being looked at, accepted, rejected. The last two count as handled. */
    public static final int NEW = 0, REVIEWING = 1, ACCEPTED = 2, REJECTED = 3;

    final long id;
    final long time;
    final UUID reporter;
    final String reporterName;
    /**
     * What it's about: Eden's own kinds ({@link ReportDesk#BUG}, {@code COMPLAINT}, {@code IDEA}, {@code OTHER}) or a
     * mod's, e.g. {@code duels_ld:wager}, {@code codex_ld:entry}; {@code reference} the filer's own id in it.
     */
    final String category;
    final String reference;
    final String text;
    /** Evidence and the filing mod's details, for staff only. */
    final List<String> log;
    int status = NEW;
    /** Who last set the status, and when. */
    String handledBy = "";
    long handledAt;
    /** Staff's own note (never shown to the player). */
    String note = "";
    /** Staff's answer to the player (shown in their tab and mailed). */
    String reply = "";
    /** A complaint's player. */
    @Nullable
    UUID target;
    String targetName = "";
    /** A suggestion's proposed content, opaque to Eden (the owning mod's editor reads it). */
    String payload = "";

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

    public int status() {
        return status;
    }

    /** Accepted or rejected: nothing left for staff to do. */
    public boolean handled() {
        return status >= ACCEPTED;
    }

    static int validStatus(int status) {
        return Math.max(NEW, Math.min(REJECTED, status));
    }
}
