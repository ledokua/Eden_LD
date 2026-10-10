# Task for the Arenas_LD agent: stable ids and an API for Eden's Dungeons tab

Arenas_LD (`/home/ledok/Projects/Mods/LeDok/Fabric/Arenas_LD`, MultiLoader common/fabric/neoforge,
Minecraft 1.21.1, Mojang mappings, version 4.4.1) runs dungeons, raids and mob arenas. The server's
hub mod **Eden_LD** (`../Eden_LD`, Fabric-only) is getting a Dungeons tab that lists them as cards,
locks them and their difficulties behind a player level, and opens their lobby screen from the hub.

**Direction of the dependency**: Arenas_LD gets a small **API of its own** in the common module.
Eden reads it (compile-only against the Arenas Fabric jar, like it does with Waystones and Open
Parties and Claims). Arenas_LD must **not** depend on or reference Eden_LD — it works the same
without it. American English in code, UI and docs; keep en_us and uk_ua lang files in step. Don't
commit unless the owner asks.

## Today (from a code scan — verify)

- Three systems, each a controller block + block entity with linked spawner instances:
  `DungeonControllerBlockEntity` (+ `DungeonBossSpawnerBlockEntity` instances, rooms),
  `RaidControllerBlockEntity` (+ `RaidBossSpawnerBlockEntity`), `ArenaControllerBlockEntity`
  (+ `ArenaSpawnerBlockEntity`). All config lives in the controller's NBT, edited in the admin GUI.
- Nothing identifies an activity but the controller's dimension + position; names are free text,
  arenas have none. Registries: `ArenasLdMod.DUNGEON_MANAGER` and the static
  `RaidControllerBlockEntity.getControllers()` / `ArenaControllerBlockEntity.getControllers()`,
  filled as block entities load (controllers force-load their chunk).
- Entry: right-click → lobby screen (`DungeonControllerScreen` etc.); lobby actions
  `createLobby`, `invitePlayer`, `requestJoin`, `acceptInvite`/`joinLobby`, `toggleReady`,
  `setLobbyTier`, start via `StartRunPayload` → `SpawnerPacketHandlers.performStartRun` →
  `startRun` (raids: `RaidPacketHandlers.performStartRaid`; arenas: `ArenaControllerBlockEntity.startRun`).
  `/arenasld lobby open <dim> <pos>` already opens a lobby screen remotely.
- Difficulties: `DifficultyTier` EASY / NORMAL / HARD / NIGHTMARE with per-tier `TierConfig`
  (`enabled`, …); raids show LEGENDARY for the top tier but store `DifficultyTier`; arenas have no
  tiers (waves, "best wave").
- Run end: `DungeonRunLifecycle.handleWin` / `handleLoss` (and the Raid/Arena equivalents); no public
  event. No entry checks beyond lobby rules.

## What to build

1. **Stable ids.** Every dungeon, raid and arena gets a unique `ResourceLocation` id
   (e.g. `arenas_ld:crypt`), set in its admin GUI, saved with it, validated unique per server.
   Existing controllers without one get a generated id once (and a warning) so nothing breaks.
1b. **Hub fields in the admin GUI** (the owner's choice — admins set everything in one place):
   - **Required level** per difficulty, on each difficulty's existing settings tab (0 = no
     requirement); arenas have no tiers, so one required level for the arena.
   - **Card art**: a `ResourceLocation` text field (an Eden profile-background id, e.g.
     `eden_ld:dungeon_crypt`; empty = default art). Arenas only stores and reports it.
   Arenas_LD does **not** check levels itself: it doesn't know what "the player's level" is (Eden
   decides that from its own config). It only stores these numbers and reports them in the API;
   the entry gate below enforces them.
