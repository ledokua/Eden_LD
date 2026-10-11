# Plan: the tutorial in Eden

**Status (2026-10-11): built and checked in the dev client** (see Checked below); not deployed. Still needed from the
owner: the arrival spot in `tutorial_dim` and the finish spot in `spawn_dim`.

Owner's request (2026-10-11): players in the tutorial must not be able to break it — teleport to other waystones,
make a party, join or create a guild, and so on. Answers came from the NPCs_LD + VectorLib session (the guide dialog)
and MCP eden (the live server); the owner's decisions are marked **(owner)**.

## How it works today

- **The tutorial** is a closed room in `yggdrasil:tutorial_dim` with no exits **(owner)**. The NPCs_LD guide dialog
  (`config/npcs_ld/dialogs/tutorial/guide.json`) runs it in stages; its per-player flag `tutorial_stage` goes 1 → 9,
  9.2 (Hub), 9.3 (Chat), 9.5 (party, guild and claims) and 10 (finished or skipped). The inventory is cleared after
  stages and at the end (`tutorial_cleanup`: `clear`, trinkets, the 100 training coins back).
- **What the stages need**: only stage 8 teleports — between "Waystone 1" and "Waystone 2", through the Waystones
  mod's own menu. The Hub stage only looks at tabs, Mail and the "?" help; stage 9.5 is pictures and text (no party,
  guild or claim needed); the shop stage uses the tutorial's own admin shop (1 emerald for 1 LC).
- **The old `tutorial-mod-for-yggdrasil` 1.0.0** is a leftover **(owner)** that still runs: on first join it tags
  the player `tutorial_active`, saves and clears their inventory, phases them (they don't see other tutorial
  players) and sends them to `yggdrasil:tower_dim` 24.5 73 18.5. Its phasing is to move into Eden **(owner)**; the
  source is a decompile of the installed jar (`~/Projects/Mods/tutorial-mod-for-yggdrasil-installed-src`).
- **Busy** (BusyLib) already blocks, for a busy player: growing a party, accepting invites (Eden), the Hub's waystone
  teleports (Eden), claiming mail (Eden), starting dungeons, raids and arenas (Arenas_LD), the duel queue (Duels_LD)
  and auctions (Economy_LD).

## The design

### 1. Entering the tutorial

- **First join** (vanilla play time 0, and Eden has no tutorial record for them): Eden puts the player into the
  tutorial **(owner)** — teleport to the configured spot in `yggdrasil:tutorial_dim`, phasing on, the busy reason
  `eden_ld:tutorial`, `commands_on_start` run (Realm Keys locks, see 5).
