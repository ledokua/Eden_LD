# Eden_LD world rules — plan

Per-dimension **gamerules** (as many as possible), **PvP**, **difficulty** and optional
**world borders**, edited in the Moderation window. Asked by the owner on 2026-10-05 (handed over
from the world-rework session); decisions locked with the owner the same day. **All five phases are done (2026-10-05):
built, tested and copied to the pack, Prism and the live server, which runs it from its next restart.**

## Decisions (owner, 2026-10-05)

| Question | Decision |
| --- | --- |
| Where | A **World settings** section in the Moderation window, with sub-categories: **GLOBAL** and one for each registered dimension. |
| Dimension pages | Show **only what can differ per dimension** — server-wide rules aren't listed there at all (no greyed-out rows). |
| Server-wide gamerules | **Stay in the world save** (`level.dat`), as today. GLOBAL reads the world's values (at every start and live) and edits them like `/gamerule`; nothing is re-applied from a file. After a world reset they're Minecraft's defaults until set again by hand. |
| keepInventory | **Server-wide only** (GLOBAL) — not worth the effort per dimension. |
| reducedDebugInfo, doImmediateRespawn, doLimitedCrafting | **Server-wide only** (GLOBAL) — no per-dimension version needed. |
| spawnRadius, spawnChunkRadius | **Overworld page only** (the world spawn is there). |
| sendCommandFeedback, logAdminCommands, maxCommandChainLength, maxCommandForkCount, commandModificationBlockLimit | **Not shown** — not needed (`/gamerule` still sets them). |
| Mod gamerules | Their own **Modded** tab. |
| PvP and difficulty | **Per dimension too** (they aren't gamerules in Minecraft). |
| Borders | **Eden takes over.** Multi World Borders leaves the server when this ships; the two live borders carry over. |

## What the owner gets

**Moderation → World settings**, with a column of sub-categories: **GLOBAL**, then every
registered dimension (Overworld, Nether, End, Starlight, the five Mine Cells areas…), each marked
with how many things it changes and whether it has a border. Every page is VectorLib's settings
page (no VectorLib changes needed): tabs, a search box, switches and number boxes, and a reset
button next to anything changed (its tooltip names what it goes back to).

| Page | Tabs |
| --- | --- |
| **GLOBAL** | **Gamerules** — the server-wide values of 41 vanilla rules (below), grouped like Minecraft's own gamerule screen: Player, Mobs, Spawning, Drops, World updates, Chat, Misc. **Modded** — the server-wide values of the mod rules. Reset goes back to Minecraft's default. |
| **A dimension** | **General** — PvP (server's / on / off), difficulty (server's / Peaceful / Easy / Normal / Hard), flight (allowed / no rocket boosts / blocked — today's `flight.json` lists). **Gamerules** — the 35 rules that can differ per dimension. **Modded** — the mod rules. **Border**. Reset goes back to the GLOBAL value. |

A dimension stores only what it changes; everything else follows GLOBAL, including later changes.
Changes apply at once (no restart). Dimension settings are saved to
`config/eden_ld/dimensions.json`, which survives world resets and can be reviewed or edited by
hand (`/reload` re-reads it). Each change is logged on the server with who made it.

- `/gamerule` keeps working as today (server-wide). New `/eden gamerule <dimension> <rule>
  [<value>|reset]` for the console and scripts. `/worldborder` changes the border of the
  dimension you're in, as Multi World Borders does now (`execute in <dimension> run worldborder …`
  from the console). `/difficulty` and `server.properties` stay server-wide.
- Permission: `eden_ld.admin.world` (LuckPerms; without it, op level 2 — the same as `/gamerule`).

## Which gamerules go where

Minecraft 1.21.1 has **51 gamerules**; the server's mods add 7 more (see *Mods*).

| Where | Rules |
| --- | --- |
| **Dimension pages and GLOBAL** (35) | Player: naturalRegeneration, fallDamage, fireDamage, drowningDamage, freezeDamage, disableElytraMovementCheck, playersSleepingPercentage, playersNetherPortalDefaultDelay, playersNetherPortalCreativeDelay, spectatorsGenerateChunks, enderPearlsVanishOnDeath. Mobs: mobGriefing, maxEntityCramming, universalAnger, forgiveDeadPlayers, disableRaids. Spawning: doMobSpawning, doWardenSpawning. Drops: doMobLoot, doTileDrops, doEntityDrops, projectilesCanBreakBlocks, block/mob/tntExplosionDropDecay. World updates: doFireTick, randomTickSpeed, snowAccumulationHeight, waterSourceConversion, lavaSourceConversion, doVinesSpread. Chat: showDeathMessages, announceAdvancements, commandBlockOutput. Misc: globalSoundEvents. |
| **GLOBAL only** (6) | keepInventory, reducedDebugInfo, doImmediateRespawn, doLimitedCrafting; doDaylightCycle, doWeatherCycle (one clock and one weather for the whole server — the Overworld's). |
| **Overworld page only** (5) | spawnRadius, spawnChunkRadius (the world spawn); doInsomnia, doPatrolSpawning, doTraderSpawning (Minecraft runs phantoms, patrols and wandering traders only in the Overworld). They set the server-wide value. |
| **Not shown** (5) | sendCommandFeedback, logAdminCommands, maxCommandChainLength, maxCommandForkCount, commandModificationBlockLimit. |
| **Modded** tab (7) | Every mod rule, per dimension and on GLOBAL. |

### doMobSpawning and performance

With doMobSpawning off in a dimension, Minecraft skips the natural spawn attempts there (picking
spots and checking spawn rules around each player, plus the custom spawners) — but it still counts
the existing mobs every tick. The bigger saving is usually just having fewer mobs (AI,
pathfinding). It only matters where players are: an empty dimension spawns nothing anyway.
Spawner blocks keep working, Mine Cells' spawner runes too (Mine Cells doesn't read the rule).
Four of the five Mine Cells areas have no natural spawns at all (their mobs come from spawner
runes), so turning it off there costs nothing but gains little; **Promenade** does spawn leaping
zombies, grenadiers, mutated bats and runners naturally — off there removes them. Peaceful in a
dimension is the softer option: no monsters, animals stay.

## PvP and difficulty

Their server-wide values stay in `server.properties` (`pvp`, `difficulty`), as today; a dimension
can follow them (the page shows the server's value) or set its own.

- **PvP**: Minecraft checks the server-wide switch whenever a player hurts another (melee, arrows,
  tridents, potions); Eden asks the dimension of the player being hurt — one hook covers every
  kind of player damage. Party friendly fire stays as it is. **Duels** rely on PvP being on, so a
  running Duels match keeps dealing damage in a dimension with PvP off (Eden follows match start
  and end through `DuelsApi`).
- **Difficulty**: everything Minecraft reads from the dimension follows it — mob damage, hunger,
  local difficulty, zombies breaking doors… A **Peaceful** dimension spawns no monsters and its
  monsters despawn, as vanilla Peaceful does. Players are told their dimension's difficulty on
  join, dimension change, respawn and when it changes (vanilla always sends the server's).

## How it works (server)

- Each dimension with overrides gets one fixed thin layer over the server's rules (a `GameRules`
  subclass returned by `Level#getGameRules` for that `ServerLevel`; never a new object per read).
  Dimensions without overrides get the server's object itself. Rules a layer doesn't override are
  read **from the real server-wide object**, so mods that hook that object keep working (see Open
  Parties and Claims under *Mods*).
- `MinecraftServer#getGameRules` returns the true server-wide rules (in vanilla it is the
  Overworld's), so the Overworld can have overrides too and `/gamerule` stays server-wide.
- Override values never run vanilla's change callbacks (those message every player). Changes other
  mods make through a dimension's rules (Axiom's world editor) are saved like any other.
- Mixins: `Level#getGameRules`, `MinecraftServer#getGameRules`, `ServerPlayer#isPvpAllowed`, a
  `getDifficulty` override on `ServerLevel`, the difficulty packets and the per-dimension monster
  spawning switch, the border ones below. Events: dimension change, respawn, `/reload`, Duels
  match start/end.

## Mods

Bytecode scan of the server's mods on 2026-10-05: 251 jars plus 315 nested ones, all parsed.

- **Mod gamerules (7, all switches)**, which matches `level.dat`: axiomDoBlockDrops,
  axiomDoBlockGravity, axiomDoTrampleFarmland, axiomPlayerInvulnerability (Axiom),
  minecells.suffocationFix, minecells.mobsDropCells (Mine Cells; nothing reads the second),
  puffish_skills:announceNewPoints (Pufferfish's Skills). All are read from the dimension, so they
  work per dimension.
- **36 mods read gamerules from the dimension** (Eternal Starlight, Naturalist, Create,
  Farmer's Delight, Unique Mobs, You're in Grave Danger, Trinkets…), so they follow per-dimension
  values with no extra work.
- **Open Parties and Claims** only protects claims from mob griefing when the rules object *is*
  the server's (an `==` test). Eden keeps one fixed layer per dimension, reads every rule the
  dimension doesn't change from the server's own object (so OPAC's test passes), and asks OPAC
  itself when a dimension overrides mobGriefing. ServerCore's zombie hook just uses whatever rules
  object it gets.
- **Server-wide readers**: only Axiom's join message about spectatorsGenerateChunks (cosmetic).
  Balm's and Kiwi's dev setup commands and a Stellarity end-portal function go through
  `/gamerule`, so they stay server-wide.
- **PvP**: nothing but Minecraft's own check reads it. ResourcefulConfig's in-game
  `server.properties` editor can change the server-wide value.
- **Difficulty**: no mod reads it server-wide; 34 mods read it from the dimension (Unique Mobs,
  Draugr, Eternal Starlight, Friends & Foes, Illager Invasion…), so they follow per-dimension
  difficulty.

## Borders (replaces Multi World Borders)

Today the borders come from **Multi World Borders 1.6.4** (server-only; not in the pack): Overworld
20,000 wide (since 2026-10-05 18:05, for now — back to 30,000 once the Overworld reset is
pregenerated to ±15,000; the world-rework session says when), Nether 20,000 wide, both centered
on 0, 0; the End, Starlight and the Mine Cells dimensions have none. **Carry the live values at
deploy time.** It keeps one `data/worldBorder.dat` per dimension, inside that dimension's
own folder (lost with a reset that deletes it).

- In Eden: per dimension **on/off, center X/Z (in that dimension's own coordinates), diameter**,
  and vanilla's damage and warning settings (left at vanilla's defaults unless changed). A
  dimension without one has no border.
- Applied at every start from `dimensions.json`, so it survives world resets. Every border change
  — the Moderation window, `/worldborder`, other mods — is written back, so the file always
  matches the live borders.
- What Eden changes in vanilla: each dimension gets its own border (vanilla makes the others follow
  the Overworld's), players are sent their own dimension's border (vanilla always sends the
  Overworld's), border updates go only to players in that dimension, `/worldborder` uses the
  dimension it runs in, and the Nether's 1:8 coordinate scaling is undone for its center.
- **Only one may run**: while Multi World Borders is installed Eden's border part stays off (with a
  warning in the log). Deploying this removes Multi World Borders from the server (moved to
  `backup/removed-mods-<date>/`) and carries the two live borders into the server's
  `dimensions.json`. The old `worldBorder.dat` files stay where they are, unused.

## `config/eden_ld/dimensions.json`

```json
{
  "minecraft:overworld": {
    "pvp": false,
    "border": { "center_x": 0, "center_z": 0, "size": 30000 }
  },
  "minecraft:the_nether": {
    "difficulty": "hard",
    "gamerules": { "doFireTick": false },
    "border": { "center_x": 0, "center_z": 0, "size": 20000 }
  },
  "minecells:prison": {
    "gamerules": { "doMobSpawning": false }
  }
}
```

Only dimension settings live here; server-wide values stay in the world save and
`server.properties`. Rules or dimensions the server doesn't know (a removed mod) are kept and
skipped with a warning.

## Phases

1. ✅ **Gamerule layer** (2026-10-05) — per-dimension overrides (vanilla and modded),
   `dimensions.json`, `/eden gamerule`, the OPAC handling (`world/` package: `RuleScope`,
   `DimensionGameRules`, `WorldRules`; mixins on `Level#getGameRules`, `MinecraftServer#getGameRules`,
   `GameRules.Value#onChanged`). Tested on a dev server: block drops off in one dimension only (and in
   the Overworld while `/gamerule` stays server-wide); server-wide changes showing through where a
   dimension doesn't override; the server-wide value removing the change; a restart; a hand-edited file
   with mistakes and `/reload`; a file with a JSON error (changes refused, file untouched); OPAC claims
   with zombies trampling farmland — claimed land kept and unclaimed trampled with no override, with
   the Overworld overriding mobGriefing to true while the server says false, and with another rule
   overridden; nothing trampled with the override false; the server-wide value never changed.
2. ✅ **PvP and difficulty** (2026-10-05) — `/eden pvp`, `/eden difficulty`, `"pvp"` / `"difficulty"` in
   `dimensions.json`; mixins on `ServerPlayer#isPvpAllowed`, a `getDifficulty` override on `Level` (production
   name checked: `method_8407`), `MinecraftServer#updateMobSpawningFlags` and `#setDifficulty`; the client is
   sent its dimension's difficulty on join, respawn, dimension change and changes. Tested on a dev server with
   two players (`/damage … by Alice`, which runs the same check as a hit): hits and arrows blocked where PvP
   is off and landing where the dimension follows the server; other damage unaffected; a real 1v1 Duels
   match in a PvP-off Overworld dealt damage, and after it ended hits were blocked again. A Peaceful Nether
   despawned a summoned zombie while the Easy Overworld kept one; with the server on Peaceful and the Nether
   on Hard, 62 monsters spawned around a player in the Nether within 40 s and none in the Overworld.
   (Open Parties and Claims can't run on Hard in the dev setup — its zombie door-breaking hook crashes there
   for lack of production names — so it's out of the dev server again.)
   **Spells** (owner's question): Spell Engine never asks vanilla's PvP switch — it picks targets by its own
   relations (on FallenEden players are `FRIENDLY`: direct harmful spells hit, area ones don't), and while
   the damage itself was stopped by the PvP check, a spell's slows, poison, fire and knockback were not.
   Eden's existing Spell Engine team matcher (`CombatTeamsCompat`, party friendly fire) now also answers
   "teammates, no friendly fire" for two players' sides where PvP is off (`WorldRules.pvpAt`), which Spell
   Engine turns into `ALLY`: no harmful spell at all, healing as with `FRIENDLY`. The same answer covers
   Better Combat sweeps, Eden's projectile pass-through and pets. Tested with Alice's wolf: Player768's
   melee and magic damage landed with PvP on and were refused with it off; ownerless damage still landed.
3. ✅ **Borders** (2026-10-05) — `WorldBorders` (file, start/reload, a listener per dimension that
   tells that dimension's players and writes the file back); mixins: `MinecraftServer#createLevels` (no
   Overworld-following delegates, no server-wide update listener), `PlayerList#sendLevelInfo` (the
   player's own dimension's border), `WorldBorderCommand` (the dimension it runs in; centers in its own
   coordinates), the two border packets (they carry the stored center, as vanilla's always do). Production
   names checked. Tested on the dev server: each dimension started with its own border from the file;
   `/worldborder` in the Nether and the End changed only that dimension and wrote the file (damage and
   warning too); a Nether center of 100.5 put the edge at x≈600 — a player at 590 took no damage and one
   at 620 died to the border — and the client drew the wall there; shrinking the Overworld to 64 left the
   Nether player's wall alone, and moving to the Overworld showed its border; a restart restored all,
   taking the Overworld's border from the world save when the file had none; `/reload` applied hand edits;
   a moving border saves its target size.
4. ✅ **World settings** (2026-10-05) — `client/hub/WorldSection` (VectorLib settings pages) and
   `world/WorldAdmin` (data and changes) over the existing admin request/data/action messages (a new
   `world` area: no new message types), staff bit `STAFF_WORLD`. Border size and center are text fields
   with a Set button, and moving a border asks first. Tested in the dev client: pages, tabs, search, a
   Nether rule on and back with the reset arrow, a GLOBAL rule (`/gamerule` agreed), tick speed sent once
   on Enter, the Nether border to 15,000 through the confirm dialog.
   **Number boxes** (2026-10-05, owner approved): VectorLib's number box reported every keystroke (typing
   15000 sent 1 first), so the page first used text fields. Fixed in VectorLib 0.5.1 (`NumberField.onCommit`).
   Gamerule numbers and the border's safe zone and warnings are now number boxes (red border for bad input,
   range, reset arrow); a value outside the range, set by a command or a mod, widens it so a click-through
   never changes it. Number boxes and sliders step with the wheel only once clicked into
   (`wheelNeedsFocus`, also 0.5.1), so scrolling the page past them changes nothing. While the admin is in
   a box or slider, server updates wait until they leave it (every change is answered with new data, and a
   rebuild would drop the box). Border changes start from the border the server last sent, so a waiting
   page can't send old values. Tested on the dev server: typing 150 sent nothing until Enter (one change),
   three wheel steps after clicking in sent 151, 152, 153 without losing the box, the reset arrow sent the
   default, the wheel over a box or the damage slider not clicked changed nothing, 5000 in warning time
   showed the red border and Enter sent 3600, and a warning-distance change kept that 3600.
5. ✅ **Docs and deploy** (2026-10-05) — README (World rules, commands, Moderation, compatibility),
   `docs/LUCKPERMS.md` and lang en/uk written with phases 1–4. The jar (built 20:18, then again at 21:23
   with the number boxes) went to the pack, Prism Client, Prism Server and the live server (the one that
   last ran is in the server's `backup/removed-mods-2026-10-05/Eden_LD-1.0-SNAPSHOT-oct5-1857.jar`; the
   20:18 build, never started, is beside it as `…-oct5-2018.jar`). Multi World Borders was already
   gone: the server's 20:29 restart ran without it, and Prism Server never had it. The live server and
   Prism Server got a `dimensions.json` with the Nether's border (20,000 wide, centered on 0, 0). The
   Overworld's border comes from the world save at the first start. No message types changed, so
   old clients keep working; they just don't have the World settings page until the pack is pushed.
