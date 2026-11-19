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
  With the PvP toggle (1.3.3, README "PvP toggle") partymates whose switches differ ignore each other
  (no damage, heals or buffs), and players outside any team fight only when both have PvP on where they are.
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
  synced, so client-side targeting agrees. (Since 1.3.3 a suspended pair counts as "no team": they fight
  when both have PvP on there — or, for Duels, when Eden sees them in the same running match, whatever
  their switches. A PvP mode of another mod needs both players' PvP on, or a dimension whose mode is `on`.)
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

- `check(player, id, null)` asks about the activity itself (its first difficulty's level). Since 1.3.5 it
  also refuses with "Closed for maintenance" while staff have the activity closed (Moderation →
  Activities); staff with `eden_ld.admin.dungeons` pass, to test.
- `reportRun` keeps each player's clears and best time per difficulty (best wave for arenas), and
  a win counts for the `complete_dungeon` daily objective. Since 1.3.5 it also writes one entry to the
  server's run history (Moderation → Activities: runs, win rate, median and best per difficulty), so
  call it **once per run with every participant** — not once per player, which would count the run
  that many times.

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

## Player settings

`net.ledok.eden_ld.api.hub.EdenSettingsApi` — server thread; works for offline players. A mod's
per-player switches can live on the hub's Settings tab: Eden shows them, stores them with the
player's other hub settings, and the mod reads them here.

```java
boolean skipWarning = EdenSettingsApi.getBoolean(server, player.getUUID(),
        EdenSettingsApi.DUELS_SKIP_LOSS_WARNING, false);
boolean challengeable = EdenSettingsApi.getBoolean(server, target.getUUID(),
        EdenSettingsApi.DUELS_PERSONAL_CHALLENGES, true);
```

- `def` is returned while the player never changed the setting — pass the same default the
  Settings tab shows (above).
- Keys today (Settings → Privacy → **Duels**, shown when Duels_LD is installed on the client):
  `duels_ld:wager.skip_loss_warning` (default off — skip the "you can lose your stake" popup;
  Duels' ready check always stays) and `duels_ld:wager.personal_challenges` (default on — others
  may challenge the player with `/duel <name>`). A new key needs an Eden release: the Settings tab
  only sends keys Eden knows, and the server refuses others.
- Cheap (an NBT lookup); fine to call per check, no need to cache.
- `setBoolean(server, player, key, value)` (1.3.12+) changes one of these keys, as the Settings
  tab would — for a mod's own "Don't ask again" box — and refreshes an open Settings tab. False for
  any other key.

## Moderation reports

`net.ledok.eden_ld.api.hub.EdenModerationApi` — server thread. A player's complaint about something
your mod saw (a disputed wager, a griefing claim) goes to staff: it lands in the Moderation window's
**Reports** section, and online staff with `eden_ld.admin.reports` get a chat line with **[Open]**
at once (and on join while any report is open).

```java
// Duels_LD: the player disputes wager #1042 from its result screen.
long id = EdenModerationApi.report(server, player.getUUID(), "duels_ld:wager", "#1042",
        "He logged out right before losing and kept his stake.",
        List.of("12:03:11 Alex staked 50 LC, Bob staked 50 LC",
                "12:04:40 Bob disconnected (match running)",
                "12:04:40 result: draw, stakes returned"));
if (id < 0) {
    player.sendSystemMessage(Component.translatable("message.duels_ld.report.refused"));
}
```

- `category` names what it's about — your mod id and a kind (`duels_ld:wager`); `reference` is
  your own id of the thing (`#1042`, "" for none). Staff see "New report #12 (duels_ld:wager #1042)
  from Alex", and both in the list.
- Returns the new report's id, or **-1 when refused**: blank text, or the reporter already filed
  5 reports in the last 10 minutes (across every mod). Tell the player — Eden says nothing to them.
- Caps are **cut, not refused**: text 500 characters, the log's first 200 lines of up to 300
  characters each (trailing spaces trimmed), category and reference 64 each. The constants are on
  `EdenModerationApi`.
- The reporter may be offline (their name comes from the server's profile cache).
- Reports are kept in the world (`data/eden_ld/reports.dat`), at most the newest 2000 — past that,
  handled ones older than 90 days go first, then the oldest.
- Since 1.3.83 every report has a **status** (New, Under review, Accepted, Rejected) and an optional **staff reply**;
  the reporter sees both in their hub tab «Звернення» (Reports) and gets Eden mail when either changes. Name your
  category in the player's language with the lang keys `gui.eden_ld.hub.reports.category.<namespace>.<path>`
  (e.g. `...category.duels_ld.wager`); without one the tab shows the raw category.

### Suggestions (1.3.83+)

The same queue takes **suggestions**: a player proposes content your mod owns (a Codex entry, a recipe), staff
review it in your own screen, and you report back the verdict.

```java
// Codex_LD: register the review screen once (server start).
EdenModerationApi.registerSuggestionEditor("codex_ld:entry",
        (staff, id, author, authorName, reference, text, payload) ->
                CodexReview.open(staff, id, reference, payload));   // your own screen

// A player submits a proposal from your editor.
long id = EdenModerationApi.suggest(server, player.getUUID(), "codex_ld:entry", "codex_ld:nether",
        "Added the soul sand valley drops.", proposalJson, List.of());
if (id < 0) { /* refused: tell the player */ }

// Staff decided in your screen.
EdenModerationApi.resolve(server, id, true, "Thanks, added!", staff.getGameProfile().getName());
```

- `payload` is opaque to Eden: your proposed content, up to `EdenModerationApi.MAX_PAYLOAD` (30,000) characters
  (longer is cut). It's stored with the suggestion and handed back to your editor.
- `suggest` returns **-1** when refused: blank text, the rate limit, or this player already has an **open**
  suggestion of the same category and reference.
- In Moderation → Reports a suggestion of a category with an editor shows **[Open in editor]**, which calls your
  `SuggestionEditor` on the server thread for that staff member. Open your screen from there.
- `resolve` sets Accepted or Rejected, shows `reason` to the author in their tab and mails them. Staff can also set
  the status and reply by hand in the Reports section.

### Starting a suggestion from the «Звернення» form (client, 1.3.84+)

`net.ledok.eden_ld.api.hub.EdenReportsClientApi` — client thread. Plain JDK + Minecraft types, so a mod that doesn't
compile against Eden can call it by reflection.

```java
// DataRewriter's client init: a «Рецепт» choice in the form, opening its editor in suggest mode.
EdenReportsClientApi.registerSuggestionStarter("datarewriter:recipe", "gui.datarewriter.suggest.eden_kind",
        () -> DatarewriterClient.maySuggest(),
        hub -> RecipeEditor.openSuggest(hub));   // hub: the screen to return to on Close

// By reflection:
Class.forName("net.ledok.eden_ld.api.hub.EdenReportsClientApi")
        .getMethod("registerSuggestionStarter", String.class, String.class, BooleanSupplier.class, Consumer.class)
        .invoke(null, "datarewriter:recipe", "gui.datarewriter.suggest.eden_kind", available, opener);
```

- The form lists one extra choice per starter after Eden's four kinds, labelled with your `labelKey` (ship the key).
- `available` is checked each time the form builds; false (or an exception) hides the choice.
- Picking it shows a short hint and **Відкрити редактор**, which calls `open` with the hub screen. Your editor then
  files with `EdenModerationApi.suggest` on the server, and the suggestion appears in the player's list.
- Registering the same category again replaces the earlier starter.

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

## Rental builds

`net.ledok.eden_ld.api.rental.EdenRentalApi` (1.3.52+), server thread. Duels_LD (ranked) and Arenas_LD (runs) swap a
player onto a loadout and back (design: `Balance/RENTAL-BUILDS.md`).

```java
boolean begin(ServerPlayer player, String session, ResourceLocation kit)        // a Kits_LD 0.3.0+ (hidden) kit
boolean begin(ServerPlayer player, String session, Consumer<ServerPlayer> give) // any loadout; may throw to refuse
boolean end(ServerPlayer player)
boolean end(MinecraftServer server, UUID player)  // offline: done at their next login
boolean isRenting(Player player)
@Nullable String sessionOf(Player player)
boolean reequip(ServerPlayer player)              // after a death in a run: the items as they were at the death
boolean stashItem(ServerPlayer player, ItemStack stack) // a reward kept for the end, safe from the cleanup
boolean isRentalItem(ItemStack stack)             // custom_data {eden_rental: <session>}
```

- `begin` puts aside the inventory, armor, offhand, Trinkets, cursor and 2x2 crafting grid, then gives the loadout and
  marks every item it gave. It returns false, with nothing changed, if the player already rents, is dead, or the kit is
  missing.
- **Skill tree:** kept by default (1.3.97+, `rental.json` `keep_skill_tree: true`): the tree gives max health and class
  stats, and spending or resetting points works as usual while renting. With `keep_skill_tree: false`, `begin` also
  resets and locks every Pufferfish Skills tree (experience, level and points are untouched) and `end` unlocks it again.
- `end` removes every rental item (also inside shulker boxes and bundles) and gives their items back to the same slots.
  A tree that was put aside is given back even if the option changed meanwhile. Anything else they got meanwhile is kept
  (overflow is dropped at their feet).
- While renting:
  - Rental items can't be dropped (they go back to the hand) or put in any container slot: chests, ender chest,
    crafting, anvils, trades, shops, modded storage screens.
  - Rental items can't go into item frames, armor stands, allays, display blocks or bundles.
  - The player picks nothing up from the ground.
- **Death** drops nothing and makes no grave. Rental items are taken off the player (their slots are remembered); the
  player's other items are kept for `end`. The session continues: call `end`, or `reequip` after the respawn.
  `reequip` (1.3.121) gives back the exact rental items from the death: same stacks, same slots, used consumables stay
  used. Only without such a snapshot does it hand out a fresh kit, and (1.3.122) only when the player holds none of the
  session's items: an Arenas run's "downed" is no death (nothing is taken), so `reequip` then leaves the inventory
  exactly as it is.
- **Sessions are not ended on disconnect**, so a player who reconnects is still renting; the owner ends the session.
  After a restart or crash, the next login ends it, and the rental items as they were are remembered (1.3.121). A
  `begin` with the **same session id** then gives those back instead of a fresh kit; a `begin` for any other session
  forgets them.
- Once a second, rental items found anywhere outside their own session are removed. A dropped rental item is removed
  as soon as it spawns.
- Staff: `/eden rental status|end <player>`, `/eden rental begin <player> <kit>` (testing).

### The rental picker (1.3.120+)

One picker for every mod that lends rental builds (ranked Duels_LD, Arenas_LD runs). Presets are Kits_LD's hidden kits
`falleneden:rental/<level>/<preset>`; a preset's class is its name up to the first `_`.

```java
record Pick(String kitId, List<String> spells)   // spells for the kit's book, may be empty
record PickerRequest(int level, Component title, @Nullable Component subtitle, @Nullable Pick current,
                     long deadlineMs, Consumer<Pick> onPick, @Nullable Runnable onClose)

boolean openPicker(ServerPlayer player, PickerRequest request)
void closePicker(ServerPlayer player)
boolean hasPicker(ServerPlayer player)
List<String> presets(int level)                          // RentalPresets.forLevel
@Nullable Pick recommended(ServerPlayer player, int level)
@Nullable Pick validate(ServerPlayer player, int level, Pick pick)
boolean begin(ServerPlayer player, String session, Pick pick)
String classOf(String kitId)                             // "paladin" from ".../25/paladin_holy"
int kitLevel(String kitId)                               // 25 from ".../25/paladin_holy", -1 when none
```

- **What the player sees:** every preset of the level, the one for their skill-tree class first with a "Recommended"
  tag, and the `current` pick highlighted. Each preset shows its items (vanilla tooltips) and its spell book's choice:
  one spell per tier as radio rows (`spell_tiers`), or a free choice with a counter. With a deadline, a bar and
  "Closes in N s" drain on the client. At 0 it stays at 0: closing is the caller's job.
- **Where it opens:** as its own screen, or as a modal on a host's canvas when a client host is registered
  (`client.rental.RentalPickerClient.registerHost(Supplier<@Nullable VectorCanvas>)`, so a menu stays open).
- **`openPicker`** replaces an open picker without calling its `onClose`. It returns false, with nothing sent, when the
  level has no preset or the client lacks Eden's payload.
- **One request per player.** The client's answers carry the request id; answers for an older request are ignored. A
  pick must be a kit that picker showed, and its spells are validated, before `onPick` runs (server thread).
- **A pick keeps the request.** The client closes its picker after a pick; call `openPicker` again (with the pick as
  `current`) to let the player change it. `onClose` runs only when the player closes it without picking (Esc, Close).
  `closePicker`, a disconnect (no callback) and the server stop drop the request.
- **`recommended`:** the first preset whose class is the player's class, else the first preset. The class is the
  branch with the most unlocked nodes in the `class_tree` (Pufferfish Skills), mapped through `class_presets`. Spells
  are prefilled: the player's saved pick for that preset, else their own book's spells when it has the same pool, else
  the kit's own.
- **`validate`:** null when the level doesn't offer the kit. Spells are cut to what the book allows, and per-tier rows
  are filled. With no spells picked, the prefill.
- **`begin(…, Pick)`:** `begin(player, session, kit)`, then the spells go into the lent book a second later, retried
  once a second until Spell Engine reads them back. After 10 tries, or once the rental ended, it gives up (with a WARN).
  `reequip` puts them in again. It doesn't check the level: `validate` first.
- **Spell picks are saved** per player and preset (SavedData `eden_ld_rental_spells`) on each pick and on `begin`.
- **Config (`rental.json`):**
  - `class_tree`: default `"falleneden:classes"`; empty means nothing is recommended.
  - `class_presets`: skill-tree class → preset class, where the names differ (`"earth": "terra"`).
  - `spell_max_tier`: kit level folder → highest spell tier; empty means the whole pool.
  - `spell_tiers`: default `[2, 3, 4]`; empty means a free choice up to the book's size.
  - These replace Arenas_LD's `rental_class_presets`, `rental_spell_max_tier` and `rental_spell_tiers`.
- **Payloads:** S2C `eden_ld:rental_picker_open` and `eden_ld:rental_picker_close`; C2S `eden_ld:rental_picker_choice`
  and `eden_ld:rental_picker_closed`.

## Stability

The `api.flight` package and `MinestarHook`/`ProfileCache` are kept source-stable; internals
(`flight`, `legacy`, `minestar` implementation classes) may change without notice. Legacy
`yggdrasil_ld:` registry ids are permanent.

## Hub theme (client)

`net.ledok.eden_ld.api.client.EdenHubThemeApi` (1.3.13+) — client only, render thread. Another mod's screen can
look like the hub in whatever theme the player picked:

```java
Theme theme = EdenHubThemeApi.theme();                    // VectorLib palette tokens + widget skins
Function<Rect, CanvasNode> shell = EdenHubThemeApi.panel(); // the themed panel background (~8 px border art)
ResourceLocation id = EdenHubThemeApi.id();                // rebuild your screen when this changes
int hairlineHi = EdenHubThemeApi.hairlineHi();             // 1.3.101+: the stronger hairline
int track = EdenHubThemeApi.barTrack();                    // 1.3.101+: progress/HP bar track
int fill = EdenHubThemeApi.barFill();                      // 1.3.101+: progress/HP bar fill
```

Duels_LD's stake screens use it in their "Hub theme" mode (looked up by reflection, so Duels runs without it).