- Their inventory and trinkets are saved (in the player's data, so a relog or restart keeps them) and given back at
  the end, as the old mod meant to, so first-join gifts from other mods survive the guide's `clear`.
- Eden keeps the state per player (`none` / `active` / `done`, in Eden's saved data). Busy is in memory only, so it is
  set again on every join while `active`. Dying respawns them in the tutorial; logging out keeps their place.
- **Existing players** are never put in: anyone with play time is `done` at rollout (no migration code — the rule is
  "play time above 0").

### 2. Locks while in the tutorial

All of them are one check — **busy** — so a dungeon run or a duel locks the same things. **(owner: busy state; all
four groups)**

| Group | Feature | Today | Change |
| --- | --- | --- | --- |
| Teleports out | Hub Waystones tab | busy blocks | — |
| | Waystones' own menu (stage 8), warp scrolls and stones | free | Tutorial players only see and reach waystones in the tutorial dimension (Eden already edits that menu; a teleport event check for the items) |
| | Activities lobbies (dungeons, raids, arenas) | Arenas_LD blocks busy | — |
| | Duel queue | Duels_LD blocks busy | — |
| Party & guild | Party invite, accept, join | busy blocks | — |
| | Guild create, join, accept an invite, invite others | free | busy check in the guild actions |
| Getting items | Mail claim | busy blocks | — |
| | Website purchases (Mail tab entry, `/shop-receive`) | free | busy check (see 4) |
| | Daily-quest rewards | free | busy check on Claim |
| | Guild bank withdraw | free | busy check |
| | Kits (`/kit`, Kits_LD) | free | the Kits_LD session adds the busy check |
| Spending coins | Wardrobe buy and gift | free | busy check |
| | Daily rerolls | free | busy check |
| | Sending mail with coins or items | free | busy check |
| | The tutorial's admin shop | — | stays open (the shop stage needs it) |

- The Hub itself stays open — the guide walks through its tabs. Locked buttons say why ("You can't do that during
  the tutorial"), like the busy messages today.
- **Claims**: nothing to do — Open Parties and Claims only allows claiming in the Overworld, the Nether and the End.
- Dropping or stashing the tutorial's small gifts (a hamburger, herbs, a potion) is left alone: the wand and runes are
  now taken back right after their stage, and the guide clears the rest.

### 3. Phasing

Ported from the old mod (reviewed 2026-10-11): everyone in the tutorial shares one room, but each player is in their
own **phase** — they don't see, hear or touch other tutorial players, and what they make there is theirs.

- **The phase** is the player's id, stored on the player and on every entity that comes from them (a saved NBT field,
  so it survives chunk unloads and restarts). New entities take the phase of whoever caused them: the player
  attacking, dropping, using an item or a block, a mob's owner, a vehicle — **and, unlike the old mod, a projectile's
  shooter** (its arrows, snowballs and spells were invisible to their own player).
- **What is separated**: seeing players and entities, damage, mob targets, pushing, sounds, particles and hopper
  pickups. Unphased things — the room itself, the NPCs, decorations — stay visible and **audible** to everyone (the
  old mod muted every sound from outside the player's phase, NPCs included). NPCs_LD's NPCs are listed as always
  visible.
- **Clean-up**: a player's phased mobs and items go when they log out (stage mobs come back on relog) and when they
  finish; anything left over is removed on its next tick.
- **Natural mob spawning** in `tutorial_dim` is turned off with Eden's own World settings (`doMobSpawning` per
  dimension) — no mixin.
- **Survival rules in the room**: ADVENTURE mode (no breaking or placing blocks); a death is cancelled and the player
  comes back at the tutorial spawn, as in the old mod.
- **Not ported**: the old stage machine, triggers, boss fight, boss bar, darkness transitions, FTB Quests checks and
  pins, whitelist and toggle — the guide does the stages now.
- **Private containers (owner: yes — the potions stage uses the alchemy table)**: each player gets their own copy of
  chests, furnaces, the alchemy table and other block menus in the room (the old mod's shadow block entities: memory
  only, ticking on their own, resent to their player). Placing and breaking blocks stays impossible (ADVENTURE).

Fixed on the way: the old mod kept the saved inventory only in memory (lost on a relog or restart) and pulled in
every player without its `tutorial_completed` tag, not only new ones.

### 4. Website purchases in the Mail tab (owner: option 1)

Already there: pending Minestar purchases show as one pinned Mail entry, "N website purchases", whose Claim runs the
delivery (`/shop-receive` does the same). They are read from Minestar each time, so they **never expire** and stay
pending on the website until claimed. Changes:

- **Busy check** on that Claim and on `/shop-receive` — purchases wait until the tutorial (or a dungeon run) ends.
- **One entry per purchase**: its name and quantity from the website, each with its own Claim, plus Claim all.
- The join message ("You have N unclaimed products") points to the Mail tab instead of running `/shop-receive`.

### 5. Finishing or skipping

- `/eden tutorial finish <player>` (staff and console; the guide runs it): busy and phasing off, the saved inventory
  back, `commands_on_finish` run, teleport to the finish point in `spawn_dim` **(owner; the spot comes later)**, state
  `done`. The guide's `tutorial_cleanup` calls it
  **last** (after its `clear`), for both finish and skip.
- Also `/eden tutorial start|reset|status <player>` for staff.
- **Realm Keys (owner)**: at the start, every dimension is locked but `tutorial_dim`; just before the end
  `spawn_dim`, `dungeon_dim`, `tower_dim`, `pvp_dim` and the Overworld unlock; the Nether, Eternal Starlight, the End
  and Mine Cells unlock later through events. Eden only runs the commands from the config lists. Today every
  dimension is unlocked for everyone (Realm Keys' global list), so per-player locks only work after that list shrinks;
  existing players are no concern while the server is in development **(owner)**.

### 6. Config (`config/eden_ld/tutorial.json`)

`enabled`, `dimension`, `spawn` (x, y, z, yaw, pitch), `finish` (dimension and spot), `save_inventory`,
`commands_on_start`, `commands_on_finish` (with `{player}`), `allowed_waystone_dimensions` (default: the tutorial
dimension).

## Rollout

1. Eden with the tutorial module; the old tutorial mod moves to the backups at the same time.
2. The guide (NPCs_LD session): `tutorial_cleanup` ends with `/eden tutorial finish {player}`.
3. Kits_LD session: the busy check on `/kit`.
4. Realm Keys: unlock what existing players already use, then shrink the global list (owner's call, with MCP eden).

## Owner's answers (2026-10-11)

- Finish point: `spawn_dim`; the owner sends the coordinates later (config).
- The guide starts by itself when a player arrives (the NPC's bubble) — Eden doesn't start the dialog.
- Realm Keys: no rollout plan needed while in development; the owner unlocks dimensions for himself.
- Private containers: yes — the alchemy table is used in the tutorial.

## Build order

1. **Busy locks** (section 2) and the **website purchases** in the Mail tab (section 4).
2. **The tutorial state**: config, first-join entry, inventory and trinkets saved, ADVENTURE, death back to the spawn,
   `/eden tutorial …`, the waystone menu limited to the tutorial dimension.
3. **Phasing** (section 3) without containers.
4. **Private containers**.
5. Dev test with two players in a copy of the tutorial room; README, todo.

## Checked (2026-10-11, dev server + two dev clients; the Nether stood in for `tutorial_dim`)

- A brand-new account was put in on its first join; accounts with play time weren't.
- `/eden tutorial start` on two players: teleported, Adventure, inventories put aside, each in their own phase.
- They didn't see each other in the room; after both finished they did again.
- A chest: one player's items stayed in their copy (the world's chest and the other player's copy empty); a
  furnace smelted in one player's copy, lit for them and unlit for the other.
- Damage between them refused; `/pay` refused with the tutorial message; `/kill` cancelled (back at the spawn,
  healed); a thrown snowball took the thrower's phase; Q kept the item.
- A relog mid-tutorial kept the phase, the place and the busy state.
- `/eden tutorial finish`: Overworld spawn, Survival, inventory back in its slots plus what they held, no phase,
  `/pay` working again.
- The Mail tab still renders; `/hub mail` opens it.

Not checked in dev: the Waystones menu limit and warp items (Waystones' menu needs its stones activated), website
purchases as Mail rows (needs Minestar), trinkets put aside, guild/bank/dailies/Wardrobe refusals (same busy check as
the tested ones), the alchemy table itself (the same mechanism as the furnace).

