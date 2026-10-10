# Eden_LD addon API

How another mod plugs into Eden_LD: flight rules and Minestar profile data.
Everything public lives under `net.ledok.eden_ld.api` (plus the `minestar` package for profiles).

## Setup

Put the Eden_LD jar on your compile classpath; at runtime the mod is present on the server anyway.

```gradle
repositories {
    flatDir { dirs("libs") }
}
dependencies {
    modCompileOnly "net.ledok:Eden_LD:1.1.0"
}
```

Register rules during your `ModInitializer.onInitialize()` — Eden_LD registers its own config rule
at that time too, and order between mods does not matter (priority decides, not registration order).

## Flight rules

`EdenFlightApi` gates vanilla elytra and every wing that flies through Fabric's elytra events
(Icarus included), on takeoff and on every flight tick, plus firework boosting. A rule returns one
of three verdicts:

- `DENY` — block flight/boosting.
- `ALLOW` — force-allow, overriding every lower-priority rule.
- `PASS` — no opinion; the next rule decides.

Rules run in ascending priority order and the **first non-PASS verdict wins**. Eden_LD's own
config-driven rule (dimension blacklists, armor threshold) runs at priority **1000** — register
below that to override it, above it to only act where the config allows flight.

A complete rule: no flight during an arena run, but force-allowed in the lobby even if the lobby's
dimension is blacklisted in the Eden_LD config:

```java
EdenFlightApi.register(ResourceLocation.fromNamespaceAndPath("arenas_ld", "arena"), 100, new FlightRule() {
    @Override
    public FlightVerdict testFlight(LivingEntity entity) {
        if (!(entity instanceof ServerPlayer player)) return FlightVerdict.PASS;
        if (ArenaManager.isInRun(player)) return FlightVerdict.DENY;
        if (ArenaManager.isInLobby(player)) return FlightVerdict.ALLOW;
        return FlightVerdict.PASS;
    }

    @Override
    public Component flightDenyMessage() {
        return Component.translatable("message.arenas_ld.no_flight_in_run");
    }
});
```

Notes:

- `testFlight` runs every flight tick on both sides — keep it cheap, no allocation-heavy work.
- Rules must be **stateless** (one instance serves client and server threads in singleplayer).
- `testBoost(Player)` is a separate default method for firework boosting; `boostDenyMessage()`
  supplies its message. A rule that only cares about boosting returns `PASS` from `testFlight`.
- Deny messages are shown on the action bar, throttled by Eden_LD (no per-tick spam).
- When flight is denied mid-air, Eden_LD applies Slow Falling (config `safe_landing_on_deny`) —
  your rule does not need to handle the fall.
- `EdenFlightApi.isFlightAllowed(entity)` / `isBoostAllowed(player)` evaluate the combined rules,
  for UI or command checks.
- `unregister(id)` removes a rule; re-registering the same id replaces it.

## Parties

`net.ledok.eden_ld.api.hub.EdenPartyApi` — server side, server thread. Read access plus
membership callbacks; mutations stay inside Eden_LD (players manage parties through the hub).

```java
Optional<EdenPartyApi.PartyInfo> party = EdenPartyApi.getParty(server, player.getUUID());
boolean sameParty = EdenPartyApi.sameParty(server, a, b);
boolean leader = EdenPartyApi.isLeader(server, player.getUUID());

EdenPartyApi.addMembershipListener(new EdenPartyApi.MembershipListener() {
    @Override public void onJoin(UUID partyId, UUID player) { /* server thread */ }
    @Override public void onLeave(UUID partyId, UUID player) { /* also kick/disband/succession */ }
});
```

Notes:

- `PartyInfo` is an immutable snapshot (id, name, leader, members) — re-read it rather than
  caching across ticks.
- Parties are **temporary**: they exist only while a member is online, disband below 2 members,
  and are never persisted. Membership also changes without player action — disconnected members
  are auto-kicked after a grace period and a disconnecting leader is succeeded immediately
  (both fire the membership listeners).
- Display names are **not unique**; identify a party by its `id`. (Players address parties by
  an auto-generated tag, but that is a hub-UI/admin-command concern, not part of this API.)
- Friendly fire between partymates is **off** (damage, projectiles, pets, Spell Engine and
  Better Combat targeting), enforced by Eden_LD — unless suspended for a PvP mode, below.
  Owned entities (tamed pets, horses) count as their owner, so partymates' pets are
  protected too.
- PvP damage that lands combat-tags both players (`combat.pvp_tag_seconds`): no hub
  teleports, and no flight via an Eden flight rule at priority 50 — register a flight rule
  below 50 if an arena must force-allow flight during combat.
- Busy players (BusyLib) cannot invite, accept, or join; leaving is never blocked — an arena
  mod must handle a member leaving mid-run itself.

### Suspending party protection (duels, PvP arenas)

A PvP mode where partymates may face each other switches Eden's protection off for its
players for the duration of the match, then decides who may hit whom itself:

```java
ResourceLocation DUEL = ResourceLocation.fromNamespaceAndPath("duels_ld", "match");

// match start — every participant
EdenPartyApi.suspendFriendlyFireProtection(player, DUEL);
// match end, forfeit, elimination — every participant
EdenPartyApi.resumeFriendlyFireProtection(player, DUEL);

boolean suspended = EdenPartyApi.isFriendlyFireProtectionSuspended(server, player.getUUID());
boolean protectedPair = EdenPartyApi.isProtected(server, a, b); // partymates, neither suspended
```

