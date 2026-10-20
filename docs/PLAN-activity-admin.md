# Activity admin (Eden 1.3.5)

Owner's decisions, 2026-10-19. The Moderation window's Activities section becomes a control panel for every
activity: controller actions through the activity's source, live status, a maintenance lock and a server-wide run
history. Everything Eden-side is built and, since 1.3.7, wired to ArenasApi 4.9.0. Eden
is compiled against `libs/arenas_ld-fabric-4.6.0.jar`, so `ArenasIntegration` doesn't override any admin call yet and
Arenas activities show only what Eden knows by itself (closed, history).

## Built

- **Source-agnostic admin contract** (`hub/activity/ActivitySource`, `ActivityLiveStatus`): default methods, all
  unsupported until a source overrides them; `Activities.sourceFor / adminActions / supports / status / closed`
  route by id (the first source reporting it wins, as before); `Activities.sources(server)` maps every id to its
  source in one pass for the list and the poll.
- **Server actions** (`hub/moderation/AdminTools`, area `activities`, all under `eden_ld.admin.dungeons`, each
  logged as "<admin> <did X> <activity>"):
  - `teleport {id}` — a refusal also goes to chat (the window closes on Teleport).
  - `rename {id, name}` (1–48 chars) and `admin {id}` — also need op (`hasPermissions(2)`): Arenas accepts op edits
    only. Rename pushes every online player's Activities data.
  - `force_end {id, instance?: {dim, x, y, z}}` — no instance = every run, an unreadable one is refused; answered
    with fresh live data.
  - `forget {id}` — for a lost controller; Eden keeps its own settings and history for the id.
  - `save` gains `closed`.
  - The full `activities` data gains per entry `actions` (`AdminAction` bitmask) and `closed`, and top-level `op`.
- **Maintenance lock** (`ActivitySettings.Setting.closed`, NBT `closed`): `ActivityGate.check` refuses everyone
  first with `message.eden_ld.activities.closed`, except staff with `eden_ld.admin.dungeons` (to test). Covers
  Arenas' entry gate and the hub/datapack open path; running runs aren't touched. The hub card is dimmed with
  "Closed" and not clickable (staff: still clickable, tooltip says so).
- **Run history** (`ActivityHistory`, `data/eden_ld/activity_history.dat`): per activity a ring of the last 50 runs
  (epoch seconds, difficulty, won, forced, duration, wave, players) and per difficulty `RunStats` (runs, wins, best
  clear, best wave, the last 200 clear times for the median). Recorded once per run from
  `ActivityServer.recordRun(..., forced)`. A forced run is listed (as "Ended by staff", counted as not won) but kept
  out of the stats, player progress and dailies. Staff-recorded clears (Moderation's Complete,
  `/activities complete`) go through `ActivityServer.credit` — player progress only, no history entry.
- **UI** (`client/hub/ActivitiesSection`): actions row (Teleport, Admin screen, ✎), Closed toggle, Live block
  (instances with a state chip and cooldown left, runs with End run, End all runs, lobbies/queue), Warnings (with
  Forget on `CONTROLLER_MISSING`), Run history; list rows gain ▶ running, ⌛ cooldown, ⚠ and Closed markers.
  Admin screen → Esc reopens Moderation on Activities with the same pick (`ModerationReturn`, modeled on
  `ActivityReturn`). Every action flushes pending edits first.
- **Tests**: `RunStatsTest` (median, ring trimming, win rate, restore clamping), `ActivityHistoryTest` (forced runs
  listed but out of the stats, newest-first 50-run ring, no-difficulty key, save/load round trip).

## ActivitySource admin contract

```java
enum AdminAction { TELEPORT, RENAME, OPEN_ADMIN, STATUS, FORCE_END, FORGET }   // bit() = 1 << ordinal
record InstanceRef(ResourceLocation dimension, BlockPos pos)

Set<AdminAction> adminActions()                                        // default: none
@Nullable Component teleport(ServerPlayer admin, ResourceLocation id)  // null = done, else the refusal
@Nullable Component rename(ServerPlayer admin, ResourceLocation id, String name)
@Nullable Component openAdmin(ServerPlayer admin, ResourceLocation id)
@Nullable Component forceEnd(MinecraftServer server, ResourceLocation id, @Nullable InstanceRef instance)  // null = all
@Nullable Component forget(MinecraftServer server, ResourceLocation id)
Optional<ActivityLiveStatus> status(MinecraftServer server, ResourceLocation id)
```

