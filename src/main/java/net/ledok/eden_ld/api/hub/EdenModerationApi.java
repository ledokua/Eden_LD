package net.ledok.eden_ld.api.hub;

import net.ledok.eden_ld.hub.moderation.ReportDesk;
import net.ledok.eden_ld.hub.moderation.ReportRules;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

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

    // ---------------------------------------------------------------- suggestions (1.3.83+)

    /** Characters of a suggestion's payload kept (longer is cut): NBT strings hold 65,535 bytes of UTF-8. */
    public static final int MAX_PAYLOAD = ReportRules.MAX_PAYLOAD;

    /**
     * A staff member's review screen for your suggestions: called on the server thread when they press
     * [Open in editor] on one in the Moderation window. Open your own screen for {@code staff} with what the author
     * proposed, and when they decide, call {@link #resolve}.
     */
    @FunctionalInterface
    public interface SuggestionEditor {
        void open(ServerPlayer staff, long id, UUID author, String authorName, String reference, String text,
                  String payload);
    }

    /**
     * Files a suggestion: the same queue as reports (staff see it in Moderation → Reports, the author in their hub tab
     * «Звернення» with its status). {@code category} your mod's id and a kind, e.g. {@code "codex_ld:entry"};
     * {@code reference} what it changes (an entry id, a recipe id; "" for something new); {@code text} the author's
     * words; {@code payload} the proposed content, opaque to Eden (your editor reads it back); {@code log} any details
     * for staff.
     *
     * @return the new id, or -1 when refused: blank text, the author's rate limit ({@link #RATE_LIMIT} per ten
     * minutes), or the author already has an open suggestion of this category and reference.
     */
    public static long suggest(MinecraftServer server, UUID author, String category, String reference, String text,
                               String payload, List<String> log) {
        return ReportDesk.suggest(server, author, category, reference, text, payload, log);
    }

    /** Your review screen for {@code category} (one per category; the last one wins). */
    public static void registerSuggestionEditor(String category, SuggestionEditor editor) {
        ReportDesk.registerEditor(category, editor::open);
    }

    /**
     * Your verdict on suggestion {@code id}: accepted or rejected, with {@code reason} for the author (may be empty).
     * Eden sets the status, shows the reason in the author's tab and mails them. {@code by} is who decided (a staff
     * name). False when there's no such id.
     */
    public static boolean resolve(MinecraftServer server, long id, boolean accepted, String reason, String by) {
        return ReportDesk.resolve(server, id, accepted, reason, by);
    }
}