- A pair is protected only while **neither** player is suspended, so suspending both sides
  of a duel is enough; spectators and other partymates stay protected from each other.
- While suspended, Eden stops cancelling damage, lets projectiles hit, lets pets target the
  player, and stops reporting them as teammates to Spell Engine / Better Combat. The flag is
  synced, so client-side targeting agrees.
- Reasons stack (BusyLib-style): protection returns once every reason is resumed. Always
  resume with the reason you suspended with.
- Suspensions are cleared on disconnect, so a crashed match can't leave a player
  unprotected — re-suspend if your mode lets players rejoin mid-match.
- Server thread only.

## Activities

`net.ledok.eden_ld.api.hub.EdenActivityApi` — server thread. The Activities tab reads Arenas_LD
through Arenas' own API; this is for activity-like content from anywhere else (activities defined
as datapack files, `data/<ns>/hub_activities/<path>.json`, see the README).

```java
int level = EdenActivityApi.level(player);   // the configured Pufferfish Skills tree level
Component refused = EdenActivityApi.check(player, activityId, "hard");   // null = may enter
// When a run ends (wave = how far an arena run got, 0 otherwise):
EdenActivityApi.reportRun(server, activityId, "hard", won, runMillis, 0, participantUuids);
```

- `check(player, id, null)` asks about the activity itself (its first difficulty's level).
- `reportRun` keeps each player's clears and best time per difficulty (best wave for arenas), and
  a win counts for the `complete_dungeon` daily objective.

## Guilds

`net.ledok.eden_ld.api.hub.EdenGuildApi` — server side, server thread. Guilds are persistent;
members may be offline.

```java
Optional<EdenGuildApi.GuildInfo> guild = EdenGuildApi.getGuild(server, player.getUUID());
// GuildInfo: id, name, tag (≤ 5 symbols), color (RGB), leader, members (uuid → Rank, join order)
boolean together = EdenGuildApi.sameGuild(server, a, b);

EdenGuildApi.addMembershipListener(new EdenGuildApi.MembershipListener() {
    @Override public void onJoin(UUID guildId, UUID player) { }
    @Override public void onLeave(UUID guildId, UUID player) { /* also kicks and disbanding */ }
});
```

Guildmates get the same friendly-fire protection as partymates: `EdenPartyApi.isProtected`
covers both, and `suspendFriendlyFireProtection` switches it off for either.

## Daily quests

`net.ledok.eden_ld.api.hub.EdenDailyApi` — server thread. A daily quest's `objective` is a
progress type; Eden counts kills, crafting, and Spell Engine casts itself. Report everything
else:

```java
// Your own objective type — quests use "objective": "arenas_ld:win_run":
EdenDailyApi.reportProgress(player, ResourceLocation.parse("arenas_ld:win_run"), null, 1);

// Extra free rerolls for today (quest rewards, shop items); works for offline players:
EdenDailyApi.grantExtraRerolls(server, player.getUUID(), 2);
```

- `subject` is matched against a quest's `target` by exact id; quests without a target
  accept any subject, including null. (`#tag` targets only work for Eden's own kill/craft.)
- Won activity runs (Arenas_LD, `EdenActivityApi.reportRun`) already report `COMPLETE_DUNGEON` with the activity id.
- The same report also feeds the player's **guild quests** (same objectives and targets), so a
  custom objective works for both boards with one call.
- Progress only counts toward today's unclaimed quests of that type; completions notify the
  player and refresh their hub.

## Minestar profiles

Server side, and only meaningful when the Minestar platform mod is installed. Gate every access
on `MinestarHook.LOADED` **before** touching `ProfileCache` — the cache exposes Minestar's own
types (`UserProfile`, `UserFriend`, `UserGroup`), so you also need the minestar jar on your
compile classpath to use it.

```java
if (MinestarHook.LOADED) {
    ProfileCache.get(player.getUUID()).ifPresent(profile -> {
        Optional<UserGroup> group = profile.group();   // name, weight, color
        boolean prime = profile.primeStatus()
                .map(status -> status.expiresAt().isAfter(Instant.now()))
                .orElse(false);

        for (UserFriend friend : profile.friends()) {  // accepted friends only
            boolean online = friend.online();          // launcher-reported, ~90s window
            Optional<Instant> lastSeen = friend.lastSeenAt();
            List<PackSummary> playing = friend.playing(); // modpacks, empty when offline
        }
    });
}
```

Profiles are fetched once per join and evicted on disconnect. `ProfileCache.get` never blocks —
an empty Optional means the fetch has not landed (or failed); do not treat it as "player has no
profile" during the first seconds after join. Friend `online()`/`playing()` values are a snapshot
from that join-time fetch — stale within minutes; features that show live status must re-fetch
the profile instead of trusting the cache.

Friend data without the Minestar types (safe to call without the jar on your classpath —
`MinestarHook` has no Minestar imports):

```java
FriendSnapshot cached = MinestarHook.cachedFriends(player.getUUID()); // null without Minestar
MinestarHook.fetchFriends(player, false, snapshot -> {                // server thread; null if unreachable
    for (FriendSnapshot.Friend friend : snapshot.friends()) { /* uuid, nickname, online, lastSeenMillis, playing */ }
});
```

`fetchFriends` re-fetches from the platform (throttled to one fetch per player per 15 s unless
`force` is true) and refreshes the cache for everyone reading it.

## Stability

The `api.flight` package and `MinestarHook`/`ProfileCache` are kept source-stable; internals
(`flight`, `legacy`, `minestar` implementation classes) may change without notice. Legacy
`yggdrasil_ld:` registry ids are permanent.
