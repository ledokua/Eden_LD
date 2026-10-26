/**
 * Public hub API for other LeDok mods — kept source-stable like {@code api.flight}.
 *
 * <p>Surface (see {@code docs/PLAN-hub.md}):
 * <ul>
 *   <li>{@code EdenPartyApi} — party lookup, members and ranks, membership callbacks,
 *       friendly-fire suspension (used by Duels_LD).</li>
 *   <li>{@code EdenActivityApi} — the activity level, entry checks, and finished runs for
 *       activities that don't come from Arenas_LD (Eden reads Arenas_LD's own API).</li>
 *   <li>{@code EdenDailyApi} — {@code reportProgress(player, type, amount)} so other
 *       mods feed daily quest counters without depending on their sources (phase 6).</li>
 *   <li>{@code EdenGuildApi} — guild lookup, ranks, chat prefixes (phase 7).</li>
 *   <li>{@code EdenSettingsApi} — read a player's hub settings, e.g. Duels_LD's wager switches (1.3.11).</li>
 *   <li>{@code EdenModerationApi} — file a player's report for staff (Moderation → Reports, 1.3.11).</li>
 * </ul>
 */
package net.ledok.eden_ld.api.hub;
