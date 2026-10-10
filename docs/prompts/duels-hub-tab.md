# Task for the Duels_LD agent: an API for Eden's Duels tab

Duels_LD (`/home/ledok/Projects/Mods/LeDok/Fabric/Duels_LD`, Fabric 1.21.1, Mojang mappings, 1.3.0)
now uses Eden_LD's parties. Next, Eden's hub (`../Eden_LD`, opened with F4) gets a **Duels tab**:
your ratings and leaderboard positions, wins/losses per mode, today's rewards, your recent matches,
the top players, and queue buttons with live queue state. Duels' own matchmaking screen (B) stays.

**Direction of the dependency**: Duels_LD gets a small **API of its own**; Eden reads it
(compile-only against the Duels jar, as it does with Waystones). The API must not reference Eden —
Duels works the same without it (your existing optional `EdenBridge` is unrelated and stays).
American English in code, UI and docs; keep en_us and uk_ua in step. Don't commit unless the owner
asks.

## Today (from a code scan — verify)

- Elo per mode in `MMRManager` (`mmr1v1`/`mmr2v2`, SavedData `duels_ld/mmr`); the getters
  `computeIfAbsent`, so merely reading a rating stores the player at 1000 (and puts them on
  leaderboards).
- Wins/losses/draws are one total in `PlayerStats`/`StatsManager` (`duels_ld/stats`), not split by
  mode, also creating records on read.
- Leaderboards are computed on demand (top 10) in `DuelCommand.leaderboard` and the private
  `LeaderboardPlaceholders`; no rank lookup.
- Queue: `MatchmakingManager.joinQueue(ServerPlayer, int mode)` / `leaveQueue` report only by chat;
  queue sizes exist; join time and search range are private; pending matches are private.
- Match state: `DuelManager.isInDuel` / `getDuel` (1v1); `getTeamDuel` is private.
- Rewards: `BattleRewardStore.totalEarned(uuid, today)` and the cap in `MatchmakingConfigManager`.
- No match history, no events.

## What to build — `net.ledok.duels_ld.api.DuelsApi` (static, server thread)

Suggested shape; keep it stable once shipped and document it in API.md:

```java
enum Mode { ONE_V_ONE, TWO_V_TWO }

record ModeStats(int rating, int wins, int losses, int draws) { int played(); }
record Stats(ModeStats oneVsOne, ModeStats twoVsTwo, long earnedToday, long dailyCap, boolean rewardsEnabled) {}
/** Empty when the player never played — must NOT create a record (fix the computeIfAbsent reads). */
static Optional<Stats> stats(MinecraftServer server, UUID player);

record LeaderboardEntry(UUID player, String name, int rating, int played) {}
/** Players with at least one match in that mode, best first. */
static List<LeaderboardEntry> leaderboard(MinecraftServer server, Mode mode, int limit);
/** 1-based position, or -1 when unranked (no matches in that mode). */
static int rank(MinecraftServer server, Mode mode, UUID player);

enum QueueResult { QUEUED, ALREADY_QUEUED, BUSY, IN_MATCH, PENDING_MATCH, NOT_PARTY_LEADER,
                   PARTY_WRONG_SIZE, PARTY_MEMBER_UNAVAILABLE, DISABLED }
/** Same rules as today, but tells the caller why (no chat spam when called from the API). */
static QueueResult joinQueue(ServerPlayer player, Mode mode);
static void leaveQueue(ServerPlayer player, @Nullable Mode mode);   // null = every queue

record QueueState(boolean oneVsOne, boolean twoVsTwo, boolean pending, long joinedAtMillis,
                  boolean viaParty, int searchRange) {}
static QueueState queueState(UUID player);
static int queued(Mode mode);

record MatchRecord(long endedAtMillis, Mode mode, Result result, int ratingBefore, int ratingAfter,
                   List<String> teammates, List<String> opponents, long durationMillis) {}
enum Result { WIN, LOSS, DRAW }
/** Newest first; keep the last 20 per player. */
static List<MatchRecord> history(MinecraftServer server, UUID player, int limit);

interface Listener {
    default void onQueueChanged(UUID player) {}
    default void onMatchStart(Mode mode, List<UUID> players) {}
    default void onMatchEnd(Mode mode, List<UUID> winners, List<UUID> losers, boolean draw) {}
}
static void addListener(Listener listener);
```

Data changes this needs (Duels' own data, your call how — no need to keep perfect history):
- **Wins/losses/draws per mode** from now on. Existing totals can stay as legacy totals; new per-mode
  counters start at 0.
- **Match history**: the last 20 matches per player, saved (SavedData like the others).
- Reading stats or ratings never creates records; players without matches don't appear on
  leaderboards.

Fire the listeners from the existing paths: queue join/leave/cleanup (including party-change drops
and disconnects), match start (1v1 and 2v2), match end (win, loss, draw, forfeit).

Also update `/duel stats` and the matchmaking screen to show per-mode numbers if it's easy; docs
(README, API.md, CHANGELOG), version bump (1.4.0).

## Check

`./gradlew build`. In a dev run: `stats` is empty for a fresh player and doesn't add them to the
leaderboard; after a match the per-mode counters, rating, rank and history update and the listeners
fire; `joinQueue` returns the right result for busy / not leader / wrong party size.