2. **API** — `net.ledok.arenas_ld.api.ArenasApi` (common, static, server thread). Suggested shape;
   keep it stable once shipped, document it in API.md:

   ```java
   enum ActivityType { DUNGEON, RAID, ARENA }

   /** One enabled difficulty: DifficultyTier id ("easy", "normal", "hard", "nightmare") and its
       required level from the admin GUI (0 = none). */
   record Difficulty(String id, int requiredLevel) {}

   /** difficulties: the enabled ones, lowest first; empty for arenas, which use requiredLevel.
       cardArt: the admin GUI's card art id, or null. */
   record Activity(ResourceLocation id, ActivityType type, Component name, List<Difficulty> difficulties,
                   int requiredLevel, @Nullable ResourceLocation cardArt) {}

   static List<Activity> activities(MinecraftServer server);
   /** Fired after the list or an activity's id, name, enabled difficulties, required levels or card art change. */
   static void addActivitiesListener(Runnable listener);

   /** Opens the activity's lobby screen for the player (lobbies, my lobby, leaderboard, invites),
       exactly as using the controller would — no block or distance needed. False if unknown. */
   static boolean openLobbies(ServerPlayer player, ResourceLocation activity);

   /** Asked before entry. difficulty null = the activity itself (seeing/entering it at all, and
       for arenas). Return null to allow, or the reason to show (it's shown as is). */
   interface EntryGate { @Nullable Component check(ServerPlayer player, ResourceLocation activity, @Nullable String difficulty); }
   static void registerEntryGate(EntryGate gate);   // several may register; the first refusal wins

   enum Outcome { WIN, LOSS }   // arenas: WIN when the max wave was reached, else LOSS (wave tells how far)
   record RunResult(ResourceLocation activity, ActivityType type, @Nullable String difficulty,
                    Outcome outcome, long durationTicks, int wave, List<UUID> players) {}
   static void addRunListener(Consumer<RunResult> listener);   // after win/loss bookkeeping
   ```
3. **Use the gate everywhere a player gets in**, for dungeons, raids and arenas:
   - opening the lobby screen (controller use, `/arenasld lobby open`, `openLobbies`) — refused
     with the reason in chat;
   - creating a lobby and changing its difficulty (`setLobbyTier`) — the owner must pass for that
     difficulty;
   - joining a lobby (request, accept invite, join) — the joiner must pass for the lobby's
     difficulty;
   - **starting a run — every member must pass** for the chosen difficulty (someone may have joined
     earlier, or levels may have changed); refuse with who and why.
4. **Lobby screen shows locks.** Send, per viewer, the gate's verdict for each enabled difficulty;
   a refused difficulty is **dimmed** with the reason next to it (Eden returns text like
   "🔒 Lv 25+"), and can't be picked. Lobbies whose difficulty the viewer can't enter show the same
   way and can't be joined.
5. **Run events.** Call the run listeners from the win/loss paths of all three types (players = the
   run's participants, duration from start tick, the difficulty id, best/last wave for arenas).
6. **Docs**: README, API.md (the API and when the gate is asked), CHANGELOG, version bump (minor,
   e.g. 4.5.0). Mention that Eden_LD uses it.

## For the owner to decide with you (don't build without a yes)

The owner is considering **leaving the lobby block behind**: activities defined without a
controller block (in saved data or config, edited from the admin GUI or commands), always loaded
and ready, instead of force-loading the controller's chunk; the hub (Eden) would be the main way to
open lobbies. Say what it would take (where definitions and lobbies would live, what happens to
existing controllers, instance spawners staying in the world) and the risks, then wait for the
owner. The API above is id-based so it works either way — build it first.

## Check

`./gradlew build` for all loaders. In a dev run: ids, required levels and card art are saved from
the admin GUI and stay after a restart and show in `ArenasApi.activities`; `openLobbies`
opens the right screen from anywhere; a test gate (e.g. refuse "hard") dims Hard in the lobby
screen, refuses setting it, refuses joining a Hard lobby, and refuses starting when one member
fails; run listeners fire on win and loss for each type.
