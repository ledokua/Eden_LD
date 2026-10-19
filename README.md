# Eden_LD

The main server mod for **Fallen Eden** on Minecraft **1.21.1** (Fabric). Eden_LD is the hub the other
LeDok mods plug into: it carries the server's shared content (quest materials, Liquid Mana), controls
elytra and wing flight through a rule API, and connects the server to the Minestar platform (shop
delivery, prime role sync, player profiles). It replaces the old Yggdrasil_LD mod.

> Minecraft 1.21.1 · Java 21 · Fabric (needs [Fabric API](https://modrinth.com/mod/fabric-api) and
> BusyLib; VectorLib is bundled). Placeholder API (optional) adds `%eden_ld:guild_tag%`,
> `%eden_ld:guild_name%` and the Minestar ones (`%eden_ld:minestar_level%`, `_xp%`, `_playtime%`,
> `_rewards%`). Goes on the server **and** on every client. Optional, server
> side: the Minestar platform mod (shop + profiles), LuckPerms (prime role sync), Economy_LD
> (balance display), Waystones (hub Waystones tab) — everything else works without them.

## Contents

[Legacy content](#legacy-content-yggdrasil_ld) · [Liquid Mana](#liquid-mana) ·
[Flight control](#flight-control) · [World rules](#world-rules) · [Hazards](#hazards) · [Tutorial](#tutorial) · [Chat](#chat) ·
[Minestar integration](#minestar-integration) ·
[Configuration](#configuration) · [Commands](#commands) · [Addon API](#addon-api) ·
[Compatibility](#compatibility) · [License](#license)

## Legacy content (`yggdrasil_ld:`)

Content carried over from Yggdrasil_LD keeps its original **`yggdrasil_ld:`** registry ids and asset
namespace, so existing worlds, FTB quests, and Puffish skill trees keep every reference:

- **16 quest/boss materials** (Cursed Skull, Sacred Leaf, Bat Wing, …) and the **`icon`** item
  (creative tab icon, used as a quest icon).
- All quest and skill-tree textures and the `yggdrasil_ld` lang files (en_us, uk_ua) — quests and
  Puffish trees reference these keys directly.

Removed on 2026-09-28: the 19-part hammer crafting line (handles and heads) and the
`yggdrasil_ld:tier_5_weapons_raid` item tag. Liquid Mana moved to `eden_ld:` (see below).

New Eden_LD content always uses the `eden_ld:` namespace; nothing new is added under the legacy one.

## Liquid Mana

`eden_ld:liquid_mana` (fluid), `eden_ld:liquid_mana_block`, `eden_ld:liquid_mana_bucket` — moved
from `yggdrasil_ld:` on 2026-09-28 (mana placed or bucketed under the old ids doesn't carry over).
A translucent violet fluid that heals living entities standing in it — by default 5% of max health
every second (both configurable). Source blocks behave like water (infinite, bucketable), sit in the
`minecraft:water` fluid tag, and are unbreakable by explosions.

## Flight control

One system gates **vanilla elytra** and **modded wings** (anything flying through Fabric's elytra
events — Icarus wings included), on takeoff *and* mid-flight:

- `flight_disabled_dimensions` — flight is blocked entirely in these dimensions. A player already
  gliding when the rule applies is stopped and, by default, gets Slow Falling so they land safely.
- `boost_disabled_dimensions` — flight is allowed, but firework boosting is blocked. With the armor
  threshold enabled, players whose armor value is **below** the threshold may still boost (light-armor
  builds keep mobility; tanks don't get free travel).

Denied players see a throttled action-bar message. Other mods can add their own allow/deny rules —
see [Addon API](#addon-api); an arena mod can, for example, force-allow flight inside an arena that
sits in a blocked dimension.

## World rules

A dimension can have **its own gamerules, PvP mode, difficulty and world border**, set in **Moderation → World
settings** (see [Moderation window](#moderation-window)) or with the commands below; the plan is
[docs/PLAN-world-rules.md](docs/PLAN-world-rules.md).
A dimension keeps only what it changes; everything else follows the **server-wide** value — the gamerules
in the world save as `/gamerule` sets them, PvP and difficulty in `server.properties` — including later
changes. `/gamerule` and `/difficulty` themselves stay server-wide.

- **Can differ per dimension**: every mod gamerule, and 35 of Minecraft's — doFireTick, mobGriefing,
  doMobSpawning, doMobLoot, doTileDrops, doEntityDrops, projectilesCanBreakBlocks, naturalRegeneration,
  fall/fire/drowning/freezeDamage, randomTickSpeed, maxEntityCramming, universalAnger,
  forgiveDeadPlayers, disableRaids, doWardenSpawning, showDeathMessages, announceAdvancements,
  commandBlockOutput, spectatorsGenerateChunks, disableElytraMovementCheck, playersSleepingPercentage,
  the two nether-portal delays, snowAccumulationHeight, water/lavaSourceConversion, the three
  explosion drop decays, globalSoundEvents, doVinesSpread and enderPearlsVanishOnDeath.
- **Server-wide only**: keepInventory, doDaylightCycle and doWeatherCycle (one clock and one weather
  for the whole server), reducedDebugInfo, doImmediateRespawn, doLimitedCrafting; the Overworld-only
  spawnRadius, spawnChunkRadius, doInsomnia, doPatrolSpawning, doTraderSpawning; and the command
  rules (sendCommandFeedback, logAdminCommands, maxCommandChainLength, maxCommandForkCount,
  commandModificationBlockLimit).
- **Set** with `/eden gamerule <dimension> <rule> <value>` (`eden_ld.admin.world`): the server-wide
  value means "follow it" and removes the change; `… <rule> reset` does the same; `… <rule>` shows
  the value and where it comes from; `/eden gamerule <dimension>` lists what the dimension changes.
- **PvP** — `/eden pvp <dimension> off|toggle|on|reset`, the dimension's PvP mode (see
  [PvP toggle](#pvp-toggle)): **off** — nobody fights there, everyone counts as PvP off (spawn); **toggle** —
  each player's own switch (`/pvp`, the inventory button) decides; **on** — everyone counts as PvP on (a
  forced-PvP zone). A dimension without its own mode follows `server.properties`: `pvp=true` means **toggle**,
  `pvp=false` means **off**; `reset` (or choosing that default) makes it follow again. It covers everything
  players do to each other — melee, arrows, tridents, potions, explosions they set off, **Spell Engine** spells
  (which never ask Minecraft's PvP switch), Better Combat sweeps and their pets and summons. Parties, guilds and
  run teams never hurt each other in any mode; a running **Duels** match fights in any mode (Duels keeps
  outsiders out itself). Eden answers Spell Engine and Better Combat for every pair of players (enemies as
  hostile — area and direct damage, no heals —, friends and ignored pairs as allies), so the two mods'
  `"minecraft:player"` relation settings (Fallen Eden: Spell Engine `NEUTRAL`, Better Combat `HOSTILE`) no
  longer decide anything between players.
- **Difficulty** — `/eden difficulty <dimension> peaceful|easy|normal|hard|reset`: mob damage, hunger,
  local difficulty and monster spawning there. **Peaceful** spawns no monsters and clears the ones
  there, as vanilla Peaceful does; a dimension on Easy or harder still spawns them while the server is on
  Peaceful (unless `spawn-monsters=false`). Players are told their dimension's difficulty when they join,
  change dimension or respawn, and when it changes.
- **Border** — every dimension has its own world border (this replaces Multi World Borders, and stays
  off while that mod is installed): `/worldborder` changes the border of the dimension it runs in
  (`execute in <dimension> run worldborder …` from the console), centers are in that dimension's own
  coordinates — the Nether's too — and players see the border of the dimension they're in. Each
  dimension's border is kept in the file (`"border"`: center, size, and the damage and warning
  settings when they differ from vanilla's) and set at every start; a dimension without one has none.
  Every change — `/worldborder`, other mods — is written back, so the file always matches the live
  borders. If the file has no Overworld border, the world save's is taken into it.
- **Kept** in `config/eden_ld/dimensions.json`, so it survives world resets; changes apply at once and
  are saved at once, and `/reload` re-reads a hand-edited file. Settings for a dimension or rule the
  server doesn't have (a removed mod's) stay in the file, unused, with a note in the log; a file with
  an error is left alone (changes are refused until it's fixed and re-read).

```json
{
  "minecraft:overworld": { "pvp": "toggle", "border": { "center_x": 0, "center_z": 0, "size": 30000 } },
  "yggdrasil:spawn_dim": { "pvp": false, "difficulty": "peaceful" },
  "minecraft:the_nether": { "difficulty": "hard", "gamerules": { "doFireTick": false, "randomTickSpeed": 6 },
                            "border": { "center_x": 0, "center_z": 0, "size": 20000 } },
  "minecells:prison": { "gamerules": { "doMobSpawning": false } }
}
```

**Mods.** Mods that read gamerules from the dimension follow its values with no extra work (36 of the
pack's mods do). Open Parties and Claims keeps protecting claims from mob griefing in a dimension that
changes mobGriefing.

`"pvp"` is `false` (off), `"toggle"` or `true` (on); the file from before the toggle reads as it was written (`true`
was "PvP on", now "on for everyone"), and Eden writes on and off the same way, so an older Eden still reads them.

## Hazards

Fire, lava, poison, falls and the like **scale with health**, so they stay dangerous from a fresh 20-health
player to a late-game one with hundreds (plan: [docs/PLAN-hazards.md](docs/PLAN-hazards.md)). A hazard deals
**the larger of its vanilla damage and a share of the target's health × the target's multiplier**: a fresh
player takes exactly vanilla damage, and the share takes over around 70–100 max health.

- **Shares** (`config/eden_ld/hazards.json`, re-read on `/reload`): of max health (lava 5% a hit, fire blocks
  1.5%, burning 1.5% a second, freezing 3%, drowning 5%, suffocation 4%, starving 4%, the void 10%,
  lightning 15%, cactus and berry bushes 1%), of current health (poison 4% a tick — it still can't kill),
  max plus missing (wither 1% + 4%), or per point of vanilla damage (falls, elytra crashes, dripstone: 3.5%
  of max health per block). Other mods' terrain counts too: Biomes O' Plenty's bramble and fumaroles,
  Farmer's Delight's stove, Fright's Delight's berry bushes, Ube's Delight's kalan, YUNG's prickly vines,
  Create's fire and lava fans.
- **Multipliers**: players 1, other living things 0.5, bosses 0.1 — `#falleneden:bosses` (the pack's global
  pack `falleneden_bosses`; without it, `#c:bosses`). Lava and fire can't melt a boss by percent.
- **Player against player**: burning, freezing, lightning, wither and poison another player caused deal half
  their share. Poison and wither count as theirs when they gave the effect (a potion, an arrow, a spell);
  lightning when their Channeling trident called it; burning when it started while another player was
  hitting them (until the fire goes out); freezing while one hit them in the last 5 seconds.
- **What protects**: not armor — hazards skip it (and don't wear it down), so heavy armor can't sit in lava.
  Protection enchantments still do (Protection, Fire Protection, Feather Falling…, up to 80%), and so do
  Fire Resistance, Resistance, Slow Falling, Water Breathing and leather against freezing.
- Attacks stay as they are: mobs and players hitting, explosions, harming potions, falling anvils, and
  skills' damage over time (More RPG Classes' bleeding, fatal poison and molten, Soulslike's bleed, Mine
  Cells' bleeding).

## Tutorial

New players start in the tutorial (`docs/PLAN-tutorial.md`; config `tutorial.json`, re-read on `/reload`). It
replaces the old tutorial-mod-for-yggdrasil; the NPCs_LD guide dialog runs its stages.

- **Entering**: a player's very first join (no play time yet) puts them in: their inventory and trinkets are put
  aside (kept in Eden's data, so a relog or restart doesn't lose them), they get Adventure mode and are sent to
  `spawn` in `dimension` (default `yggdrasil:tutorial_dim`; empty spot = its spawn point), and
  `commands_on_start` run (default: Realm Keys locks every dimension but the tutorial's). Players who have played
  before are never put in; staff use `/eden tutorial start`.
- **Alone together**: everyone in the tutorial shares one room, but each player is in their own **phase** — they
  don't see, hear, hurt, target or push other tutorial players, and the mobs, projectiles and items they make are
  theirs alone (and go when they log out or finish). The room and decorations are seen and heard by everyone; shared
  entities (`always_visible`: the guide NPCs `npcs_ld:npc`, on Fallen Eden also the target dummy
  `dummmmmmy:target_dummy`) are seen by everyone and anyone can hit them. Any other unphased entity in the room is
  out of sight for tutorial players — add it to the list (`/reload`).
- **Private containers**: the blocks in `private_blocks` (default: the alchemy table, chests, barrels, furnaces,
  smokers, blast furnaces, brewing stands) give each player their own copy — their own items, smelting or brewing
  and lit state; the world's block stays untouched. Keep waystones, shops and the binding table off this list.
- **Blocks they can't use**: `locked_blocks` (block ids or `#tags`; default: `#minecraft:all_signs`,
  `minecraft:chiseled_bookshelf`, `#minecraft:trapdoors`) — a right-click is refused with a line above the hotbar,
  and the block is sent again so a trapdoor their game already swung open closes.
- **Locked while in it** (the busy state `eden_ld:tutorial`, so dungeon runs and duels lock the same things): the
  hub's waystone teleports, guilds and parties, claiming mail, daily rewards and rerolls, the guild bank, Wardrobe
  purchases, website purchases, `/pay`, `/withdraw` and kits (`commands.busy_blocked`), dungeons and the duel queue.
  The Waystones mod's own menu lists only waystones in the tutorial dimension, and scrolls or stones can't leave
  it. Items can't be thrown away. The hub itself stays open to look at.
- **Dying** there is cancelled: back at the tutorial spawn, healed.
- **Finishing or skipping**: the guide's cleanup runs `/eden tutorial finish {player}` last: busy and phase off,
  the saved inventory and trinkets back (what they hold is kept too), their game mode back, `commands_on_finish`
  (default: Realm Keys opens `spawn_dim`, `dungeon_dim`, `tower_dim`, `pvp_dim` and the Overworld), and a teleport
  to `finish_spot` in `finish_dimension` (default `yggdrasil:spawn_dim`).
- **Natural mob spawning** in the tutorial dimension is best turned off in World settings (`doMobSpawning`).

## Eden Hub

One parchment-styled hub screen (default key **F4**, or `/hub`) gathers the server's social and
progression features under bookmark tabs: Home · Profile · Wardrobe · Friends · Party · Guild ·
Waystones · Activities (with Duels) · Dailies · Kits, plus Settings. The hub remembers the last-opened tab during a play session and opens on
Home after joining.

The inventory has a hub button too, under the crafting grid (where the recipe book button is when Not
Enough Recipe Book keeps one, it sits beside it). Opened from there, the inventory closes first, as E closes
it, so nothing of it stays open behind the hub, and closing the hub goes back to the inventory. Opened with
the key, closing it goes back to the game. Right of it is the [PvP toggle](#pvp-toggle) button.

**Tabs are icons**, all the same width (the open one stands taller, so no tab moves when another
opens); each names itself on hover, and the open tab's name is the page title above its content.
The open sub-tab or filter inside a page (Settings sections, Mail filters, Guild views…) uses the
theme's accent-tinted button. **News on a tab**
shows Discord-style: a badge in its top right corner with a count (friend requests on Friends,
invites on Party and Guild, quests to claim on Dailies, kits ready on Kits, shop purchases on Home) or a dot on a tab
never opened; or a glow inside the tab, or both — see Settings → Notifications and → Visual.
**"?"** next to Settings explains the open tab (lang keys `gui.eden_ld.hub.help.<tab>`, so a
server can reword them in `config/eden_ld/lang/`) in a small window that can be moved, resized
and kept open while using the tab — it switches to the help of whichever tab opens; "?" again
closes it. When what the tab shows has a **tour** (`tutorial.tab_tours`), **▶** in its title bar starts a short
NPCs_LD walk-through of it — pinned lines that highlight its parts one by one — any time, as often as wanted (a
dialog waiting for something, like the guide, is set aside and comes back after). Every tab has one, going by
what's on screen: Guild for players without a guild, each guild sub-tab for members (`guild/members`, `guild/recruiting`,
`guild/quests`, `guild/level`, `guild/bank`), Duels in Activities (`activities/duels`), and the tab itself everywhere
else (Home, Mail, Profile, Wardrobe, Friends, Party, Waystones, Activities, Dailies, Kits, Settings); ▶ follows
the sub-tab as it changes, and shows only for tours whose dialog NPCs_LD has. Tours a newer Eden adds join an
existing `tutorial.json` when it loads; set one to `""` to turn it off. The parts have stable names for dialogs
(`eden_ld:hub/home/…`, `…/profile/…`, `…/wardrobe/…`, `…/friends/…`, `…/party/…`, `…/guild/…`, `…/waystones/…`,
`…/activities/…`, `…/duels/…`, `…/dailies/…`, `…/mail/…`, `…/kits/…`, `…/settings/…`, listed in `HubSpotlights` and
in NPCs_LD's editor picker).

**Any size.** Drag the hub's sides or bottom (or the dotted corner) to make it bigger, or press
the maximize button left of "?" to fill the window (again to go back); every tab stretches to
it — wider lists and chat, more cards a row. The smallest hub is 440 × 262 GUI pixels, which a
1080p window at GUI scale 4 still shows unscaled; smaller windows scale it down as before. The
size is kept on this computer (`config/eden_ld/hub_window.json`). Side-by-side panes with a gap
between them — Guild and Party members | chat, Mail list | letter, Waystones favorites | all — have
a divider to drag (double-click it to reset); it's remembered for the session.

**Home** is live today: your card (Minestar level ring, head, name, platform group, LC balance
with Economy_LD, today's dailies — over your [profile](#profile) background), pending shop
purchases, party invites and friend requests with action buttons, and the server's news (Markdown,
in the reader's language — written in the [Moderation window](#moderation-window)). **Friends**, **Party**, **Guild**, **Waystones**,
**Activities**, **Duels**, **Dailies**, **Kits** and **Settings** are live too; the other
tabs arrive in future updates — see [docs/PLAN-hub.md](docs/PLAN-hub.md) for the roadmap.

### Guilds

Persistent guilds with **leader / vice-leader / officer / member / recruit** ranks. Found one from the Guild
tab (config `hub.guild_create_cost`, default free; paid in LC through Economy_LD otherwise):
a unique name (3–24 characters), a unique **tag of up to 5 letters or digits** (shown inside
the hub only — chat and nameplates keep the LuckPerms role prefixes), and a color.

- **The tab** (in a guild): a header with the `[TAG] name`, the guild level (the bar fills toward
  the next one; hover for XP) and the settings gear, over five sub-tabs — **Members** (roster,
  member card, MOTD and chat), **Recruiting** (officers and up: invite online players without a
  guild, accept or decline applications, cancel pending invites; waiting applications show on
  the button), **Quests**, **Level** (see [Guild quests and levels](#guild-quests-and-levels)),
  and **Bank**.
- **Joining**, per guild: *invite only*, *applications* (players apply from the guild
  directory; officers accept or decline — applications wait up to 7 days, also for offline
  officers), or *open* (anyone joins). Size limit `hub.guild_member_limit` (default 5; bank upgrades add more).
- **Ranks**: officers invite, handle applications, set the MOTD, and kick/promote/demote below
  their rank (up to member). The **vice-leader** has everything the leader has — settings
  (name, tag, color, join mode), managing officers, and the bank's leader controls (tabs,
  upgrades, permissions, full vault access) — except transferring leadership and disbanding,
  which stay with the leader. Promotions go up to one step below your own rank. A leader has
  to transfer before leaving (and becomes vice-leader when they do).
- **Guild chat**: `/g <message>` and the chat panel in the tab (history kept). In the hub's guild
  and party chat, names show in their LuckPerms name color (`eden_ld.name_color`), clicking a
  name adds `@name` to your message, and Tab completes an `@` mention from the members.
- **Friendly fire is off** between guildmates exactly as between partymates (damage,
  projectiles, pets, Spell Engine/Better Combat) — and suspended the same way for duels. Guildmates whose
  [PvP](#pvp-toggle) differs (one on, one off) ignore each other instead: no heals or buffs either.
- **Guild tag**, opt-in per player (Settings → Show guild tag): the colored `[TAG]` above your
  head and before your name in [chat](#chat) (both drawn by Eden). The Placeholder API
  placeholders `%eden_ld:guild_tag%` / `%eden_ld:guild_name%` remain for other mods' formats.
  For the Minestar profile there are `%eden_ld:minestar_level%` (`12`), `%eden_ld:minestar_xp%`
  (`712/2500`, into the level), `%eden_ld:minestar_playtime%` (hours on all Minestar servers) and
  `%eden_ld:minestar_rewards%` (reward count) — empty until the player's profile is loaded.
- **Guild bank** (Guild tab → Bank), WoW-style:
  - **LC**: any member deposits from their balance; withdrawals follow a per-rank daily limit.
  - **Vault tabs** of 54 slots, bought by the leader **with the bank's LC** — the first is cheap,
    each next one costs much more (config `hub.guild_tab_costs`, whose length is the tab cap;
    guild levels can lower prices and gate how many may be bought — see level bonuses).
  - **Rights per rank and per tab**: none / view / deposit / withdraw, with a daily
    withdrawal limit in **items** (not stacks — stack sizes vary); plus each rank's daily LC
    limit. Leader and vice-leader always have full access. Defaults: officers withdraw freely, members take
    64 items a day, recruits view. The permissions window edits one rank at a time.
  - Items move by clicking or dragging: click a vault item to take it, an inventory item to
    deposit it. Left click = one item, shift + left click = the whole stack, **right or middle
    click = choose how many** (slider + number); **drag a stack** from your inventory onto the vault
    (or back) to move all of it — the grid it would land on is outlined. The server validates every
    move. The deposit box shows your own LC. The open tab's status ("Tab 1 · Items left: 5") sits
    left of the Log button.
  - **Audit log** of every deposit, withdrawal, purchase, and rights change — a window that stays
    open (and live) beside the vault, moved and resized like any hub window.
  - **Upgrades** paid from the bank: member limit (+`hub.guild_member_upgrade_step` per level,
    `hub.guild_member_upgrade_costs`) and claim limit (+`hub.guild_claim_upgrade_step` chunks per
    level, `hub.guild_claim_upgrade_costs`; see **Guild claims** below). With guild claims
    active, the upgrades box shows the guild's claims in use / limit.
  - A guild whose bank still holds LC or items can't be disbanded.
- **Guild claims (Open Parties and Claims, optional).** Eden registers guilds as an OPAC party
  system named `eden_ld_guilds`. To make guilds own claims, set in OPAC's server config
  (`<world>/serverconfig/openpartiesandclaims-server.toml`; both need a restart):
  - `[serverConfig]` → `primaryPartySystem = "eden_ld_guilds"`;
  - `[serverConfig.claims]` → `partyOwnedClaims = true`;
  - optionally `[serverConfig.parties]` → `enabled = false`, so OPAC's own parties don't
    compete with guilds.

  Then, as OPAC documents, a guild's claims technically belong to its **leader**:
  - **Who can do what:** officers and above claim/unclaim as the guild and add players to the
    claim config's groups; the leader and vice-leader edit the guild's claim config. Guilds
    have no alliances.
  - **Claim upgrades** from the bank become OPAC *bonus chunk claims* on the leader's config
    (added on top of any bonus an admin set; only Eden's part is ever taken back).
  - **Leadership transfer** moves the bonus and every chunk the old leader claimed to the new
    leader — guild land always stays with the guild. Moved chunks use the new leader's main
    claim config (sub-configs are per player).
  - **Ending a guild** (disband, or the last member leaving) works like FTB Chunks when a
    team's last member leaves: the land stays with the leader only up to their **own** limit
    (without the guild-member bonus and the bought bonus), and the rest is released — the most
    recently claimed chunks first (`hub.guild_release_newest_first`, default on; false = oldest
    first), so the guild's original base survives. Released chunks go the way OPAC releases
    inactive players' land: **expired claims** when OPAC's `playerClaimsConvertExpiredClaims`
    is on (its default; protected per the expired-claim config), otherwise wilderness. The
    leader is told how many went. Without this, a group could claim with the guild's limit,
    disband, keep the land, and repeat in a new guild forever.
  - Claim times: OPAC doesn't record when a chunk was claimed, so Eden logs every claim-owner
    change (`<world>/data/eden_ld/claim_times.dat`). Chunks claimed before this was installed
    count as the oldest.
  - **Losing members** lowers the limit; a guild then over its limit keeps its land but OPAC's
    over-limit rules apply (access throttled by `overLimitClaimAccessCooldown`, no new claims)
    until members return or chunks are unclaimed. The land can never exceed the most the guild
    was allowed at once.
  - `/guild-admin disband` releases the excess the same way.
  - Without OPAC, or without that setup, guilds work as before and claims stay personal.
- **Guild waystones**: see [Waystones](#waystones) — "Visible to Team" stones are shared with
  the owner's guild.

### Guild quests and levels

Guilds earn **guild XP** and level up (config `guilds.level_xp`: the XP for each next level —
the first entry takes a guild from level 1 to 2; the list length + 1 is the top level). The
default curve (24 200 XP to level 10) is paced for guild quests worth **100 XP (daily) and 500 XP
(weekly)**: a guild finishing 3 dailies a day and both weeklies (~443 XP a day) reaches level 10 in
about 55 days, all 5 + both weeklies in ~38, 2 + 1 in ~89. Goals grow with the member count
(`count_per_member`), so the pace per player is the same for small and big guilds. XP comes
from **guild quests** and, a little, from the **daily quests members claim**
(`guilds.daily_quest_xp`, default 5, or a daily's own `guild_xp`; daily cards show it only to
players in a guild). Level-ups are announced to the members online with what the level gives.

**Guild quests** (Guild tab → Quests) are harder and shared: every member's actions count
toward the same goal, and each card shows your share and the top contributor. The board holds
`guilds.daily_quests` daily (default 5) and `guilds.weekly_quests` weekly ones (default 2),
drawn per guild from the quests its level allows, the same set all day/week. Dailies reset with
the daily quests (`dailies.reset_hour`, `dailies.time_zone`), weeklies on
`guilds.weekly_reset_day` (default `MONDAY`) at that hour. A quest completes by itself: its
`guild_xp` goes to the guild, its `lc` into the guild bank (logged), and its `items` are mailed
to **every member at that moment**, deleted at the next reset of that board (needs
Economy_LD's mail; joining after a quest finished gets nothing).

**Adding guild quests**: `config/eden_ld/guild_quests/*.json` (a `frost_mages.json.example` is
created there) or `data/<namespace>/guild_quests/<name>.json`, then `/reload`. Same objectives,
targets and naming as [daily quests](#daily-quests), plus:

```json
{
  "period": "weekly",
  "objective": "cast_spells",
  "target": "wizards:frostbolt",
  "count": 100,
  "count_per_member": 10,
  "icon": "minecraft:blue_ice",
  "min_level": 3,
  "rewards": {
    "guild_xp": 500,
    "lc": 5000,
    "items": [{ "id": "economy_ld:silver_coin", "count": 5 }]
  }
}
```

| Field | Meaning |
| --- | --- |
| `period` | `daily` (default) or `weekly`: which board it's drawn for. |
| `count_per_member` | Added to `count` for each member the guild has when the quest is drawn (so a big guild gets a bigger goal). Default 0. |
| `min_level` / `max_level` | Guild levels that may draw it (default 1 / 0 = no top): harder quests for higher levels. |
| `rewards.guild_xp` | Guild XP. |
| `rewards.lc` | LC straight into the guild bank. |
| `rewards.items` | Item stacks mailed to every member. |

`name`, `objective`, `target`, `count`, `icon` and `weight` work as for dailies. A test pool
of 12 guild quests ships disabled: `/datapack enable "eden_ld:test_guild_quests"`, then
`/guild-admin quests <tag> reset`.

**Level bonuses** (Guild tab → Level shows them now, next, and per level) are set in
`guilds.level_perks`, in the same rule format as the [Profile](#profile) level perks — `amount`
of `perk` at level `from`, then every `every` levels (0 = once), up to `until` (0 = no end).
Defaults (owner's set):

| Level | Gives |
| --- | --- |
| 2 | +5 emoji favorite slots for every member |
| 4 | 25% off claim upgrades |
| 5 | +1 daily guild quest |
| 6 | +1 vault tab that can be bought (the 6th; `guilds.tab_cap` is 5) |
| 7 | +1 weekly guild quest |
| 8 | 25% off vault tabs |
| 9 | +1 daily guild quest |
| 10 | +5 free daily quest rerolls a day for every member |

Perks:

| Perk | What |
| --- | --- |
| `emoji_slots`, `sticker_slots` | Favorite emoji / saved sticker slots for every member. |
| `daily_rerolls` | Free daily quest rerolls a day for every member. |
| `daily_quests`, `weekly_quests` | Extra quests on the guild board (added at once on level-up). |
| `tab_discount`, `member_upgrade_discount`, `claim_upgrade_discount` | Percent off vault tabs / member upgrades / claim upgrades (90 at most). |
| `extra_tabs`, `extra_member_upgrades`, `extra_claim_upgrades` | Purchases allowed above `guilds.tab_cap` / `member_upgrade_cap` / `claim_upgrade_cap` (-1 = the whole cost list, no level gating; defaults: tabs 5, the others -1). |

### Daily quests

A few quests a day (config `dailies.quest_count`, default 4), drawn from a pool you define.
Each player gets their own set each day, never the same quest twice at once; it resets at
`dailies.reset_hour` (default 4:00) in `dailies.time_zone` (e.g. `"Europe/Kyiv"`; empty = the
server machine's zone). The day is derived from the clock and saved per player, so a restart
at reset time (or downtime across it) never skips or repeats a day. The tab shows a live
reset countdown and each quest with a progress bar and its rewards; the action slot holds a
**↻ reroll** while the quest is in progress (progress is lost; a reroll never gives a quest you
already have, nor the one it replaced — unless the pool has nothing else) and turns into
**Claim** at 100%. A reroll you can't afford is greyed out and says why. Claiming every quest unlocks the **all-missions bonus** (config `dailies.bonus`).
Players get a chat nudge when a quest completes; Home shows claimable rewards and a
"Dailies n / m" line.

**Rerolls**: `dailies.free_rerolls` free per day (default 1; per role via the LuckPerms meta
key `eden_ld.daily_free_rerolls`), then `dailies.reroll_costs` in LC (default 50, 100, 200,
400 — the last repeats; needs Economy_LD). `/dailies give-rerolls <players> <amount>` grants
extra free rerolls for today (e.g. as a quest or shop reward).

**Adding quests** — two ways, same JSON format:

- **Config folder** (easiest): drop `.json` files into `config/eden_ld/daily_quests/` and run
  `/reload`. Starting points are created there (`zombie_hunter.json.example`,
  `baker.json.example`, plus lang tables in `config/eden_ld/lang/`) — copy, drop the
  `.example`, edit.
- **Datapack**: `data/<namespace>/daily_quests/<name>.json`.

```json
{
  "objective": "kill_mobs",
  "target": "minecraft:zombie",
  "count": 20,
  "icon": "minecraft:rotten_flesh",
  "weight": 10,
  "rewards": {
    "items": [
      { "id": "minecraft:iron_ingot", "count": 4 },
      { "id": "economy_ld:bronze_coin", "count": 50 }
    ],
    "commands": ["give {player} minecraft:bread 3"],
    "text": { "translate": "daily.reward.bread" }
  }
}
```

**Two languages, no resource pack.** Quests need no authored text at all: without a `name`
the card shows a generated objective line — "Kill Zombie ×20" / "Вбити: Зомбі ×20" — with mob,
item, and spell names from the game's own translations, so every player reads their own
language. Rewards show as item icons (hover for the vanilla tooltip) plus XP / LC. For your own
names and notes, use `{"translate": "your.key"}` and put the text in server lang tables —
`config/eden_ld/lang/<locale>.json` (e.g. `en_us.json`, `uk_ua.json`; re-read on `/reload`) or
`data/<ns>/eden_lang/<locale>.json` in a datapack. The server picks each player's client
language (falling back to `en_us`); a client resource pack that knows the key still wins. With
a `name`, the generated objective line shows under it.

| Field | Meaning |
| --- | --- |
| `name` | Optional title: text or `{"translate": "key"}` (server lang tables). Without it, the generated objective line is the title. |
| `objective` | `kill_mobs`, `craft_items`, `place_blocks`, `break_blocks`, `eat_food`, `catch_fish`, `cast_spells`, `complete_dungeon`, `stat`, or another mod's progress id (`modid:thing`). Required. |
| `target` | Optional filter: an id, or `#tag` — entity types for kills, items for crafting/eating, blocks for placing/mining, spells for casts. For `stat` it's required: the scoreboard stat name, e.g. `minecraft.custom:minecraft.jump`. Omit to count anything. |
| `count` | How many are needed (default 1). |
| `icon` | Item shown on the card (default paper). |
| `weight` | Draw weight in the pool (default 10; 0 = never drawn). |
| `guild_xp` | Guild XP the claim gives the player's guild (default: config `guilds.daily_quest_xp`). |
| `rewards` | `items` (full item stacks, components allowed — pay money as Economy_LD coins: `economy_ld:bronze_coin` 1 LC, `silver_coin` 100, `gold_coin` 10 000, `emerald_coin` 1 000 000; stacks up to 99), optional `xp` points and `lc` (straight to the balance), `commands` run by the server (`{player}` = name), `text` note shown after the rewards (describe what commands give; text or `{"translate": ...}`). |

Eden counts kills (projectile kills count for the shooter), crafting (shift-click counts the
whole batch), placing blocks (planting seeds counts), mining, eating, fishing, **finished Spell
Engine casts**, and **any vanilla statistic** (`stat` — same names as `/scoreboard` criteria;
distances are in centimeters, times in ticks).

**Test pool**: 15 starter quests (kill zombies/skeletons/spiders/cave spiders/#undead/blazes/
endermen, craft food, place blocks, eat, mine ores/logs, fish, cast spells, jump) ship in the
jar as a disabled datapack — `/datapack enable "eden_ld:test_dailies"` (then
`/dailies reset <player>` to redraw today's set). Disable it again once your own pool is ready. `complete_dungeon` counts won activity runs (target = the activity id), and
custom objectives from other mods through `EdenDailyApi` (see API.md).

### Kits

With **Kits_LD** (0.2.0+) on the server, the **Kits** tab lists the kits a player can see and lets them
claim them ([docs/PLAN-kits.md](docs/PLAN-kits.md)). Kits_LD decides everything: which kits show
(`hidden_when_locked` hides a kit from players without its permission), who may claim one (`kits_ld.kit.<name>`),
the cooldown or one-time claim, and the claim itself (items into their saved slots, the kit's commands). Kits are
made and edited with Kits_LD's `/kit save` and `/kit editor`.

Each kit is a card: its icon and name (with the kit's `&` colors), a status — **Ready** (with "once every 2d" or
"a one-time kit"), **Ready in 1h 20m** counting down (the card turns claimable at zero), **Claimed** for one-time
kits, or **Locked** with the kit's own `lock_message` — and **Preview** and **Claim**. Preview shows the kit laid
out like the inventory it lands in: armor and offhand on the left, the three rows and the hotbar beside them,
trinkets below, each item with its tooltip. Claiming says what happened in a notice; it's refused while the player
is busy (the tutorial, a duel), like every hub reward. The tab's badge counts the kits ready to claim (Settings →
Notifications → Kits to claim).

**`/kits`** opens the Kits tab for players with Eden on their client; vanilla clients keep Kits_LD's chat list, and
`/kit claim <name>` works as before. The tab shows only where the server runs Kits_LD.

### Waystones

With [Blay's Waystones](https://modrinth.com/mod/waystones) on the server, the hub mirrors
your waystones — the tab only appears when the mod is installed.

- **Favorites** (left): waystones you've starred (☆/★ on any row). This is the Waystones
  mod's own Favorites group, so stars match its menus. **Drag** favorites up or down into your own
  order (kept on the server, per player); drag a waystone from the right onto the favorites to star
  it right where you drop it, or a favorite out to the right to unstar it.
- **Right-click a waystone's name** for **Rename…** (your own name for it — the Waystones mod's
  per-player alias, shown in its menus too; only you see it; saving it empty brings the original
  back), **Use the original name**, favorites, and **Forget waystone…** (asks first; it leaves your
  list and the Waystones menus until you activate it again — global and guild-shared stones always
  stay). Hover a renamed stone for its real name.
- **Waystones** (right), in sections: **Mine** (you own it), **Guild** (owned by a guildmate,
  with their name), **Friends'** (owned by a Minestar friend, with their name), **Global**, and
  **Activated** (anything else you've activated); click a section's heading to fold it (kept on this
  computer). A divider between the two columns can be dragged.
  One search covers both columns and matches owner names too. Rows show the dimension, plus
  the distance in your current dimension.
- **Hub teleports follow Eden's rules, not the Waystones mod's**: no XP/item cost, a
  cooldown (config `hub.waystone_cooldown_seconds`, default 30), and no teleporting while
  **PvP combat-tagged** (see [PvP combat tag](#pvp-combat-tag)). Busy players (BusyLib) can't
  teleport either. Waystones itself still validates the stone and moves your pets.
- **Per-role cooldown**: set the LuckPerms meta key `eden_ld.waystone_cooldown` (seconds;
  `0` = no cooldown), e.g. `/lp group prime meta set eden_ld.waystone_cooldown 10`.
- A fixed status line at the bottom counts down the cooldown / PvP block and briefly shows
  why a teleport was refused; a successful teleport closes the hub.
- The list updates live when you activate a stone or one is renamed or removed, and when
  guild membership changes.
- **Guild waystones**: on this server, Waystones' **"Visible to Team"** setting means *visible
  to the owner's guild*. Guildmates see such a stone (without having to activate it) in the
  hub's Guild section and in all of Waystones' own menus — waystone blocks, warp stones and
  scrolls, the inventory button, portstones — and can travel there. Waystones' own
  scoreboard-team matching is replaced: TEAM stones it would offer through a shared scoreboard
  team (Duels_LD puts players in those during matches) are hidden unless you activated them.
  Sharestone networks and the admin `/waystones` inspection menu are left as they are. A stone
  switched to or from "Visible to Team" shows up in the hub the next time the tab is opened.
  Waystones' own wording follows: the option reads **"Visible to Guild"** and its menu group
  **"Guild"** (English and Ukrainian), via an always-on built-in resource pack (Eden's plain lang
  files would lose to Waystones', since mod assets load alphabetically).

### Activities

Dungeons, raids and mob arenas as picture cards — **All · Dungeons · Raids · Arenas · Duels**
filters, sorting by **name / required level / type** (ascending or descending), and "Your level: N"
on top. **Duels** (with Duels_LD) shows the [duel queues and top lists](#duels) in the same page.

- **Cards** show only a picture and the name. A locked card is dimmed with "🔒 Locked · Minimum
  level N" — N is the level of its first enabled difficulty. Clicking an open card opens the
  activity's lobby screen (lobbies, leaderboard, invites — Arenas_LD); closing that screen with Esc
  brings the hub back on this tab.
- **Partymates below the level:** a ⚠ in the card's corner; its tooltip names who (online
  partymates, against the level the card opens at).
- **Set up in the Moderation window** (Activities section, `eden_ld.admin.dungeons`): every activity
  from every source is listed with whether the hub **shows** it, its **card art** (any profile
  background) and the **level** each difficulty needs (one level for arenas). **New activities are
  hidden** until turned on there; hidden ones aren't on the tab and give no unlock notice (their
  lobby block, if any, still works). Changes save themselves and reach players at once.
- **Levels** come from a Pufferfish Skills tree: config `activities.level_category` (a category id,
  e.g. `puffish_skills:combat`). While it's empty nothing is locked (the log says so once).
  Each difficulty needs its own level, and **every party member** must have it — Arenas_LD asks Eden
  when a lobby picks a difficulty, when someone joins, and for everyone when a run starts.
- **New unlocks**: when a player's level opens an activity (checked every
  `activities.unlock_check_seconds`, default 30), they get a chat line, the Activities tab a badge,
  Home a "New activity unlocked" card, and the card glows on their next visit. Settings →
  Notifications → "New activities" turns the badge and card off.
- **Stats** per player and difficulty (clears, best time; best wave for arenas) are kept in the world
  and shown in the Moderation window; won runs count for the `complete_dungeon` daily objective.

**Where activities come from**: **Arenas_LD** 4.6.0+ (its `ArenasApi`: every dungeon, raid and
arena with its id, name and difficulties; the card opens its lobby screen from anywhere; Eden is its
entry gate; finished runs feed the stats and dailies — Arenas knows nothing of levels or card art),
and datapack files for anything else or for testing (their `level`s and `card_art` are the defaults
until the Moderation window changes them) — `data/<namespace>/hub_activities/<name>.json` (id `<namespace>:<name>`, `/reload`):

```json
{
  "type": "dungeon",
  "name": "Sunken Crypt",
  "difficulties": [{ "id": "easy", "level": 0 }, { "id": "normal", "level": 5 }, { "id": "hard", "level": 15 }],
  "card_art": "eden_ld:twilight",
  "open_command": "warp crypt {player}"
}
```

`type`: `dungeon`, `raid` or `arena` (arenas use `"level"` instead of difficulties). `card_art`: a
profile background id — backgrounds with no rule are locked and hidden in the profile picker, so
card pictures don't show there unless you open them in Moderation → Customization. `open_command`
runs as the server when the card is clicked (without it, the player is told there's no lobby).

### Wardrobe

Everything a player can wear, by category — **Backgrounds** and **Hub themes** for now (built to
take character cosmetics later) — filtered **All · Owned · Shop**. Each card shows the picture (a
drawn preview for themes) and **Worn ✔**, **Owned**, its price, or 🔒. Clicking one opens it big:

- **Wear** it (backgrounds can be **taken off**), or **Buy** it when it's for sale (prices and who
  sees what are set in Moderation → Customization).
- **Gift…** anything that has a price: type a player's name and an optional note; the giver pays,
  the other player gets it unlocked at once (online or not) and a mail — in their language — saying
  who it's from. Refused when they already have it, for yourself, or for unknown names.

### Duels

With **Duels_LD** 1.4.0+ on the server, ranked duels in the hub — the **Duels** filter of the
[Activities](#activities) tab (it hides without the mod):

- **A card per mode** (1v1, 2v2): rating, leaderboard place, wins · losses · draws with the win
  rate, and **Find match / Leave queue**. While searching the card shows the time and the rating
  range it has widened to; then "Match found" while an arena frees up, and "In a match". Both modes
  can search at once. Refusals (party too big, not the leader, partymate busy, no arena set up)
  come as a notice.
- **2v2 and parties**: a party of exactly 2 queues together and only its leader starts or stops the
  search; on your own you're paired with a random partner. The card says which applies.
- **Battle rewards today** (with Economy_LD): what you've earned from duels against the daily cap.
- **Recent matches** (your last 20: result, mode, opponents and teammate, rating change, length,
  when) and **Top 1v1 / Top 2v2** (the top 20; you're highlighted, or shown below with your place).
- While the tab is open, the server pushes every queue change, match start and end, and party
  change, and refreshes every 5 seconds. Duels' own screen (B) and commands keep working.
- **Outside the tab:** while you're in a queue, Home shows "Searching for a 1v1 duel · 0:42" with
  **Leave queue**; when your match starts, a bell and a notice in the screen corner (you may be
  looking elsewhere). Settings → Notifications → Duels turns both off.

### PvP toggle

Players choose whether they take part in PvP (docs/PLAN-pvp-toggle.md) — explorers who don't want it can still
play and explore the world. **Everyone starts with PvP off** (new and existing players).

- **Switching**: the button right of the hub button in the inventory (the Duels tab's crossed swords, lit while
  on, dimmed while off, shaded while the cooldown runs; its tooltip says the state, the cooldown or combat time
  left, and "PvP is off here" where the dimension overrides it), or `/pvp on` / `/pvp off`; `/pvp` alone shows the
  state. After any switch the next one waits **1 minute**, both ways, across relogs and restarts; a
  [combat-tagged](#pvp-combat-tag) player can't switch at all. Admins: `/eden pvp player <players> [on|off]` (no
  cooldown, no combat check; the player is told). Clients older than 1.3.3 get no button and count as off unless
  they use `/pvp`.
- **Where it counts**: each dimension's PvP mode ([World rules](#world-rules)) — **off** (spawn: everyone counts
  as off), **toggle** (the player's own switch; the default while `server.properties` has `pvp=true`) or **on**
  (everyone counts as on). A player's state where they stand is their *effective* state.
- **Who does what to whom** — between two players, their pets and summons counting as their owner. "Team" means
  the same party or guild (unless friendly-fire protection is suspended, as for a duel), or the same scoreboard
  team with friendly fire off (Arenas run teams, a Duels side):

  | | Same team | Different or no team |
  |---|---|---|
  | Both on, or both off | **Friends**: heals and buffs land, no damage | both on: **enemies** (damage, no heals); both off: **ignore** |
  | One on, one off | **Ignore** (party or guild) — a run team stays **friends** | **Ignore** |

  **Ignore** means nothing lands either way: no damage, heals or buffs, no slows, pulls or knockback; area spells
  and sweeps pass over them, they aren't spell targets or highlighted, arrows and spell projectiles fly through,
  splash and lingering potions skip them — so a PvP-off guildmate can't heal or buff a PvP-on fighter risk-free.
  Splash and lingering potions give friends only their beneficial effects and enemies only their harmful (and
  neutral) ones. **Duels**: two players in the same running match follow the duel's teams (opponents are
  enemies, partners friends) whatever their switches; a duelist and anyone outside the match ignore each other.
  Run teams (dungeons, raids, arenas) stay friends whatever the switches.
- **Pets and summons** follow their owner: tamed animals, horses, dragons, Spell Engine summons, Draugr's Flame
  Atronach and Archers Expansion's Alter Ego. Nobody can hurt a pet whose owner they may not hurt, and a pet never
  goes after a player, pet or summon its owner may not hurt (it doesn't take them as a target at all).
- **Hitting someone you ignore** (melee) says why in the action bar, at most every 2 seconds: "Bob has PvP off",
  "Bob is in a duel", "Your PvP is off (/pvp on)", "You're in a duel" or "PvP is off here".
- **Nameplates**: players whose effective state is on where they stand show the crossed swords after their name
  above their head (after a guild tag and the party tint).
- **Tutorials** can point at the button: spotlight id `eden_ld:inventory/pvp_button`.
- **Under the hood**: the flag and the last switch time are per-player hub settings (`pvp`, `pvp_switched`); the
  server tells each client its own state and the world's mode (`PvpState`) and everyone's switch and duel
  (`PvpPlayers`), so Spell Engine and Better Combat target on the client as the server decides. Spell Engine's
  relations have no "ignore", so Eden refuses ignored pairs at its action gate (`EntityRelations.actionAllowed`).

### PvP combat tag

When one player's damage lands on another (melee, arrows, spells), **both** are combat-tagged
for `combat.pvp_tag_seconds` (default 30). While tagged they can't use hub waystone teleports, switch their
[PvP](#pvp-toggle), and — with `combat.pvp_tag_blocks_flight` (default on) — can't fly: elytra and wings are
grounded through the flight rules (a player already gliding gets Slow Falling). Hits between
partymates never land, so they never tag anyone.

### Mail

An MMO-style mailbox tab (needs [Economy_LD](../Economy_LD) 1.0.2 or newer, which stores the mail; without
it the tab stays hidden, and an older Economy_LD refuses to load alongside Eden). Everything a player is owed arrives here: dungeon, raid, arena and duel rewards, auction
results, offline payments, returned coins, messages from staff — and pending **website purchases**
(Minestar): one pinned entry per purchase (its name ×quantity) with **Claim**, and **Claim all** when there are
several. They are read from Minestar each time, so they never expire and stay pending on the website until
claimed; the join message about them opens this tab (`/hub mail`), and `/shop-receive` claims them all.

- **List** (newest first): the mail's kind, title, date and time left, an unread dot, and a marker when
  it still holds items or coins. Filters: All, Unread, With items. **Claim all** takes every mail whose
  items fit; **Delete read** clears read mail that holds nothing.
- **Open mail**: title, sender, date, "deleted in N days", the text, the attached items and coins,
  **Claim** (all items must fit the inventory; coins go straight to the wallet) or **Delete** once
  claimed. Opening a mail marks it read.
- **Text** is Markdown (VectorLib's: `**bold**`, `*italic*`, `{#ff8800}color{/}`, `# headings`,
  `{size:1.5}sizes{/}`, `-# subtext`). Staff mail can carry one version per language — each player reads
  their own, else the default — and `{player}` / Placeholder API placeholders filled in for each reader.
- **Wallet bar** along the bottom: your Economy_LD balance, an amount field with **Withdraw** (paid out
  as Economy_LD coins, in the fewest coins; refused when they don't fit your 36 main slots) and
  **Deposit all** (every coin and money bag you carry). Economy_LD toasts the outcome, and the balance
  refreshes after every deposit or withdrawal, including ones made by right-click or command.
- **Tab badge**: the unread count (Settings → Notifications → Unread mail), and a Home card.
- Claiming (mail and purchases) is refused while BusyLib reports the player busy (duel, dungeon run, the
  tutorial).
- Expired mail is deleted with whatever it still holds (Economy_LD's `mail.systemExpiryDays`, 7 days,
  for automatic mail; staff choose up to `mail.maxExpiryDays`, 30).

### Moderation window

Staff get a **Moderation** tab (anyone with an `eden_ld.admin.*` node or a BanHammer command, or op)
that opens a separate window — a plain, dense editor rather than the hub theme. The sidebar lists only
the sections you may use:

- **Mail** (`eden_ld.admin.mail`): write a mail to **players** (by name), **everyone** (players who
  join later get it too, until it expires) or **LuckPerms groups** (their direct members when it's
  sent). Sender name, expiry (days, up to Economy_LD's `mail.maxExpiryDays`), coins, and items in up to
  `mail.maxItems` slots — click an item in your inventory, or drag one from **EMI**'s panel (it shows
  beside the window, which moves left to make room; the players' hub never shows EMI); the slots hold
  copies, you keep yours. Scrolling a slot changes the count, right-click clears it.
  - One title and text **per language** (★ marks the default, read by players whose language has no
    version). **Edit text…** opens the text on a screen of its own: VectorLib's editor (bold,
    italic, colors, headings, sizes; **{PH}** lists every placeholder on the server — `{player}`,
    `{player_uuid}` and all Placeholder API ones — with its value for you) beside a **preview** of
    the mail exactly as the Mail tab will show it, with your own placeholder values.
  - **Drafts** save themselves on the server while you type — close the window, leave, restart, and
    they're still there; several per admin. The list names each by its title (or when it was
    started); **✎** gives one a name of its own, **+** starts a new one, **✕** deletes it.
  - **Send** checks first: how many players it reaches, names nobody has joined with, and anything
    missing (a title, recipients); then asks to confirm.
- **Mail log** (your own mail with `eden_ld.admin.mail`; everyone's with `eden_ld.admin.mail.log`):
  sent staff mail with its recipients, how many read and claimed it, the mail as players see it, and
  **Recall** (it disappears for everyone; claimed items stay claimed).
- **News** (`eden_ld.admin.news`): the Home tab's news — Markdown, one text per language (★ the
  default), written on the same text screen with the Home tab as the preview. **Publish** puts it on
  everyone's Home tab at once (placeholders filled in for each reader); **Revert** drops unpublished
  changes. Kept in the world's data.
- **Customization**, with a tab each for **Backgrounds** (`eden_ld.admin.backgrounds`) and **Hub
  themes** (`eden_ld.admin.themes`): every profile background or theme with its state (themes as a
  small drawn preview); for the one picked, a big preview and its rule — **locked by default** (off: everyone may use it), **unlocked for
  LuckPerms groups** (checkboxes; members through other groups count too), **hidden while locked**
  (on: players who can't use it don't see it; off: they see it locked), and a **price** in LC (0: not
  for sale). Changes save themselves. See [Profile](#profile).
- **Players**: search anyone who has joined — with nothing typed, who's online and then the 30 most
  recently active ("3 h ago") — and open their page. Its tabs, each for staff with its node:
  - **Punish** (with [BanHammer](https://modrinth.com/mod/banhammer)): **Ban, IP ban, Mute, Warn** —
    permanent or for a while (1h, 1d, 7d, 30d, or any length like `2d12h`) — or **Kick**, with a
    reason and optionally **Silent** (only staff see the chat message); lift active punishments
    (**Unban, Unban IP, Unmute, Unwarn**; **Pardon all**), and their history. It runs BanHammer's own
    commands as you, so its permission nodes (`banhammer.punish.ban`, `banhammer.unpunish.unban`, …),
    protected players, duration limits, messages, warn actions and Discord webhooks all apply — you
    only get the buttons you may use, and its replies show in the window.
  - **Unlocks** (`eden_ld.admin.themes` / `eden_ld.admin.backgrounds`): every hub theme and profile
    background with what the player has — open to them (free, by rule or group), unlocked for them
    (**Lock** takes it back), or locked (**Unlock**).
  - **Dailies** (`eden_ld.admin.dailies`): today's quests and progress, rerolls used, **Give** extra
    rerolls for today, **Reset today**.
  - **Activities** (`eden_ld.admin.dungeons`): the player's activity level (while online), and every
    activity with the level it opens at and clears per difficulty; **Complete** (records a won run
    without a time) and **Reset** (forgets its stats).
  - **Emojis** (`eden_ld.admin.emojis`): their favorite-emoji and sticker slots, bonus grants with
    when they end; give more (amount, duration like `30d` or permanent) or clear them.
- **Punishments** (`eden_ld.admin.punish.log`): everyone's latest punishments from BanHammer's history
  (`storeAllPunishmentsInHistory`, on by default); a click opens that player's page.
- **Guilds** (`eden_ld.admin.guilds`) and **Parties** (`eden_ld.admin.parties`): every one with its
  leader and members (online dot), and **Disband**.
- **World settings** (`eden_ld.admin.world`): a column of pages — **GLOBAL**, then every dimension,
  each marked with how many things it changes and ⬜ when it has a border — each a settings page with a
  search box. GLOBAL: the server-wide gamerules (as `/gamerule`) — **Global**, the six with one value for the
  whole server that no dimension can change (keepInventory, doDaylightCycle, doWeatherCycle, reducedDebugInfo,
  doImmediateRespawn, doLimitedCrafting); **Gamerules** and **Modded** (the mods' rules), the defaults a dimension
  can set for itself — a rule some dimension does set says which, with their values ("Set differently in: Nether:
  Off").
  A dimension: **General** (PvP mode — server's, off, each player's choice, on —, difficulty, flight), **Gamerules** (only those that can differ per
  dimension; the Overworld's page also has the Overworld-only ones, which set the server-wide value),
  **Modded** and **Border**. A reset arrow takes a value back to GLOBAL's (on GLOBAL, to Minecraft's
  default); changes apply at once and are logged with who made them. A number is sent on Enter, on leaving
  its box, or by a wheel or arrow step once you've clicked into it — the wheel over a box or slider you
  haven't clicked scrolls the page. Moving a border — switching it on, its size or center (with their Set
  buttons), *Center on me* — asks first, saying where it will be. See [World rules](#world-rules).
- **Server**: **Reload emojis** (`eden_ld.admin.emojis`); **Write list** — every command and the level it
  needs, to `eden_ld-commands.md` (`eden_ld.admin.commands`); and the **op-only commands**
  (`eden_ld.admin.commands`) — pick commands to keep for ops whatever their mod allows; saved to
  `commands.json` and applied at once.

The inventory key doesn't close the window (it isn't an inventory); Esc does.

### Profile

Every player has a **profile page** (the Profile tab; `/hub profile`; the level-up message links
there), laid out like the Minestar site's profile, on a background of their choice:

- **Header**: head with the Minestar level on a badge, name, **Prime**, the platform group, the
  guild, "member since" (Minestar registration date).
- **Total time**: hours on all Minestar servers, and on this one.
- **Minestar activity level** (named so it isn't mistaken for this server's skill tree level): Minestar's level as a ring, XP into the level and XP to the next.
- **Rewards**: the player's Minestar rewards (achievements) as icons framed in their rarity's color
  (common, rare, epic, legendary) — hover for title and description.
- **Level rewards** (own page): what the level gives now, what the next level gives, and a track
  of the reward levels around yours.

**Other players' profiles** open by clicking a friend's card or a player's face and name on the
Friends tab (right-click there for more, like **Remove friend**) and from the **Profile** button on
party and guild member cards — unavailable when their Settings → Privacy → **Who can see my
profile** (Anyone / Friends / No one) says no. The Profile bookmark always leads back to your own
page. Minestar data for offline friends is
fetched when the page opens (kept a few minutes).

**Backgrounds** (Steam-style): picked (and bought) in the [Wardrobe](#wardrobe) tab; it shows on
your profile, your Home card and the mini profile others see when they hover your card in their
Friends tab (it shows whatever your privacy setting — that only guards the profile page). The
cards in the Friends lists themselves always use the hub theme, so every name reads the same. The default is **no background** —
pages and cards keep the hub theme's look. Built in: Twilight, Old Parchment, Aurora, Embers, Pine
Forest, Deep Sea, Starfall, Blossom, Clouds, Dark Castle, Space, Desert, Knight's Camp, Umbrella, Dark Peak,
Frieren in the Snow, Frieren in the Sun, Green Forest, Lantern Field, Bright Castle, Windmill, Pink Forest,
Red Stones and Sakura. Blossom
and Desert are light pictures with dark text (`assets/eden_ld/textures/gui/profile_backgrounds/blossom/`
is the example to copy); pictures can be any 2:1 size — the newer ones are 1024×512. Who may use
which is set in the [Moderation window](#moderation-window)'s **Customization** section: open to
everyone (Twilight and Old Parchment until changed), or locked — then open to chosen LuckPerms groups,
**hidden** from other players' lists (the default) or shown to them locked, and **for sale** when it
has a price: the player clicks it, sees a big preview and "spend N LC on it?", and it's theirs (and
on their profile) once paid. Per player, `/hub background unlock <players> <background>` /
`/hub background lock …` (`eden_ld.admin.backgrounds`; OPs by default) still work — e.g. a shop
item.

**Making a background** works like a hub theme — a JSON file per background
(`background.json`, may be just `{}`) in the background's folder of pictures,
`assets/<ns>/textures/gui/profile_backgrounds/<name>/`: `background.png` (2:1, e.g. 1024 × 512), and optionally
`card.png` (Friends cards, 512 × 128) and `home.png` (the Home card, 1024 × 128). The JSON can set
text colors for light pictures and how much the picture is darkened. The full
guide with every size and aspect ratio: [docs/THEMES.md](docs/THEMES.md).

**Level rewards** — the Minestar activity level gives perks (config `profile.level_perks`):

| Level | Gives (default rules) |
| --- | --- |
| 1, 3, 5, … (odd) | +1 emoji favorite slot |
| 2, 4, 6, … (even) | +1 saved sticker slot |
| 5, 15, 25, … | +5 claims on the player's own Open Parties and Claims limit |
| 10, 20, 30, … | +1 free daily quest reroll per day |

Each rule is `{"perk", "from", "every", "amount", "until"}` — `amount` of the perk at level
`from`, then every `every` levels (0 = once), up to `until` (0 = no end); perks `emoji_slots`,
`sticker_slots`, `claims`, `daily_rerolls`. They add to the LuckPerms/config limits and
`/emojis bonus` grants. The level is re-read on join, when the Friends tab opens, and every
`profile.refresh_minutes` (10) while playing; the last seen level is kept in the world data, so the
perks hold before the next fetch. A player who levels up is told what they got (or what the next
reward is), with a link to their profile; the first time Eden sees a player's level, they're told
what it already gives. Bonus claims are added to OPAC's per-player bonus as a tracked difference
(an admin's own bonus stays), and follow rule changes on the next fetch.

### Themes

The hub's look is a **theme**: Parchment (default), **Dark**, and **Stone Tablet** ship built in.
Players pick theirs on the Settings tab (only for themselves; saved on the server, applied
from login). Who may use which is set like backgrounds, in the Moderation window's
**Customization → Hub themes** tab: open to everyone (Parchment and Dark until changed), or locked —
then open to chosen LuckPerms groups, hidden from the picker or shown there with 🔒, and **for
sale** when it has a price: the player picks it in Settings → Visual, sees a preview and "spend N
on it?", and it's theirs (and on) once paid. Per player: `/hub theme unlock <players> <theme>` /
`/hub theme lock <players> <theme>` (`eden_ld.admin.themes`; OPs by default), e.g.
`/hub theme unlock @p eden_ld:stone_tablet` as a quest or shop reward.

**Making a theme** needs only a resource pack (ship it with the modpack) — sizes and details for
themes and profile backgrounds in [docs/THEMES.md](docs/THEMES.md):

- One folder per theme, `assets/<ns>/textures/gui/themes/<name>/` — the theme's id is
  `<ns>:<name>` — holding `theme.json` and its textures: `panel.png` (48×48 nine-slice, 8 px border)
  and `button_normal.png`, `button_hover.png`, `button_disabled.png` (32×16 nine-slice, 4 px border).
  (`"textures"` in the JSON points elsewhere, e.g. to reuse parchment's.)

```json
{
  "name": { "translate": "hub_theme.mypack.frost" },
  "textures": "mypack:textures/gui/themes/frost/",
  "text_shadow": true,
  "palette": {
    "panel": "#1E2A33", "panel_2": "#24323D", "hairline": "#4A6272", "hairline_hi": "#0A1116",
    "row_bg": "#2A3A46", "row_bg_alt": "#24323D",
    "ink": "#E6F0F5", "ink_mid": "#C2D2DB", "ink_dim": "#7E929E",
    "accent": "#7FC8E8", "good": "#7FD08F", "warn": "#E8C070", "danger": "#E87A6A",
    "bar_track": "#121A20", "bar_fill": "#4A9AB8",
    "button_text": "#E6F0F5", "button_text_hover": "#FFFFFF", "button_text_disabled": "#5E7280",
    "panel_tint": "#FFFFFF"
  }
}
```

Everything is optional — a theme can be a palette over the parchment textures, or textures
only. `name` without a value uses the lang key `hub_theme.<ns>.<name>`. Colors are `#RRGGBB`
or `#AARRGGBB`; `text_shadow` suits dark themes. F3+T reloads themes live. Then open, sell or
unlock it in Moderation → Customization → Hub themes.

### Settings

Per-player preferences, saved on the server and enforced there, in sub-tabs like a game's
options. The search field (top right) finds a setting in any sub-tab by its name or description;
a setting moved off its default gets a small reset arrow beside it (its tooltip names the default).

- **Privacy**
  - **Allow party invites from** — *Anyone* (default), *Friends* (Minestar friends only; shown
    when Minestar is installed), or *No one*. Enforced for the hub and `/invite` alike.
  - **Show guild tag** — your `[TAG]` in chat, the tab list and above your head (off by default).
  - **Who can see my profile** — *Anyone* (default), *Friends*, or *No one*; others' Profile
    buttons follow it (you always see your own).
  - **Ignored players** — everyone you ignore, each with *Stop ignoring* (the way back to someone
    offline and not your friend). See [Ignoring players](#ignoring-players).
- **Chat**
  - **New chat look** — the rounded chat panels in your hub theme's colors, with smooth motion and channel tabs
    (default on); off brings back the 1.2 chat (its pips). It applies the next time you open chat. See [Chat](#chat).
  - **Closed chat lines** (with the new look) — *Rounded strips* (default): while the chat is closed, each message
    sits on its own rounded strip in your theme's plate color, as strong as *Text Background Opacity* sets it;
    *Classic*: the previous band. Applies at once. (Without the rounded-box shader the classic band is used.)
  - **Mention sound volume** — the ping when someone writes `@YourName`: a slider, 0–100% (0 = off,
    default 90%). Letting go plays a test ping. No game slider changes it — not "Players", not master
    volume (the ping makes up for it); only master at 0%, which mutes the whole game, silences it.
  - **Death messages** / **Advancement messages** — see *Everyone's* (default), only your
    *Party & guild's* (and your own), or *Nobody's*.
  - **Channels** — show or hide Global, Local, Party and Guild chat, and Discord messages, in
    your chat; with the new look a hidden channel also loses its tab (it shows again while it's
    the one you type into, e.g. after `/ch` or a click on its `[tag]`). Your own messages always
    show; the hub's Party and Guild tabs keep their chat. Discord lines are recognized by
    `chat.discord_line_marker` (`[Discord]`, Minestar's default format). With the new look, on
    servers that keep them (Eden 1.3.1+):
    - **All tab** — the tab with every channel together, first in the chat's tabs (default on).
    - **System messages in channel tabs** — the server's and other mods' lines also show in the
      Global, Local, Party and Guild tabs (default on); All and System always have them (system-only
      lines: only System, see [Chat](#chat)). Deaths and advancements follow their own settings above in
      every tab.

    With the 1.2 look (*New chat look* off): **Only the selected channel** — the chat shows just the
    channel you type into (Discord counts as Global), switching with it.
  - Hiding is done by your client: every line still arrives (marked with its kind by an
    invisible piece of text), hidden ones are just left out of the chat window — turn a kind
    back on and what you missed shows again (up to the chat's history size).
- **Recent Global chat on join**: a joining player gets the last `chat.join_history` (20)
  Global and Discord lines no older than `chat.join_history_minutes` (120), under a
  "Recent Global chat" header (kept in memory; a restart starts fresh).
- **Notifications** — which news the tabs point out: tabs you haven't opened, friend requests,
  party invites, guild invites, daily quests to claim, shop purchases (all on by default).
- **Visual**
  - **Hub theme** — see [Themes](#themes).
  - **Notification style** — *Badge* (default), *Glow*, or *Badge and glow*.
  - **Notification color** — *Theme* (the theme's `notification` color; parchment's is red) or
    red, orange, yellow, green, cyan, blue, purple, pink.

### Friends

Your **Minestar platform** friends, in-game (the tab only appears when the Minestar mod is on
the server). Friendships live on the platform, so they are the same ones players see in the
launcher.

- **Friends list** (center), Steam-style: grouped under *IN GAME*, *ONLINE* and *OFFLINE* (click a
  heading to fold that group; it stays folded on this computer, `config/eden_ld/hub_folds.json`); each
  friend is a card with their head at the left, a status stripe (green here, blue online
  elsewhere, grey offline), their name and what they're doing — and no buttons: **click** opens
  their profile, **right-click** has *Invite to party* (friends on this server), *Ignore* and
  *Remove friend*, and **hovering** shows a mini profile card (their profile picture, head, name,
  status). Friends you ignore who are online have their own *IGNORED* group, right before *OFFLINE*.
  Both side panels fold away with slim bars, sliding in and out (so do the Guild tab's create form and invites
  without a guild); each hidden panel adds a column of friends (1 → 2 → 3).
  Search by name, and add `@pack` words to filter by modpack name or id — `@garden mas` finds
  friends named *…mas…* playing GardenMyth. Each friend shows head, name, and status — the dimension they're in (in game), *online*
  or *playing* a modpack elsewhere (launcher-reported), or *last seen … ago*. Removing a friend
  asks for confirmation.
- **On this server** (left): every other online player with an always-visible search, as the same
  cards as friends (green stripe: they're here) with the relation underneath — *Friend*, *Request
  sent*, *Wants to be friends*, or *On this server*. Right-click for **Add friend** / **Accept** /
  **Decline** / **Cancel request**, **Invite to party** and **Ignore** — friends or not; the hover card says
  why an invite can't be sent (already in a party, already invited, not accepting your invites,
  party full, …). Adding someone who already sent you a request makes you friends right away.
- **Requests** (right): incoming requests (accept / decline) and the ones you sent (cancel),
  in a side panel toggled by the slim bar on the right edge. It opens by itself while incoming
  requests wait; the Friends bookmark and the Home tab show the count.
- The tab re-fetches your profile each time it opens (at most every 15 seconds), and players
  joining or leaving update it live. Players with pending requests get a chat nudge on join.

#### Ignoring players

Right-click a player in the Friends tab → **Ignore** (friends too), or `/ignore <name>` (anyone who
has played here, online or not). From then on:

- **Their chat is hidden**: Global, Local, Party and Guild lines, the hub's party and guild chat
  panels, the recent chat on join, and their deaths and advancements. Your client hides the marked
  lines, so *Stop ignoring* brings back what's still in your chat history.
- **Whispers** (`/msg`, `/tell`, `/w`) and **`/me`** from them don't reach you (the server drops
  them; those don't come back).
- **Their @mentions don't ping you.**
- **Their party and guild invites** are refused like your "Allow party invites from" setting
  ("isn't accepting party invites from you"), and you're left out of their guild invite list.
- **Their friend requests** still arrive in the requests panel (last, marked ⊘), but without the
  chat notice, the join reminder or the badge count.
- **Lists put them last** among the players online, right before the offline ones, with a ⊘ before
  their name: On this server, the requests panel, the party and guild invite pickers, and the party
  and guild member lists (within each rank).

Anyone can be ignored, staff included, and they aren't told: their side works as usual (their
whisper shows as sent). Your list is kept on the server (up to 200 players), so it follows you to
another computer; Settings → Privacy → **Ignored players** lists it with *Stop ignoring*, and so do
`/ignore` (the list) and `/unignore <name>`. Discord lines can't be ignored (the bridge sends only the
author's name).

**Player names** in the Friends tab, party and guild chat, and Home show **LuckPerms
prefixes and suffixes** for players online on the server — legacy codes like
`&c`, `&l`, hex `&#ff8800`, and TAB-style tags (`<#ff8800>…</#ff8800>`, gradients, `<red>`, `<b>`)
are rendered. They update live when someone joins or leaves or
when LuckPerms data changes (group or prefix edits). Offline players show their plain name
(LuckPerms only has online players loaded). Party and guild member lists, invite pickers
and applications show plain names. Unsent party/guild chat text survives live updates.

### Parties

**Temporary** groups with **leader / vice-leader / member** ranks. There is no "create party"
button: invite someone (`/invite <player>` or the hub's searchable picker — online friends
first) and the party is born the moment they accept, with the inviter as leader. A party lives
only while its people do: it disbands when the member count drops below 2 or the last member
logs off. Nothing is saved across restarts.

- **Friendly fire is always off** between partymates — enforced server side for all damage and
  integrated with **Spell Engine** / **Better Combat** team matchers, so spell AoE and sweep
  attacks skip partymates natively. Arrows and other projectiles fly through partymates, and
  **pets count as their owner**: partymates can't hurt each other's pets (tamed animals,
  horses), pets can't hurt partymates, so pets never turn on the party. Partymates whose
  [PvP](#pvp-toggle) differs (one on, one off) ignore each other: no damage, and no heals or buffs either.
- **Nameplates**: partymates' overhead names render in cyan (each player sees their own party
  tinted — parties you're not in look normal).
- **Disconnect handling**: a disconnected member keeps their spot for a grace period (config,
  default 3 minutes) before being auto-kicked; a disconnecting **leader** hands leadership to
  the oldest active member immediately and returns as vice.
- **Join modes** (settings dialog): *Invite only* (default), *Friends join freely* (Minestar
  friends of any member can join from their Party tab, no invite needed), or *Open to everyone*.
  Joins are instant — there is no request queue.
- Leaders and vices also configure: party name (free-form, not unique — the party's identity is
  an auto-generated tag like `<creator>_party`), and who may invite. Invites expire after five
  minutes.
- **Party chat**: `/p <message>` from anywhere, plus a chat panel with history in the tab.
- **BusyLib**: busy players (e.g. mid-dungeon) can't grow a party — no inviting, accepting, or
  joining; leaving is always allowed.
- Other mods read parties through `EdenPartyApi` — see [API.md](API.md).
- **FTB Teams parties are off** (FTB Teams stays for FTB Quests): FTB Teams refuses new parties —
  its *Create a Party* button greys out and `/ftbteams party create` refuses, both saying parties and
  guilds are in the hub (with the hub key) — so FTB Quests' progress and rewards stay per player.
  Config `hub.ftb_teams_parties` (default `false`) turns them back on. Parties made before stay until
  an op runs `/ftbteams force-disband <name>#<first 8 characters of the team id>` (from
  `/ftbteams list parties`, e.g. `OldParty#a5fbbf60`; other characters than A–Z, 0–9 and _ become _).
  Fallen Eden also makes `/ftbteams` op-only (`commands.json`).

Planned (not yet built): shared mob-kill XP with a small group bonus for partymates in range.

## Chat

Eden formats and routes all player chat — it **replaces Styled Chat** (remove it; Eden logs a
warning while it's installed, since it would re-format every message).

**Eden's chat window** ([docs/PLAN-custom-chat.md](docs/PLAN-custom-chat.md)): on Eden servers the client draws the
chat itself instead of Minecraft's window — in the same place and size, following the game's chat settings (scale,
width, height, line spacing, opacity, text background, chat delay, hidden chat). Typing stays Minecraft's own input
(command suggestions, ↑/↓ history, signed chat), and every message still reaches Minecraft's chat too (the log, the
narrator, other mods). What it adds:
- **1000 messages** of history (Minecraft keeps 100), and switching channel view is instant. They stay through a
  server switch behind a proxy (a reconfiguration), as Minecraft keeps its own 100, still read or unread as they were.
- **Heads**: each player's face before their message.
- **Spam protection**: the very same line again from the same sender, right after it, adds a gray **×N** to the last
  one instead of a new line (it comes back on screen and counts as new).
- **Scrolling holds still** while you read older lines and new ones arrive.
- **Hover a message** in the open chat: its lines light up, and its line shows when it came ("22:41"; with the date
  for older days), then **↩ Reply** (someone else's message), **⧉ Copy** and **⋯** (the menu).
- **Right-click a message** (an emoji or sticker keeps its favorite toggle): **Reply** (switches to its channel and
  types "@Name "), **Copy text** (the message without the sender part; server emojis back as `:name:`, [GIF] and
  [Sticker] links as their addresses, spoilers as their text), **Open profile** (the hub), **Ignore** / Stop
  ignoring, and for staff who may punish, **Moderate** (the Moderation window on that player's page). The server's
  lines and your own only copy.
- **The look** (1.3.0, [docs/PLAN-chat-v2.md](docs/PLAN-chat-v2.md)): the open chat (bed chat too) sits on rounded,
  translucent panels in the hub theme's colors — dark glass on the light themes (parchment, stone tablet) — that fade
  in when it opens and out when it closes; everything stays where Minecraft puts it. Messages light up as rounded rows
  on hover, with their time and buttons on one rounded bar. **↓ New: N** shows while you've scrolled up (back to the
  newest line) and **↓ Mentions: N** while mentions wait (to the oldest one). A view with nothing in it says so. The
  game's *Text Background Opacity* fades the panels (its default, 50%, is the full look; 0% hides them). Themes can
  set the chat's colors in a `chat` block of their `theme.json` (`plate`, `plate_input`, `border`, `focus_border`,
  `ink`, `ink_dim`, `hover`, `unread`, `accent`; `glass`: `auto`, `on` or `off`). Settings → Chat → **New chat look**
  turns it off (the 1.2 look).
  - **Menus** in the same style: right-click a tab (see *Tabs* below) or a message (or its ⋯: Reply, Copy text,
    Open profile, Ignore and, for staff, Moderate). Submenus open on a short rest or a click; one Esc closes them
    all.
  - **The emoji picker** is the hub's: a tab grid, search (Enter inserts the first match), Shift+click to pick
    several, right-click to star an emoji or save/drop a sticker; it slides up beside the emoji button and one Esc
    closes it. Typing goes back to the chat line after a pick (Shift-picks while searching keep the search).
  - **Closed chat**: each message on its own rounded strip (Settings → Chat → *Closed chat lines* → Classic for the
    previous band).
- Other servers keep Minecraft's chat as it is.

**Channels** (Project Zomboid style): every message goes to one of

| Channel | Who reads it | Tag | Discord |
| --- | --- | --- | --- |
| **Global** | everyone | `[Global]` | ✔ (the only one) |
| **Local** | players within `chat.local_radius` blocks (default 100), same dimension | `[Local]` | ✘ |
| **Party** | your party (needs one) | `[Party]` | ✘ |
| **Guild** | your guild (needs one) | `[Guild]` | ✘ |

- **Tabs** (the new look, 1.3.1, [docs/PLAN-chat-v2.md](docs/PLAN-chat-v2.md) phase 5) in a row above the open chat:
  **All** (every channel together; Settings → Chat → *All tab* turns it off), **Global**, **Local**, **Party** and
  **Guild** (while you're in one), and **System** (the server's messages, deaths and advancements). The row wraps
  into a second one on a narrow chat; the chat then keeps its full height (a short screen shows a line or two
  fewer), so the tabs never move.
  - **A channel's tab is where you write**: clicking Local reads Local and sends your messages there. All and System
    keep the channel you write in, shown by the **sign left of the input line** (G, L, P, Gd — in Ukrainian З, П,
    Гр, Гд — in the channel's color; a typed `!l ` turns it to L, a `/` command to a dim "/"). **Click the sign** for
    the next channel (Shift+click: back), right-click it for that channel's menu. It pops when the channel changes.
  - **Switching**: click a tab (a channel's tab also jumps to its oldest mention you haven't seen), **Ctrl+Tab** /
    **Ctrl+Shift+Tab** for the next / previous tab, or **Shift+Tab** alone on an empty line (with text, Shift+Tab
    stays Minecraft's). The lit plate glides to the new tab and the lines slide in from its side; a new tab starts at
    its newest line. Page Up / Page Down page by the lines shown. The tab lasts until you quit the game.
  - **Hidden channels** (Settings → Chat → *Channels*, or *Hide tab*) have no tab and leave All; a hidden channel's
    tab still shows while it's the tab you're on (`/ch` or a `[tag]` click on a channel's tab moves you there),
    marked so in its tooltip. Ways back: *Show hidden tabs ▸* in any tab's menu, Settings → Chat,
    `/ch`, a `[tag]`, or `!x` for one message.
  - **Right-click a tab**: on a channel, its menu (below), plus **Discord messages** (Global: show or hide the
    Discord lines), **Hide tab** (never the last of All and the channels) or *Show tab* on a hidden one, **Show
    hidden tabs ▸**, and *Chat settings*. System: *Mark as read*, **Also in channel tabs** (the server's lines in the
    channel tabs too). All: **Mark all as read**, *Hide tab*.
  - **The closed chat** shows the tab you were on, plus mentions of you from other tabs you haven't seen yet.
  - **System-only lines** (1.3.2): routine lines go to the **System** tab alone. They don't show in All, the channel
    tabs, or the closed chat (unless System is your tab), and they never light a dot.
    - **Moved**: other admins', command blocks' and the console's command echoes (`[Name: …]`; your own commands'
      replies stay where they are), and game and mod notices: *Respawn point set*, F3 shortcuts (`[Debug]: …`),
      *Saved screenshot as …*, shader toggles, login tips (Observable, Axiom, Simple Voice Chat), ModBeacon's update
      list (its restart countdown stays), *Activated Waystone*, your grave's location, claim refusals (*(Main Hand) You are not allowed to interact
      with this block…*), and CraftTweaker's `/reload` lines.
    - **Unchanged**: player chat, Discord, deaths and advancements, joins and leaves, `/say` and announcements, and
      Eden's own messages.
    - The server adds or keeps lines in `chat_system_only.json` ([Configuration](#configuration)). At most 200 are
      kept, so they never push your chat history out.
    - The 1.2 look shows them as before and keeps all of them (its S pip dot ignores them).
- **Click a channel tag** in chat (`[Local]`, `[Party]`…) to switch straight to that channel. In the new look it
  switches at once, with no "Chat channel" line: on a channel's tab the tab moves, on All or System the sign turns
  and the bar above the hotbar says so.
- **Channel pips** (the 1.2 look, *New chat look* off; [docs/PLAN-chat-activity.md](docs/PLAN-chat-activity.md))
  left of the chat input: one per channel you can use (Party and Guild while you're in one); the one you type into
  shows its name, the others a letter. **Click** one to switch — also to a channel switched off in Settings → Chat
  (its lines show while it's selected); **Ctrl+Tab** (Shift+Ctrl+Tab: back) cycles, skipping switched-off channels.
  The input starts right after the pips. **System** (the **S** pip after the channels): only system lines — the
  server's messages, deaths and advancements (by their own settings); a channel pip or Ctrl+Tab goes back.
- The channel you write in is remembered. Without the Eden client: `/ch <global|local|party|guild>`.
- **What you missed**: a dot on a tab (or pip) means lines there you haven't seen yet (seen = shown in the open
  chat); **@N** counts mentions of you. A muted channel's tab (or one set to Nothing) is struck through. A line that
  mentions you gets a stripe and a tint in your notification color (Settings → Notifications), "You were mentioned"
  on hover, and stays on screen 30 s instead of 10. **↓ Mentions** jumps to the oldest one you haven't seen
  (switching channel and tab when needed; one no tab can show any more, e.g. from a party you left, is skipped);
  clicking a tab or pip with @N does the same for that channel.
- **Per channel** (right-click its tab or pip, Discord style): **Mute** for 5 or 15 minutes, 1 or 24 hours, or until
  you turn it back on (no dot, no "all messages" sound; mentions still count); **Notifications**: All messages (a soft
  sound for every line), Only @mentions (default: the ping and @N), Nothing; **Mark as read**; **Chat settings** opens
  the hub on Settings → Chat, which has the same per channel. Kept on the server with the other hub settings.
- **One message elsewhere**: start it with `!g`, `!l`, `!p` or `!gd` (global, local, party,
  guild) — e.g. `!p on my way`. The sign left of the input (the 1.2 look: that channel's pip) shows it while you type;
  sent from another channel's tab, the bar above the hotbar says where it went.
- `/p`, `/g`, `/l <message>` and the hub's party/guild chat panels post to the same channels.
  Party and guild messages typed in normal chat also land in the hub panels' history. They follow typed chat's
  rules: no `§` or control characters, at most 256 characters, nothing while muted in BanHammer (its database is
  read off the server thread: at join, when BanHammer mutes someone, and every few seconds while a muted player
  keeps trying, so an unmute takes effect within about 20 seconds), and a rate limit — `chat.rate_limit_messages`
  (6) in a row, as many again every `chat.rate_limit_seconds` (5); 0 turns it off. Guild names and MOTDs, party
  names, bank tab names and Wardrobe gift notes refuse `§` and control characters too; a muted player can't set a
  MOTD or add a gift note.
- Local chat tells you when no one was close enough to hear. Spectators nearby still hear it but don't count, so
  the notice never gives away that someone is spectating you.
- Only Global reaches the **Minestar Discord bridge** (it listens to Fabric's chat event, which
  Eden fires for Global only).
- Typed chat stays signed player chat (reportable, "secure chat" compatible).

**Format**: `[channel] [GUILD TAG] prefix Name suffix » message` (no guild tag in guild chat)
- **Name**: LuckPerms prefix and suffix around the name, drawn in its group's color. Prefixes take
  `&` codes, `&#rrggbb` and TAB-style tags — `<#ff8800>[A]</#ff8800>`, gradients
  `<#ffffff>…</#ffff00>`, `<red>`, `<b>` (see [docs/LUCKPERMS.md](docs/LUCKPERMS.md)). The same
  styled name appears wherever the server names a player — chat, deaths, join/leave,
  advancements, `/me`, `/say`. A scoreboard team color (Duels_LD matches) wins over it.
- **Per-group looks** come from LuckPerms meta (inherited by weight like prefixes); colors as
  `#69118f`, `&6` or a name like `gold`:
  - `eden_ld.name_color` — name color (default `chat.default_name_color`, `#00c8c9`);
  - `eden_ld.chat_color` — message text color;
  - `eden_ld.silent_join` — `true` hides the player's join and leave messages.

  The Styled Chat setup this replaces, as commands:
  ```
  /lp group admin meta set eden_ld.name_color #931001
  /lp group admin meta set eden_ld.chat_color gold
  /lp group admin meta set eden_ld.silent_join true
  /lp group moderator meta set eden_ld.name_color #1a39fe
  /lp group helper meta set eden_ld.name_color #00e014
  /lp group prime meta set eden_ld.name_color #69118f
  ```
- **Links** (`http(s)://…`) are clickable; **@name** of an online player is highlighted and
  pings them (`chat.mention_sound` for the server; each player sets its volume in Settings → Chat).
  **Tab** right after `@` completes a player's name (`@Le` → `@LeDok`).
- Leave messages are red (`chat.red_leave_messages`), join messages yellow.
- **Formatting** (as Styled Chat allowed, for everyone):
  - **Markdown** as on Discord: `**bold**`, `*italic*` or `_italic_`, `__underline__`,
    `~~strikethrough~~`, `||spoiler||`, `` `code` `` (nothing formatted inside). `\*` keeps a
    character as typed. A lone `*` (`2*3`) and underscores inside words (`Kiber_Kabachok`) stay
    text; links, emojis and `@names` are never cut.
  - **Tags**: colors `<red>`, `<gold>`, `<orange>`, `<#ff8800>`, `<color:aqua>`; `<b>`, `<i>`,
    `<u>`, `<st>`, `<spoiler>`, `<reset>`. Close with `</red>` or `</>` (the last one); unclosed
    tags run to the end of the message.
  - **Spoilers** show as a gray bar; hover to read.
  - Discord gets the same markdown (tags become markdown, colors are dropped), and Discord's
    markdown and spoilers show in game. Config `chat.markdown` / `chat.color_tags` turn each off.
- **Shortcuts**: `:item:` shows the item in your main hand as `[Name]` in its rarity color —
  hover it to see the item (enchantments and all), with `×count` for a stack; `:pos:` shows
  your block position (`12 64 -300`) — hover for the dimension, click to copy. Discord gets them
  as text. With an empty hand `:item:` stays as typed.
- The hub's **party and guild chat** show lines exactly as chat does: hover items, spoilers and
  emojis; click links, [GIF]s and [Sticker]s (the viewer opens over the hub) and `:pos:`;
  right-click an emoji to star it.

**Discord emojis** — the Discord server's custom emojis work in game chat, both ways:
- The list is `config/eden_ld/emoji.json`: each emoji's name and its Discord **emoji id**
  (right-click → Copy Emoji ID), or its **image link** (Copy Emoji Image Link — use that for
  animated ones: `animated=true` in the link marks them). Empty entries are skipped. Keep the
  order and add new emojis at the end. `/emojis reload` (`eden_ld.admin.emojis`; OPs by default) applies changes.
- **Discord sync (optional, recommended)**: so emojis added or deleted on Discord show up by
  themselves, give Eden a bot of your own in your Discord server:
  1. [Discord Developer Portal](https://discord.com/developers/applications) → New Application →
     Bot → Reset Token → copy it.
  2. Invite it: OAuth2 → URL Generator → scope `bot`, no permissions → open the link, pick your
     server.
  3. In `config/eden_ld/chat.json` set `discord_bot_token` (keep it secret) and `discord_guild_id`
     (right-click your server → Copy Server ID; needs Developer Mode).

  On start, on `/emojis reload`, and every `chat.emoji_sync_minutes` (60), the list follows the
  server: new emojis are added under their Discord name, deleted ones removed, animated ones
  marked — and the in-game names you set in `emoji.json` are kept (matched by id). Players only
  get the list again when something changed.
- **Animated emojis play in game**: the server turns the GIF into a strip of composed frames
  (up to 64, with their delays); the client draws each new frame over the emoji's spot in the
  font texture, so it animates everywhere it's shown at no extra cost — and pauses while it isn't
  on screen (not drawn for a second: scrolled away, chat faded), resuming in step when seen again.
- The server downloads each image once from Discord's CDN (`config/eden_ld/emoji/<id>.png`,
  32×32); the Eden client builds an emoji font from them — no resource pack. Clients **cache
  images by emoji id** (`<game dir>/eden_ld/emoji_cache/`): on join the server sends only the
  list (~3 KB), and a client downloads just the images it doesn't have — after the first join,
  nothing until emojis are added.
- **In game**: type `:KEKW:` — it shows as the image (hover for its name), in chat and in the
  hub's party/guild chat panels. In the chat input a completed `:KEKW:` turns into the emoji as
  you type (one Backspace removes it): it becomes the emoji's own character, which Eden clients
  draw as the image in any font — so it also works in `/msg`, chat history, and anywhere text shows.
- **To Discord**: Global messages reach the bridge with `:KEKW:` rewritten as `<:KEKW:id>`, so
  Discord shows the real emoji. **From Discord**: `<:KEKW:id>` (and `:KEKW:`) in bridged
  messages turn into the image.
- **Emoji groups** (optional) — extra picker tabs of the server's emojis, in `emoji.json`:
  ```json
  "groups": {
    "cats": { "name": "Cats", "icon": "catwhite", "permission": "eden_ld.emoji.cats",
              "emojis": ["catallblack", "catcalico", "catwhite"] }
  }
  ```
  `icon` is an emoji name; grouped emojis leave the Minestar tab. `permission` is optional: without
  it (LuckPerms node; OPs have it unless LuckPerms says otherwise) the player doesn't see the tab
  and typing those emojis stays plain text — though they still see others use them. Standard
  categories never need a permission. `/emojis reload` applies changes; permission edits apply live.
- **Guest emojis**: a custom emoji in a Discord message that isn't in `emoji.json` — another
  server's (Nitro, or a FakeNitro-style image link), or one not listed yet — is fetched on the spot
  and shown as the image (animated too); the line reaches the game a moment later. Guests keep
  their character across restarts (`config/eden_ld/emoji/guests.json`, up to 500; the one seen
  longest ago makes room, favorites never do). They can't be typed by name. Sent back to Discord,
  a guest goes as its image link (the bridge's bot can't show other servers' emojis). **Pasted in
  game** too: an emoji image link (`[name](https://cdn.discordapp.com/emojis/…)` or the bare link)
  in chat, `/p` `/g` `/l` or the hub panels becomes a guest the same way — the message goes out a
  moment later, once the image is fetched (that player's following messages wait their turn, so
  chat stays in order). Right-click it to favorite it.
- **Links in Discord messages** are clickable too (Minestar turns `&x` into colors across the
  whole line — inside links Eden puts the `&` back); **GIF links** (Tenor, Giphy, Klipy, `.gif` — what
  Discord's GIF picker posts) show as a short clickable **[GIF]**, in game chat as well. Clicking
  it opens a small **GIF viewer** over the chat (bottom right): the animated GIF, **↗** to open
  it in the browser, **✕** / Esc / a click elsewhere to close. The player's own game fetches it,
  only on that click (a Tenor/Giphy page is resolved to its `.gif`; up to 80 frames, shrunk to
  320 px; freed on close). Sites that refuse it — Klipy blocks automated requests — show a note
  and keep the browser button.
- **Stickers** (FakeNitro style — `[monke](https://media.discordapp.net/stickers/…)` or the bare
  link, from Discord or pasted in game) show as a clickable **[Sticker: monke]** that opens in the
  same viewer (PNG and GIF stickers; an animated PNG shows its first frame; Lottie stickers can't
  be shown and keep the browser button). Real Discord stickers aren't part of the bridged text,
  so they can't reach the game.
  - In the hub's **party and guild chat** a message's stickers also show as images under it
    (64 px, animated ones play; up to 3 a message; click to enlarge). GIFs stay a `[GIF]` to click.
  - **Saved stickers**: right-click a `[Sticker]` in chat (or a sticker image in the hub) to save
    it; the picker's **Stickers** tab (after ⭐ Favorites) shows them as images — click puts one
    in the chat field as a single character drawn as the sticker (like an emoji), sent as its
    FakeNitro link so Discord shows the image too (links that wouldn't fit the 256-character
    message limit are left out); right-click removes it. Stickers
    have their own limit: config `chat.sticker_favorites` (5), per group LuckPerms meta
    `eden_ld.sticker_favorites` (e.g. `/lp group prime meta set eden_ld.sticker_favorites 20`).
    Needs the emoji picker permission, like favorites.
- Guest emojis sent to Discord are kept apart with spaces: Discord shows each such link's text
  with its image under the message, and glued together they broke. Stickers
  and uploaded images aren't part of the bridged text (Minestar sends author + text only).
- **Favorites**: right-click an emoji in the picker — or in any chat message, guests included —
  to star it; favorites sit at the top of the picker and are saved on the server per player
  (kept across rejoins and restarts, on any computer). Limit: config `chat.emoji_favorites` (5),
  per group via LuckPerms meta, e.g. `/lp group prime meta set eden_ld.emoji_favorites 20`.
  Favorites come with the picker, so they need `eden_ld.emoji_picker` too.
- **Extra slots** (e.g. a shop item) on top of that base limit, for favorites and saved stickers
  separately — limit = base + `/emojis bonus` grants (LuckPerms can't stack two identical timed
  grants, so bonuses are Eden's own):
  - **`/emojis bonus add <players> <emojis|stickers> <amount> [duration]`** (OP or console, so a
    shop can run it; offline players who joined before work too). Duration `30d`, `12h`, `1d12h`,
    `2w`… or `permanent` (the default — until a wipe: grants live in the world's data). Grants
    **stack**: buying "+20 for 30 days" twice gives +40, each ending on its own. A negative amount
    takes slots away; `/emojis bonus clear <players> [emojis|stickers]` removes grants,
    `/emojis bonus list <players>` shows them with the time left. Online players get a message
    with their new limit.
  - When a bonus ends, favorites over the new limit stay; new ones can't be added until below it.
- **Standard emojis** (Discord's own: 😂 👍 ❤️ 🇺🇦 …, 3,944 with skin tones): typed as
  `:joy:`, `:thumbsup:`, `:flag_ua:` (Discord's names), sent from a phone or the system emoji
  keyboard, or arriving from Discord — all show as Discord's images (Twemoji) in game, and go to
  Discord as the real emoji. Minecraft draws one character at a time, so each emoji (even a
  flag or a family, several characters long) becomes one character of its own; symbols like
  ™ © ❤ become images too. List and images ship in the mod (`eden_ld_emoji/`: Twemoji at 32 px
  in 16 sheets, 3.6 MB — nothing is downloaded); a sheet is only opened when one of its emojis is
  first shown. `tools/emoji/build_emoji.py` rebuilds them when Discord adds emojis.
- **Emoji button**: players with the permission `eden_ld.emoji_picker` (e.g.
  `/lp group prime permission set eden_ld.emoji_picker true`) get a button at the right end of
  the chat input: tabs ⭐ Favorites, **Minestar** (the server's emojis, `:minestar:` icon), your
  emoji groups, then Discord's categories (People, Nature, Food, Activities, Travel, Objects,
  Symbols, Flags) — the tab row scrolls with the mouse wheel. A **search** field under the tabs
  finds any emoji by name (Enter inserts the first match). Click inserts the emoji (Shift+click: keep the grid open;
  scroll for more). Everyone else can still type `:name:` by hand. Operators get the button
  unless LuckPerms sets the node to false for them.
- The hub's **party and guild chat** have the same picker in the hub's look: a button between the
  chat field and Send opens it beside the chat (party: in place of the invites column; guild:
  right of the chat lines, only while the right panel is collapsed — opening the panel closes
  it). Same tabs, search and right-click favorites.

### Tab list

Eden also replaces **Styled Player List** (remove it — the log warns while it's installed):
- **Names** as in chat: `[GUILD TAG] prefix Name suffix` — the guild tag only for players who
  turned on Settings → Show guild tag (`tab_list.guild_tags` turns it off for everyone), the
  name in its group color (`eden_ld.name_color`).
- **Sorted by rank**, highest first, then by name (spectators stay at the bottom). The rank is
  the weight of the player's highest-weight LuckPerms group, or the meta `eden_ld.tab_weight`
  when set:
  ```
  /lp group admin setweight 100
  /lp group moderator setweight 80
  /lp group helper setweight 60
  /lp group prime setweight 20
  ```
  (`tab_list.sort_by_rank: false` keeps vanilla's by-name order.) Sorting happens on the client —
  1.21.1's player list has no order of its own — so it needs Eden on the client, as the rest
  of the hub does.
- **Header and footer**: `tab_list.header` / `tab_list.footer`, one entry per line, legacy
  colors (`&6`, `&l`, `&#00c8c9`) and `{online}`, `{max}`, `{player}`, `{ping}` (ms), `{tps}`
  (colored green/yellow/red), `{mspt}`. With Placeholder API installed its `%…%` placeholders work
  too (e.g. `%player:playtime%`).
- Everything refreshes every `tab_list.update_ticks` (20 = 1 s) and only changes are sent, so a
  new prefix, color, rank or guild shows up by itself within a second.

## Minestar integration

Active only when the Minestar platform mod is installed (server side); Eden_LD runs fine without it.

- **Shop delivery** — on join, players with pending purchases get a clickable message;
  `/shop-receive` delivers them by running each product's commands and confirms delivery back to the
  platform. Only successfully delivered products are confirmed.
- **Prime role sync** — on join, the player's platform prime status is compared with their LuckPerms
  group (`prime` by default) and the group is added or removed to match. Dedicated servers only.
- **Profile cache** — one profile fetch per join caches the player's platform nickname, group, prime
  status, and friends list for other Eden_LD features and addon mods.
- **Friends** — the hub's Friends tab reads and changes platform friendships (see
  [Friends](#friends)).

All platform calls are asynchronous; nothing blocks the server thread.

## Configuration

**World data** (per world, saved by the server) lives in **`<world>/data/eden_ld/`**:
`hub.dat` (per-player hub data: settings and themes, daily quests, activity stats), `guilds.dat` (guilds,
their banks, XP and quest boards), and `claim_times.dat` (OPAC claim times, only with Open Parties and Claims).
Back up or move the folder as a whole, with the server stopped.

Everything Eden_LD reads lives in **`config/eden_ld/`**, **one file per section**:
`minestar.json`, `liquid_mana.json`, `flight.json`, `hub.json`, `combat.json`, `dailies.json`,
`guilds.json`, `activities.json`, `chat.json`, `chat_system_only.json`, `tab_list.json`, `profile.json`,
`commands.json` — plus `emoji.json`, `dimensions.json` (each dimension's own gamerules, PvP mode, difficulty and border, see
[World rules](#world-rules)) and the `daily_quests/`, `guild_quests/` and `lang/` folders.
Missing files and settings are created with their defaults on launch (new settings appear by
themselves after an update); a file that can't be read keeps its defaults and says so in the log.
The old single `eden_ld.json` is no longer read (the log warns while it's there: copy what you
changed into the new files, then delete it). Below, each top-level key is the file of that name:

```json
{
  "minestar": {
    "prime_role_sync_enabled": true,
    "prime_group": "prime",
    "shop_notify_on_join": true
  },
  "liquid_mana": {
    "heal_percent": 5.0,
    "heal_interval_ticks": 20
  },
  "flight": {
    "flight_disabled_dimensions": [],
    "boost_disabled_dimensions": ["minecraft:overworld", "minecraft:the_nether"],
    "boost_armor_threshold_enabled": true,
    "boost_armor_threshold": 20,
    "safe_landing_on_deny": true
  },
  "hub": {
    "party_max_size": 5,
    "party_offline_grace_seconds": 180,
    "party_xp_radius": 50.0,
    "party_xp_bonus_percent": 25,
    "ftb_teams_parties": false,
    "waystone_cooldown_seconds": 30,
    "guild_create_cost": 0,
    "guild_member_limit": 5,
    "guild_tab_costs": [1000, 5000, 20000, 80000, 250000, 1000000],
    "guild_member_upgrade_costs": [1000, 5000, 10000, 20000, 40000],
    "guild_member_upgrade_step": 1,
    "guild_claim_upgrade_costs": [5000, 20000, 60000, 150000],
    "guild_claim_upgrade_step": 25,
    "guild_release_newest_first": true
  },
  "dailies": {
    "quest_count": 4,
    "reset_hour": 4,
    "time_zone": "",
    "free_rerolls": 1,
    "reroll_costs": [50, 100, 200, 400],
    "bonus": { "items": ["economy_ld:silver_coin 5"], "xp": 0, "lc": 0, "commands": [] }
  },
  "activities": {
    "level_category": "",
    "unlock_check_seconds": 30
  },
  "guilds": {
    "daily_quests": 5,
    "weekly_quests": 2,
    "weekly_reset_day": "MONDAY",
    "level_xp": [800, 1200, 1700, 2200, 2700, 3200, 3700, 4100, 4600],
    "daily_quest_xp": 5,
    "tab_cap": 5,
    "member_upgrade_cap": -1,
    "claim_upgrade_cap": -1,
    "level_perks": [
      { "perk": "emoji_slots", "from": 2, "every": 0, "amount": 5, "until": 0 },
      { "perk": "claim_upgrade_discount", "from": 4, "every": 0, "amount": 25, "until": 0 },
      { "perk": "daily_quests", "from": 5, "every": 0, "amount": 1, "until": 0 },
      { "perk": "extra_tabs", "from": 6, "every": 0, "amount": 1, "until": 0 },
      { "perk": "weekly_quests", "from": 7, "every": 0, "amount": 1, "until": 0 },
      { "perk": "tab_discount", "from": 8, "every": 0, "amount": 25, "until": 0 },
      { "perk": "daily_quests", "from": 9, "every": 0, "amount": 1, "until": 0 },
      { "perk": "daily_rerolls", "from": 10, "every": 0, "amount": 5, "until": 0 }
    ]
  },
  "combat": {
    "pvp_tag_seconds": 30,
    "pvp_tag_blocks_flight": true
  },
  "chat": {
    "local_radius": 100.0,
    "default_name_color": "#00c8c9",
    "separator": " &8» ",
    "join_history": 20,
    "join_history_minutes": 120,
    "markdown": true,
    "color_tags": true,
    "link_color": "#7878ff",
    "mention_color": "#7878ff",
    "mention_sound": true,
    "red_leave_messages": true,
    "discord_bot_token": "",
    "discord_guild_id": "",
    "emoji_sync_minutes": 60,
    "emoji_favorites": 5,
    "sticker_favorites": 5,
    "rate_limit_messages": 6,
    "rate_limit_seconds": 5
  },
  "chat_system_only": {
    "keys": [],
    "text": [],
    "keep": []
  },
  "tab_list": {
    "enabled": true,
    "header": ["", "&#00c8c9&lFallen&#f5c542&lEden", "&8&m            &r &8[ &f{online}&7/&f{max} &8] &8&m            ", ""],
    "footer": ["", "&7TPS: {tps} &8| &7Ping: &e{ping}", ""],
    "guild_tags": true,
    "sort_by_rank": true,
    "update_ticks": 20
  },
  "profile": {
    "refresh_minutes": 10,
    "level_perks": [
      { "perk": "emoji_slots", "from": 1, "every": 2, "amount": 1, "until": 0 },
      { "perk": "sticker_slots", "from": 2, "every": 2, "amount": 1, "until": 0 },
      { "perk": "claims", "from": 5, "every": 10, "amount": 5, "until": 0 },
      { "perk": "daily_rerolls", "from": 10, "every": 10, "amount": 1, "until": 0 }
    ]
  },
  "commands": {
    "op_only": ["structure_upgrade", "dungeon_difficulty_config_reload"],
    "busy_blocked": ["pay", "withdraw", "kit", "kits"]
  },
  "tutorial": {
    "enabled": true,
    "dimension": "yggdrasil:tutorial_dim",
    "spawn": "",
    "finish_dimension": "yggdrasil:spawn_dim",
    "finish_spot": "",
    "commands_on_start": ["realmkeys player {player} lockall", "realmkeys player {player} unlock yggdrasil:tutorial_dim"],
    "commands_on_finish": ["realmkeys player {player} unlock yggdrasil:spawn_dim", "…"],
    "always_visible": ["npcs_ld:npc"],
    "tab_tours": {"home": "config:hub/home", "mail": "config:hub/mail", "profile": "config:hub/profile",
                  "wardrobe": "config:hub/wardrobe", "friends": "config:hub/friends", "party": "config:hub/party",
                  "guild": "config:hub/guild", "guild/members": "config:hub/guild_members",
                  "guild/recruiting": "config:hub/guild_recruiting", "guild/quests": "config:hub/guild_quests",
                  "guild/level": "config:hub/guild_level", "guild/bank": "config:hub/guild_bank",
                  "waystones": "config:hub/waystones", "activities": "config:hub/activities",
                  "activities/duels": "config:hub/duels", "dailies": "config:hub/dailies", "kits": "config:hub/kits",
                  "settings": "config:hub/settings"},
    "private_blocks": ["potions_ld:alchemy_table", "minecraft:chest", "minecraft:trapped_chest", "minecraft:barrel",
                       "minecraft:furnace", "minecraft:smoker", "minecraft:blast_furnace", "minecraft:brewing_stand"],
    "locked_blocks": ["#minecraft:all_signs", "minecraft:chiseled_bookshelf", "#minecraft:trapdoors"]
  }
}
```

**`commands.op_only`** — other mods' commands only ops (level 2) may use, for mods that leave an
admin command open to every player. Root names without the slash; an alias is a command of its
own (`tp` next to `teleport`). The console and command blocks keep them. Applied at start and on
`/reload`, which re-reads the file. `/eden commands dump` shows what each command needs (a listed
one shows as level 2).

**`commands.busy_blocked`** — other mods' commands a busy player (a duel, a dungeon run, the tutorial) can't run:
paying, taking coins out, kits. Every subcommand is covered; the command stays visible and says why. Applied at
start and on `/reload`.

**`chat_system_only`** (1.3.2) — lines that show only in the chat's System tab (the new look), on top of Eden's own
list (see [Chat](#chat) → *System-only lines*). Re-read on `/reload` and applied to everyone online at once, lines
already in their chat included. An entry no rule allows is left out, and the log says why; each list keeps at most
256 entries of up to 256 characters.
- **`keys`** — translation keys: the line's own, or a part's inside an empty wrapper (as F3's `[Debug]:` prefix).
  A trailing `*` makes a prefix (at least 3 characters before it): `"chat.waystones.*"`. Find a key with Inspector's
  `translate`, or in the mod's `en_us.json`.
- **`text`** — how a line's text starts (leading spaces and `§` codes skipped; case counts), at least 3 characters:
  `"[DataRewriter]"`; `#` stands for a number (`"[ModBeacon] # update"` matches *[ModBeacon] 8 updates available:*). Only for mods that send plain text — a translated line's text depends on the reader's language.
- **`keep`** — keys or text starts that stay where they are, over every rule: it switches off one of Eden's
  (`"gui.xaero_claims_protection_*"` brings the claim refusals back), and keeps one kind of admin echo by the echoed
  command's key (`"commands.ban.success"`).

**`tutorial`** — see [Tutorial](#tutorial). Spots are `"x y z"` or `"x y z yaw pitch"`; `{player}` in the commands
is the player's name.

## Commands

| Command | Who | What |
| --- | --- | --- |
| `/hub` | any player | Opens the Eden Hub screen (same as the F4 key). |
| `/hub theme unlock <players> <theme>` | `eden_ld.admin.themes` | Unlocks a hub theme (e.g. `eden_ld:stone_tablet`) for players. |
| `/hub theme lock <players> <theme>` | `eden_ld.admin.themes` | Removes an unlocked theme (free themes stay). |
| `/hub profile` | any player | Opens the hub on your profile. |
| `/hub mail` | any player | Opens the hub on the Mail tab (the website-purchases join message links here). |
| `/kits` | any player | With Kits_LD: opens the hub on the Kits tab (vanilla clients: Kits_LD's chat list). |
| `/hub background unlock <players> <background>` | `eden_ld.admin.backgrounds` | Unlocks a profile background (e.g. `eden_ld:aurora`) for players. |
| `/hub background lock <players> <background>` | `eden_ld.admin.backgrounds` | Removes an unlocked background (free ones stay). |
| `/invite <player>` | any player | Invites to your party — or, when solo, starts one (created when they accept). |
| `/ignore [player]` | any player | Ignores a player (online, or anyone who has played here) — see [Ignoring players](#ignoring-players); without a name, lists who you ignore. |
| `/unignore <player>` | any player | Stops ignoring them (suggests the names on your list). |
| `/p <message>` | any player | Sends a message to your party's chat. |
| `/party-admin list` | `eden_ld.admin.parties` | Lists every party: tag, display name, member count. |
| `/party-admin disband <tag>` | `eden_ld.admin.parties` | Force-disbands a party by tag — works with every member offline. |
| `/activities complete <players> <id> [difficulty] [seconds]` | `eden_ld.admin.dungeons` | Records a won run (stats, the `complete_dungeon` daily objective). |
| `/activities reset <players> [id]` | `eden_ld.admin.dungeons` | Forgets activity stats (one activity, or all). |
| `/activities level <player>` | `eden_ld.admin.dungeons` | Shows the player's activity level (checks `activities.level_category`). |
| `/dailies give-rerolls <players> <amount>` | `eden_ld.admin.dailies` | Extra free daily-quest rerolls for today. |
| `/dailies reset <players>` | `eden_ld.admin.dailies` | Draws a new random set of today's daily quests (progress, claims and rerolls start over). |
| `/g <message>` | any player | Sends a message to your guild's chat. |
| `/l <message>` | any player | Sends a message to players nearby (Local chat). |
| `/emojis reload` | `eden_ld.admin.emojis` | Re-reads `config/eden_ld/emoji.json`, fetches new emoji images, resends them. |
| `/emojis bonus add <players> <emojis\|stickers> <amount> [duration]` | `eden_ld.admin.emojis`, console | Extra favorite / saved-sticker slots, permanent or for a time (`30d`, `12h`); grants stack. |
| `/emojis bonus clear <players> [emojis\|stickers]` | `eden_ld.admin.emojis`, console | Removes bonus grants. |
| `/emojis bonus list <players>` | `eden_ld.admin.emojis`, console | Lists active grants and time left. |
| `/ch <global\|local\|party\|guild>` | any player | Switches your chat channel (a click on its tab or pip in chat does the same). |
| `/guild-admin list` | `eden_ld.admin.guilds` | Lists every guild: tag, name, size, leader. |
| `/guild-admin disband <tag>` | `eden_ld.admin.guilds` | Disbands a guild by tag (works with every member offline). |
| `/guild-admin xp <tag> add\|set <amount>` | `eden_ld.admin.guilds` | Adds or sets a guild's XP (levels follow; bonuses and messages as for a real level-up). |
| `/guild-admin quests <tag> reset` | `eden_ld.admin.guilds` | Clears the guild's quest boards and draws them again (progress lost). |
| `/shop-receive` | any player | Delivers pending Minestar shop purchases (registered only when Minestar is installed). |
| `/emireload` | any player (client command, with EMI) | Reloads EMI from what the client already has — for an EMI stuck on "Reloading..." without an op's `/reload`. |
| `/eden gamerule <dimension> <rule> <value>\|reset` | `eden_ld.admin.world` | Sets a dimension's own value of a gamerule, or (`reset`, or the server-wide value) makes it follow the server-wide one again — see [World rules](#world-rules). |
| `/pvp [on\|off]` | any player | Shows or switches your own PvP (1 minute cooldown, not while combat-tagged) — see [PvP toggle](#pvp-toggle). |
| `/eden pvp <dimension> [off\|toggle\|on\|reset]` | `eden_ld.admin.world` | Shows or sets a dimension's PvP mode (`reset` follows `server.properties`: `pvp=true` → toggle, `false` → off) — see [World rules](#world-rules). |
| `/eden pvp player <players> [on\|off]` | `eden_ld.admin.world` | Shows or sets players' own PvP switch, skipping their cooldown and combat check. |
| `/eden difficulty <dimension> [peaceful\|easy\|normal\|hard\|reset]` | `eden_ld.admin.world` | Shows or sets a dimension's difficulty (`reset` follows the server's). |
| `/eden tutorial start <players>` | `eden_ld.admin.tutorial` | Puts players into the tutorial (again) — see [Tutorial](#tutorial). |
| `/eden tutorial finish <players>` | `eden_ld.admin.tutorial`, console | Ends their tutorial: everything back, the finish spot. The guide's cleanup runs it last. |
| `/eden tutorial status <player>` | `eden_ld.admin.tutorial` | Not started, in the tutorial, or finished. |
| `/eden gamerule <dimension> [<rule>]` | `eden_ld.admin.world` | Shows a rule's value in that dimension and where it comes from, or lists everything the dimension changes. |
| `/eden commands dump` | `eden_ld.admin.commands` | Writes every command on the server, by mod, with the op level each needs (and subcommands needing a different one) to `eden_ld-commands.md` in the server folder — for deciding what players get. |

**Admin permissions.** A command with a node in "Who" is for players with that LuckPerms node;
while it's unset (or without LuckPerms) OPs (level 2) have it, and the console always does. Grant
a staff group one (`/lp group moderator permission set eden_ld.admin.parties true`) or all
(`eden_ld.admin.*`); set it `false` to take it from an OP. Changes apply live — the player's
command list is resent. [docs/LUCKPERMS.md](docs/LUCKPERMS.md) lists every LuckPerms setting.

## Addon API

Other mods can register flight rules and read cached Minestar profiles — see [API.md](API.md).

## Compatibility

- **Icarus** — wings are gated by the flight rules automatically (they fly through Fabric's elytra
  events); no extra configuration needed. Icarus's own `no_flying_allowed` dimension tag and
  Flightless effect keep working independently.
- **Minestar / LuckPerms** — optional; features degrade gracefully when absent.
- **Open Parties and Claims** — optional; guilds become an OPAC party system (`eden_ld_guilds`)
  for guild-owned claims (see Guilds → Guild claims). Claims stay protected from mob griefing in a
  dimension that changes mobGriefing (see [World rules](#world-rules)).
- **Arenas_LD** (4.6.0+) — optional; its dungeons, raids and mob arenas fill the Activities tab,
  and Eden's level check is its entry gate (see Eden Hub → Activities).
- **Duels_LD** (1.4.0+) — optional; the Duels tab (see Eden Hub → Duels). Duels uses Eden's parties
  for 2v2.
- **Kits_LD** (0.2.0+) — optional; the Kits tab and `/kits` (see Eden Hub → Kits).
- **Realm Keys** (1.0.0) — server-only in the pack, so players' games don't have its texts: Eden's lang files carry
  its messages (`command.realm_keys.*`, `message.realm_keys.*`, `dimension.realm_keys.*`, English and Ukrainian, from
  its own files), so its "Path Opened!" title when the tutorial unlocks a dimension, and its command replies, show as
  text instead of keys. Refresh them if a new Realm Keys version adds messages.
- Any mod whose flight uses Fabric's `EntityElytraEvents` is covered by flight control.
- **ChatPatches** — not needed (Eden's chat has its duplicate counter, message copy and more) and no longer in the
  pack. Where it's installed anyway, a right-click on an emoji or sticker still stars / saves it (its own menu may
  draw under the chat lines: Eden no longer moves it).
- **Chat Heads** — Eden's chat window draws heads itself, so Chat Heads' heads don't show on Eden servers (it can
  leave the pack; elsewhere it works as usual).
- **Mods and resource packs with their own font glyphs** (Economy_LD's coin, icon fonts) — emojis
  use characters from Unicode's plane-15 private use area and never replace a glyph a font
  defines itself.
- **EMI** (1.1.24) — rejoin fix: EMI could stay on "Reloading..." after joining the server again
  without restarting the game (a server restart, a lost connection), until an op ran `/reload`. EMI
  reloads once both halves of the server's data — tags and recipes — have come and remembers which
  half it has seen; its reset of that memory sits on a disconnect path that a dropped connection, a
  server stop and Join never take, and the server sends a few lone recipe syncs after each join. The
  stale half then paired with the next join's tags before the world existed, the reload stopped
  ("World is null") and EMI waited for tags that had already come. Eden forgets the halves whenever
  the client leaves a world, redoes a reload that stopped without a world once the world and the
  recipes are there, and adds `/emireload`. Does nothing without EMI.
- **Saint's Dragons** (0.9.5) — crash fix: its dragon spawn rule counted the dragons near a spot
  by asking the live world, also while chunks generate on worker threads (C2ME), which corrupted
  the world's entity lists and crashed the server. Eden makes the rule skip that count while chunks
  generate (a new chunk has no dragons around it to count) and off the server thread; natural spawns
  keep the density limit. Does nothing without Saint's Dragons.
- **Stylish Effects** (21.1.3) **with Trinkets** (3.10) — the inventory's effect widgets start left of
  Trinkets' extra slot columns; Stylish Effects put them right next to the panel, where Trinkets
  then drew its columns over them. Effects on the right side, other screens and an inventory
  without extra columns are left alone. Does nothing without both mods. With **EMI**, its panels
  (favorites on the left) flow around the effect widgets instead of drawing over them — Stylish
  Effects tells only JEI and REI where they are.

## Credits

- Emoji images: [Twemoji](https://github.com/jdecked/twemoji) (the art Discord uses) —
  © Twitter / X Corp and contributors, [CC-BY 4.0](https://creativecommons.org/licenses/by/4.0/).
  Shipped resized to 32 px (`eden_ld_emoji/sheets/`).
- Emoji list and categories: [emojibase-data](https://github.com/milesj/emojibase) (MIT);
  names as on Discord (JoyPixels shortcodes).

## License

MIT. Built for the Fallen Eden server.
