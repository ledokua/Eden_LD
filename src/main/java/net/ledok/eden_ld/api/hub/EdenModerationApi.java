package net.ledok.eden_ld.api.hub;

import net.ledok.eden_ld.hub.moderation.ReportDesk;
import net.ledok.eden_ld.hub.moderation.ReportRules;
import net.minecraft.server.MinecraftServer;

import java.util.List;
import java.util.UUID;

/**
 * File player reports for staff (source-stable). Server thread.
 *
 * <p>A report lands in the Moderation window's <b>Reports</b> section; online staff with
 * {@code eden_ld.admin.reports} get a chat line with [Open] at once, and on join while any are open.
 */
public final class EdenModerationApi {

    /** Reports one player may file within ten minutes (across every mod). */
    public static final int RATE_LIMIT = ReportRules.RATE_LIMIT;
    /** Characters of text kept (longer text is cut, not refused). */
    public static final int MAX_TEXT = ReportRules.MAX_TEXT;
    /** Log lines kept (the first ones), and characters per line. */
    public static final int MAX_LOG_LINES = ReportRules.MAX_LOG_LINES;
    public static final int MAX_LOG_LINE = ReportRules.MAX_LOG_LINE;

    private EdenModerationApi() {
    }

    /**
     * Files a report from {@code reporter} (online or not). {@code category} says what it's about — your mod's id and
     * a kind, e.g. {@code "duels_ld:wager"}; {@code reference} your own id of the thing, e.g. {@code "#1042"} ("" for
     * none); {@code text} the player's words; {@code log} what your mod knows about it, one line each (may be empty).
     *
     * @return the new report's id, or -1 when refused: blank text, or the reporter filed {@link #RATE_LIMIT} reports
     * in the last ten minutes. Text and log past their caps are cut, not refused.
     */
    public static long report(MinecraftServer server, UUID reporter, String category, String reference, String text,
                              List<String> log) {
        return ReportDesk.file(server, reporter, category, reference, text, log);
    }
}