`ActivityLiveStatus(instances, runs, openLobbies, queued, warnings)`:
`Instance(dimension, pos, State FREE|RUNNING|COOLDOWN, cooldownTicksLeft)`,
`Run(dimension, instance, difficulty?, players, Component phase, ticksElapsed, ticksLeft or -1)`,
`Warning { GENERATED_ID, UNNAMED, NO_DIFFICULTIES, NO_INSTANCES, PENDING_REMOVALS, CONTROLLER_MISSING }`.
`Run` carries the dimension too (not only the instance's BlockPos) so End run can name the instance exactly.

A source must only list an action in `adminActions()` when it implements it; the window shows buttons by that set
(and `STATUS` decides whether the Live and Warnings blocks exist).

`status()` is polled for **every** activity of the source about every 2 s per admin with the section open (the list
markers), so it must answer from state the source already holds in memory: never load chunks or read the block
entity of an unloaded controller — leave such an instance out or report it as unknown instead. A run's `phase` may be
null (read as none).

`forceEnd` with a null instance means every run. The server only passes null when the request has no `instance` at
all; an `instance` that doesn't parse (`dim`, `x`, `y`, `z`) is refused, never widened to every run.

## Payloads (1.3.5)

| Payload | Direction | Fields | Guard |
|---|---|---|---|
| `ActivityLiveRequest` (`eden_ld:activity_live_request`) | C2S | `selected` (activity id, "" = none) | client sends only if `ClientPlayNetworking.canSend`; polled ~2 s while the section is open and the window is up |
| `ActivityLive` (`eden_ld:activity_live`) | S2C | NBT: `rows` [{id, running, cooldown, lobbies, queued, warnings, closed}], `detail` {id, status?, history} for `selected` only, names resolved | `ServerPlayNetworking.canSend` + `eden_ld.admin.dungeons` |
| `ActivitiesClosed` (`eden_ld:activities_closed`) | S2C | `ids` (closed, shown activities), `bypass` (staff) | `ServerPlayNetworking.canSend`; sent right before every `ActivitiesData` (whose codec is unchanged) |

The full `AdminData` (`activities`) stays the "on open and after edits" path; the poll never resends it.
`force_end` answers with `ActivityLive`; `teleport` and `admin` answer with nothing but a notice.

## Wired: ArenasApi 4.9.0 (Eden 1.3.7, 2026-10-20)

(The number moved twice: Arenas 4.7.0 went to load fixes and `/arenasld instance add|check`, 4.8.0 to boss minions;
the admin API is 4.9.0, built by the Arenas_LD session, not committed or deployed when Eden 1.3.7 was built.)

`compat/arenas/ArenasIntegration` looks up once, by reflection, whether the running Arenas_LD has the admin calls
(`ADMIN`); when it does, `adminActions()` is all six and every call goes to `compat/arenas/ArenasAdmin`, the only class
using the 4.9.0 types, so an older Arenas_LD (live ran 4.8.0) just keeps the buttons hidden and logs one line.

| Eden | ArenasApi 4.9.0 |
|---|---|
| `teleport` | `teleportToController(player, id)` (free 2-high spot within 3 blocks; refused in a run or busy) |
| `rename` | `rename(server, id, name)` (Arenas doesn't check op: `AdminTools` does) |
| `openAdmin` | `openAdmin(player, id)` (Arenas checks op; bound to the controller's dimension, any distance) |
| `status` | `status(server, id)` → `ActivityLiveStatus`: instances (+ `pendingRemoval`), runs (+ `closing`), `openLobbies`, `queued` = `waitingPlayers`, warnings by name (+ `CONTROLLER_NOT_LOADED`: the live block says "unknown" instead of "none") |
| `forceEnd` | `forceEnd(server, id, InstanceRef?)` (Eden's `InstanceRef` → `ResourceKey<Level>` + pos) |
| `forget` | `forget(server, id)` (refused while the controller exists) |

Runs: `Outcome.FORCED` (a staff end) is matched by name and recorded with `forced = true` (history only, no stats,
progress or dailies). The run phase is a translatable Component (`status.arenas_ld.*`), sent as JSON and resolved by
the client.

## Not done / notes

- No in-game test yet (dev client not started for this work).
- Datapack activities support no admin actions (nothing to teleport to or end); they get the lock and history.
- Forget leaves Eden's settings and history for the id (they return if the activity does).

**Deployed (2026-10-19, 1.3.5):** Prism Client/Server (1.3.4 to removed-mods-2026-10-19), pack 0352999 (pushed),
live (1.3.4 to backup/removed-mods-2026-10-19; cmp + unzip -t). Not run in game before the deploy (no RAM for a dev
server); live from the owner's next restart. Only the Closed lock and run history work until Arenas 4.9.0 runs on the server (Eden 1.3.7+).

**Deployed (2026-10-20, 1.3.7):** Prism Client/Server (1.3.6 to removed-mods-2026-10-20), pack a2d4652 (pushed),
live (1.3.6 to backup/removed-mods-2026-10-20; cmp + unzip -t), next to Arenas_LD 4.9.0 (released c9db237, pack
53aef85). Not run in game; both go live at the owner's next restart.
