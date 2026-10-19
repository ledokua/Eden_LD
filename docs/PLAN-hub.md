# Eden_LD Hub module — plan

One player-facing **Hub screen** in Eden_LD that gathers the server's social and progression
features in the Fallen Eden style, with a public API so the other LeDok mods plug into it instead
of shipping their own versions. Decisions locked with the owner on 2026-09-26.

## Decisions

| Question | Decision |
| --- | --- |
| Parties & guilds | **Our own systems** in Eden_LD (parties in phase 2, guilds last). FTB Teams gets replaced once guilds ship; land claims are *out of scope* for guilds. |
| Waystones | **Integrate Blay's Waystones** (the pack's mod, 21.1.x) — the Hub tab is a GUI mirror + teleport trigger over `WaystonesAPI`; no own waystone block. |
| Friends | **Full tab** — Minestar 1.3.0/1.4.0 (2026-09-26) shipped friend online status (`online()`, `lastSeenAt()`, `playing()` packs), `addUserFriend`/`removeUserFriend`, and incoming/outgoing request listing. No API blockers left. |
| Daily quests | **Standalone engine in Eden_LD** with a generic progress-report API other mods feed. NPCs_LD's journal stays for story quests; no coupling. |

## Architecture

- **Client**: one `HubScreen` — VectorLib parchment theme (same nine-slice textures as
  NPCs_LD/Economy_LD/Arenas_LD, copied into `assets/eden_ld/textures/gui/themes/parchment/`).
  Bookmark-style tab bar on top of the panel: **Home · Profile · Friends · Party · Guild ·
  Waystones · Dungeons · Dailies**, plus a **Settings** bookmark seated at the opposite (right) end.
  Opened by keybind (default `F4`, was `H` until 2026-09-28) and `/hub`. **Tab memory**: reopening the hub returns to the
  last-opened tab within the play session; after (re)joining the server it opens on Home
  (owner, 2026-09-27). Tabs for absent optional mods (Waystones, Minestar) hide themselves. Lists use VectorLib's existing `VirtualList`/`ScrollPanel` —
  no new VectorLib widgets needed.

### Design decisions (owner, 2026-09-27)
- Bookmarks get **icons** later and then become **fixed-width** — lay the tab bar out so
  swapping text labels for icons doesn't move anything.
- **Friends tab**: three zones — online players on server (with an **always-visible** search
  field), friends list, requests. The requests zone is a **collapsible side panel** toggled
  by a slim vertical bar on the right edge: collapsed by default when there are no pending
  requests (friends list takes the width), auto-expanded/badged when incoming requests exist.
  Friends column is the widest; online-player rows explain a missing add button
  ("friend"/"pending"); party-invite button only on friends online on this server; count
  badge on the Friends bookmark.
- **Settings tab** (owner, 2026-09-28): server-stored per-player settings (`HubSettings` in
  `HubData`). First setting: **allow party invites from** Anyone / Friends / No one — enforced
  in `PartyManager.inviteState`, which also drives every invite button's enabled state and
  tooltip. Further settings TBD.
- Mockup direction for the other tabs approved in general; sketch each before building.
- **Server**: `hub` package. Persistent per-player state in one `SavedData` (`HubData`):
  unlocked dungeons, daily-quest progress; guilds get their own `SavedData` in phase 7.
  Parties are ephemeral (memory only). All state mutations on the server thread; Minestar
  reads stay async as today.
- **Sync**: custom S2C payloads per tab, sent on screen open + on change (no polling).
- **API**: `net.ledok.eden_ld.api.hub` — source-stable like `api.flight`. Other mods depend on
  it via `modCompileOnly`; every callback documented with its thread.
- **Build**: VectorLib 0.2.6 jars copied into `libs/` (as done for the skills mod); adding
  Eden_LD to VectorLib's `syncLibs` list needs the owner's approval. VectorLib is a required
  client dependency from phase 1 on.
- Lang: `en_us` + `uk_ua` for everything; docs (README/API.md) updated at the end of each phase.
- **BusyLib integration** (cross-cutting; owner's lib at `Fabric/BusyLib`, required dependency):
  every Hub action is validated server side against `BusyState.isBusy(player)`, and tab sync
  payloads carry the busy flag + reasons so the GUI **greys out blocked buttons** with a
  tooltip naming the reason. `BusyStateEvents.CHANGED` pushes a live refresh to an open Hub
  screen. Busy blocks: waystone teleports; party composition changes *inward* (sending invites,
  accepting members/join requests). **Leaving a party is never blocked** — players may leave
  a dungeon whenever they want, and Arenas_LD already handles mid-run departures and
  disconnects itself (owner, 2026-09-27). BusyLib has
  no client sync of its own — the Hub payloads are the sync.

## Phases

Each phase ships something usable on the live server.

### Phase 1 — Hub shell & core
- VectorLib into the build; parchment textures copied in; `EdenParchment` palette class
  (mirrors `ArenasParchment` naming).
- `HubScreen` shell with the tab bar (Home leftmost with house icon, Settings at the right
  end) and session-scoped tab memory (Home after join); keybind + `/hub`.
- **Home tab v1** (mockup approved): player summary strip (head, nickname, Minestar group +
  prime, LC balance via Economy compat, playtime) and the server news/MOTD panel
  (config-authored text; later possibly from the platform). The needs-attention card grid
  ships here as a framework, with each card arriving with its phase (shop claim immediately;
  friend requests → phase 3; party invite → phase 2; claimable dailies + mini progress
  strip → phase 6). Cards render only when actionable — otherwise an "all caught up" line.
  Home is the default tab after (re)joining.
- `HubData` SavedData + load/save plumbing; payload registration scaffold.
- `api.hub` package skeleton.

### Phase 2 — Party
Redesigned with the owner 2026-09-28 (replaces the 09-27 persistent design):
- **Model — TEMPORARY parties** (in-memory only, owner decision): a party is born when a
  solo player's invite is accepted (inviter becomes leader; no "create" flow), and dies
  when the member count drops below 2 or the last member logs off. Ranks
  **leader / vice-leader / member**; display name free-form and non-unique (rename by
  leader/vice); unique auto tag `<creator>_party` for admin commands. Max size (config).
- **No allies, no PvP toggle** — friendly fire between partymates is always off (server
  damage hook + Spell Engine / Better Combat team matchers). *(Later: Eden 1.3.3 added a per-player PvP toggle,
  docs/PLAN-pvp-toggle.md; partymates whose PvP differs ignore each other.)*
- **Join modes** (settings): invite-only (default) / friends join freely (Minestar friends
  of a member) / open to everyone. Joins are instant; the join-request queue was removed.
  The no-party tab lists parties the player can join.
- **Disconnect handling**: offline grace (config `party_offline_grace_seconds`, default
  3 min) before auto-kick; a disconnecting leader is succeeded immediately by the oldest
  active member (vices first, join order) and returns as vice.
- **Nameplates**: partymates render cyan, per-viewer (client mixin over synced party data).
- **Party chat**: `/p <msg>` + a chat panel in the tab (history + compose field). Routed
  through a small **ChatChannels core** built for the full channel system (see phase 8).
- **Tab layout** (with party): left — members list (heads + rank marks) over a
  selected-member card (promote/demote/transfer/kick per rank); center — party name header +
  settings gear, chat panel (heads); right — invite menu (searchable modal, friends first) +
  pending invites.
- **No-party state**: explanatory text, invite button (party forms on acceptance), your
  outgoing invites, invites to you, joinable parties.
- **Settings dialog** (leader/vice): rename, invite policy, join mode; disband (leader).
- **Commands**: `/invite <player>`, `/p`, `/party-admin list|disband <tag>` (OP 2).
- **API** `EdenPartyApi`: `getParty(player)`, members/ranks, leader checks, join/leave
  callbacks — what Arenas_LD consumes for "enter run as party".
- **TODO (owner, 2026-09-28)**: shared mob-kill XP — split among partymates within
  `party_xp_radius` of the killer with `party_xp_bonus_percent` group bonus (config keys
  already exist); needs a `LivingEntity#dropExperience` redirect of `ExperienceOrb.award`.

### Phase 3 — Friends
**Built 2026-09-28** (untested in-game) as specced below: three zones per the owner's layout
(on-server players with search · friends · collapsible requests panel with a slim toggle bar),
friend-row party invites, remove confirmation, Friends bookmark count + Home card, join nudge.
Server: `minestar/FriendService` (throttle 15 s, one in-flight action per player, HTTP status
parsed from Minestar's failure message), cached pushes on player join/leave (no platform
calls), both sides re-fetch after a mutation, then party data re-syncs (friend flags).
Minestar 1.3.0 covers most of the tab:
- List: nickname, **cross-launcher online status** (`friend.online()`, ~90s window),
  `lastSeenAt()`, and the modpack(s) they're playing (`playing()`). The join-time
  `ProfileCache` snapshot goes stale, so the tab **re-fetches the profile on open** (throttled)
  and renders from that.
- **Add friend**: `addUserFriend(playerUuid, friendUuid)` — auto-accepts when the other side
  already sent a request (`FriendRequestStatus.ACCEPTED` vs `PENDING`). Since there is **no
  nickname→UUID lookup**, v1 adds friends from a picker of this server's online players
  (UUIDs known); 400/404/409/429 mapped to friendly messages (409 disambiguated by checking
  `profile.friends()`).
- **Remove friend / cancel request**: `removeUserFriend` (cancels pending in either direction).
- "Invite to party" button on friends online on *this* server (bridges to phase 2).
- **Requests section** (Minestar 1.4.0): `profile.incomingRequests()` / `outgoingRequests()`
  (`UserFriendRequest{uuid, nickname, sentAt}`). Accept an incoming request =
  `addUserFriend` back; decline it — and cancel an outgoing one — = `removeUserFriend`.
  On join, a player with incoming requests gets a nudge message ("you have N pending friend
  requests"); the tab re-fetches on open, so no events endpoint is needed.
- Nickname→UUID lookup was deliberately dropped (same-server picker is enough).
- Tab hidden when Minestar is absent.

### Phase 4 — Activities: dungeons, raids, arenas (redesign, owner decisions 2026-10-01)
The 2026-09-28 draft (`hub/dungeon/`, `EdenDungeonApi`, `/dungeons`, hidden tab) is replaced by
this design; the draft code is rewritten as needed (no data migration).

**Split with Arenas_LD** (prompt for its agent: `docs/prompts/arenas-eden-dungeons.md`):
- **Arenas_LD** owns what exists and runs: dungeons, raids, mob arenas, instances, lobbies, runs,
  difficulties. It gives each one a **stable id** (e.g. `arenas_ld:crypt`) and exposes a small
  **API of its own** (Eden reads it; Arenas never compiles against Eden): the list of activities
  (id, type, name, enabled difficulties) with change notifications, "open this activity's lobby
  screen for this player" (no block needed), run-finished events (players, difficulty, outcome,
  time / wave), and an **entry gate** hook it asks before a lobby picks a difficulty, before
  joining, and for every member when a run starts. The owner is considering leaving the lobby
  block behind (activities always loaded/ready instead of force-loaded chunks); the API is id-based
  so either way works.
- **Required levels and card art are set in the Arenas admin GUI** (owner, 2026-10-01): a required
  level on each difficulty's tab (one for arenas) and a card-art id (a profile background id,
  registered hidden + locked so it never shows in the picker; can later unlock as a profile
  background, e.g. on first clear). Arenas stores and reports them; **Eden** decides the player's
  level and registers the entry gate that enforces them. Optional Eden files keyed by the id only
  for extras (description, sort override).

**Levels**: one level source, config `dungeons.level_source` (e.g. `puffish_skills:<category id>`
— the Pufferfish Skills tree level; the owner picks the category later). Behind a switch so an
Eden-only level can replace it. Unset = nothing is gated (log warning).

**Rules**: an activity unlocks at the level of its **first enabled difficulty** (Easy 5, or Normal 10
when Easy is off…). Each difficulty needs its own level; **every party member** must meet it. No
other prerequisites (difficulties progress naturally: better loot and XP).

**Hub tab**:
- The tab is **Activities** (owner, 2026-10-01); Duels got a tab of their own instead of a filter.
- Filter tabs **All · Dungeons · Raids · Arenas**; sort by **name / required level / type** plus an
  ascending/descending toggle.
- **Minimal cards**: the art; unlocked → the name top-left; locked → name + dimmed art + "🔒 Locked ·
  minimum level N".
- **Click** an unlocked card → Arenas_LD's lobby screen for it (lobbies list, my lobby,
  leaderboard, invites…) — everything else lives there. Locked difficulties are dimmed there with
  "🔒 Lv 25+".
- **New unlocks**: when a player's level opens an activity, its card glows (like notification tabs),
  the Dungeons tab gets a badge, and Home shows a "New dungeon unlocked: …" card until seen.
- **Duels** get their own hub tab (owner, 2026-10-01): ratings and ranks, per-mode W/L/D, today's
  rewards, recent matches, top players, queue buttons with live state. Prompt for the Duels agent:
  `docs/prompts/duels-hub-tab.md` (Duels exposes `DuelsApi`; Eden reads it).
- **Eden side built 2026-10-01** (dev-tested with datapack activities and `-Deden_ld.devActivityLevel`):
  `hub/activity/` (Activity, ActivitySource, Activities, DatapackActivities, ActivityLevels via
  `compat/PuffishCompat`, ActivityGate, ActivityProgress, ActivityServer), `EdenActivityApi`,
  `/activities complete|reset|level`, Activities tab (cards, filters, sort, glow), badge + Home card,
  Moderation player-page tab.
- **Arenas_LD adapter built 2026-10-01** against Arenas 4.5.0 (`ArenasApi`): `compat/arenas/`
  (ArenasHook, ArenasIntegration) — an ActivitySource (registered before datapack files, so its ids
  win), `Activities.changed()` on its activities listener, `ActivityGate::check` as its entry gate,
  run listener → `ActivityServer.recordRun`. Compiled against a copy of its jar in `libs/`. Not tested
  in game (needs a controller with an id).
- **Duels tab built 2026-10-01** against Duels 1.4.0 (`DuelsApi`): `compat/duels/` (DuelsHook,
  DuelsIntegration), `hub/duels/DuelsHub` (viewers, batched pushes, queue actions), payloads
  `DuelsView`/`DuelsQueue`/`DuelsData`, `HomeData.duelsAvailable`, `HubTab.DUELS` (bit 11),
  client `DuelsTab`. Dev-tested: empty state, "no arena" refusal, searching timer, leave; the lists
  with sample data. In-match state comes from match start/end events (DuelsApi has no lookup).
- **Owner's playtest 2026-10-01**: all passed. Changes: hub settings moved out of Arenas into Eden —
  `hub/activity/ActivitySettings` (world data: shown, card art, level per difficulty / arena level),
  applied over every source in `Activities.all`; `Activities.visible` feeds the tab and unlock
  notices; new activities are hidden. Moderation window → **Activities** section
  (`client/hub/ActivitiesSection`, AdminTools area `activities`). The Arenas adapter ignores Arenas'
  `requiredLevel`/`cardArt`; prompt `docs/prompts/arenas-standalone.md` removes them from Arenas
  (4.6.0). `client/hub/ActivityReturn`: Esc on a lobby screen opened from a card reopens the hub on
  Activities.
- **Open (owner)**: Arenas' "leave the lobby block behind" — the Arenas agent's assessment: activity
  state moves into world-saved data keyed by id, packets and screens by id, a one-time migration of
  controllers, the block becomes an optional kiosk; about 2–3× the 4.5.0 change, with migration and
  chunk-loading risks. The API stays the same either way.

### Phase 5 — Waystones integration
**Built 2026-09-28** (untested in-game): `compat/waystones/WaystonesHook` (no blay09 imports)
+ `WaystonesIntegration`; compiles against waystones-fabric 21.1.42 / balm 21.0.65
(`modCompileOnly`, pack runs 21.1.45). Destinations = GLOBAL-visibility stones + the player's
activated ones (public API only). Teleport = `createDefaultTeleportContext` with the
`INVENTORY_BUTTON` flag → `tryTeleportAsync`, so the inventory-button requirements from the
Waystones config apply; the server re-checks the id is in the player's list and BusyLib.
Balm events (activated/updated/removed) push live refreshes. Tab hides via HomeData flag.
**Reworked with the owner the same day**: hub teleports use **Eden's own rules** — Waystones'
requirements are replaced by a free `WarpRequirement` (its "not enough XP" error was really the
inventory-button cooldown rule); `hub/waystone/HubTeleportRules`: cooldown (config 30 s,
LuckPerms meta `eden_ld.waystone_cooldown` per role) + PvP block (30 s after landed damage from
a player, victim only). Layout: Favorites (left, HubData `waystone_favorites`) | sections Mine /
Friends' / Global / Activated (right); fixed bottom status line with live countdowns.
**Second pass (owner, same day)**: PvP block became a general `combat/CombatTag` (tags
attacker AND victim; also grounds flight via an Eden flight rule at priority 50, synced to the
client); favorites + per-player rename now use Waystones' own Favorites group and alias
(internal `PlayerWaystoneManager` + `WaystoneSyncManager.sendActivatedWaystones`, verified in
21.1.42) — Eden's own favorites store was dropped. Party protection now covers pets
(`CombatTeamsCompat.sideOf`: owned entities count as their owner).
- **Tab closed for now (owner, 2026-09-28).** Parked idea: Waystones' groups (players can file
  a stone under one group) — not shown in the tab.
- **Future (owner, 2026-09-28)**: Waystones' `TEAM` visibility could show guild waystones
  once guilds exist (phase 7).
- The pack runs **Blay's Waystones 21.1.x** (`net.blay09.mods:waystones`, needs Balm; source at
  `~/Projects/Mods/Waystones`). Its API covers the tab completely, server side:
  - List: `WaystonesAPI.getActivatedWaystones(player)` (per-player) +
    `WaystoneManager.getGlobalWaystones()` — global waystones are a native feature, no own
    "global" concept needed.
  - Teleport: `WaystonesAPI.createUncheckedDefaultTeleportContext(player, waystone, ...)` →
    `tryTeleportAsync(context)` — the mod's own requirements/costs/cooldowns apply and errors
    come back as `WaystoneTeleportError` (shown on the tab).
  - Live refresh: Balm events `WaystoneActivatedEvent` / `WaystoneUpdatedEvent`.
- Isolation like Minestar: a `WaystonesHook` (isModLoaded gate, no `net.blay09` imports) is the
  only class the rest of Eden_LD touches; `modCompileOnly` on the waystones jar. Soft
  dependency — the tab only exists when the mod is loaded.
- Eden_LD sends its own S2C payload for the tab (name, dimension, distance, global flag) — the
  client never talks to Waystones directly, keeping the integration server-only.
- **Tab layout** (mockup approved): two columns — global / yours (searchable); rows show name +
  dimension, distance only for same-dimension stones; footnote that costs/cooldowns are the
  Waystones mod's. Teleport buttons grey out while the player is busy (BusyLib), tooltip
  names the reason.

### Phase 6 — Daily quests
**Built 2026-09-28** (untested in-game). `hub/daily/`: DailyQuestDefinition/DailyReward codecs
(registry-aware reload → full ItemStack rewards), DailyQuestRegistry (datapack
`daily_quests/` **plus `config/eden_ld/daily_quests/*.json`**, example `.json.example`
written), DailyQuests engine (HubData `dailies`: day, slots, rerolls, extra_rerolls, bonus;
seeded base draw per day, reset_hour server time), DailyProgressHooks (kills via
AFTER_KILLED_OTHER_ENTITY, crafting via `ResultSlotMixin.checkTakeAchievements`),
`EdenDailyApi` (reportProgress, grantExtraRerolls). Owner additions: **free rerolls per day**
(config `free_rerolls`, LuckPerms meta `eden_ld.daily_free_rerolls`) before the LC curve,
`/dailies give-rerolls` + `/dailies reset`. `cast_spells` counted from Spell Engine's
`SpellEvents.SPELL_CAST` (api.spell.event — same hook NPCs_LD uses). Round 2: no duplicate
quests at once; reroll avoids the replaced quest unless the pool is exhausted; one action slot
(↻ → Claim → ✔); unaffordable reroll greyed with "costs N, you have M" (balance in
DailiesData); `time_zone` config. Round 3 (two languages, owner): `name` optional —
`DailyObjectives` generates "Kill Zombie ×20" from translate components (game names);
rewards sent as `RewardView` (real ItemStacks → icons with vanilla tooltips, XP, LC, note);
`hub/lang/ServerLang` = NPCs_LD-style server lang tables (`config/eden_ld/lang/<locale>.json`,
`data/<ns>/eden_lang/<locale>.json`) filled into translate components' fallback per player. Generic `HubNotice` toast for hub errors.
- Definitions from datapack: `data/eden_ld/daily_quests/*.json` — objective type
  (`kill_mobs` [entity type/tag], `cast_spells`, `craft_items` [item/tag], `complete_dungeon`
  [id or any]), count, rewards (items / commands / xp).
- Rotation: N random quests per day (config), seeded by date; reset at a configured hour;
  per-player progress + claimed flags in `HubData`.
- **Reroll** (owner, 2026-09-27): each quest card gets a reroll button — replaces that quest
  with a fresh random one from the pool (progress lost, claimed quests not rerollable). First
  reroll of the day is free, then an escalating LC cost (50, 100, 200, … — config curve);
  LC via Economy_LD compat (same reflection pattern as Arenas' `EconomyCompat`). Reroll count
  resets daily.
- **Completion bonus** (owner, 2026-09-27): claiming ALL of the day's quests grants a bonus
  reward (config: items/commands/LC) — shown in the tab as a final "all missions" row with
  its own claim.
- **Tab layout** (mockup approved): reset timer in the panel header; full-width quest rows —
  objective icon, name, progress bar, "n / m · reward preview" line; claim button appears at
  100%; reroll button per card; bonus row at the bottom.
- Counters: kills via Fabric's after-death event; crafting via a small `ResultSlot` mixin;
  spells and dungeons come *in* through the API — **`EdenDailyApi.reportProgress(player, type,
  amount)`** so Arenas_LD / a Spell Engine hook / NPCs_LD feed it without Eden_LD depending
  on them.
- Dailies tab: today's quests with progress bars and claim buttons; rewards granted server-side
  with a toast.

### Phase 7 — Guilds
Decided with the owner 2026-09-28 (replaces the 09-27 sketch):
- **Persistent** guilds (`GuildManager` SavedData): id, unique name, unique **tag (≤5 symbols,
  hub only — not in chat/nameplates; LuckPerms role prefixes already live there)**, color,
  MOTD, created date, members with **fixed ranks leader / officer / member / recruit**,
  pending invites, applications, chat history.
- **Create** costs LC (config `guild_create_cost`, **default 0**).
- **Join mode per guild**: invite only / applications (officers accept) / open. Guild
  directory in the no-guild state (browse, apply, join).
- **Friendly fire always off** between guildmates (same machinery as parties — damage,
  projectiles, pets, Spell Engine/Better Combat — and the same suspension API for duels).
- Guild chat `/g` + chat panel (ChatChannels core). Home card for guild invites.
- **API** `EdenGuildApi` (read + membership listeners).
- **Guild bank (WoW-style, step 2)**: pooled LC + item **vault tabs**. A new guild has no tabs;
  the leader buys tab 1 cheaply, each next tab costs exponentially more, **paid from the bank's
  LC**. Per-rank, per-tab permissions: view only / deposit only / withdraw with a **daily limit**
  (stacks per day); per-rank daily LC withdrawal limit; **audit log** of every deposit and
  withdrawal (who, what, when). Bank LC also buys **upgrades**: member limit (base 10) and
  claim limit.
- **OPAC (step 3)**: Eden registers guilds as OPAC's **party system** (like its FTB Teams /
  Argonauts integrations: `IPlayerPartySystemAPI`, config `primaryPartySystem`); OPAC's own
  parties are replaced; `partyOwnedClaims` makes claims guild-owned (technical owner = leader);
  claim-limit upgrades via the leader's `BONUS_CHUNK_CLAIMS` player config.
- **Guild waystones (step 4)**: Eden-side, no scoreboard teams (Duels_LD uses them): members'
  TEAM-visibility waystones appear in the hub (Guild section) and in Waystones' own menus via
  `BuildWaystoneSelectionMenuEvent`.
- **Step 4 built 2026-09-29** (untested): `WaystonesIntegration` — TEAM visibility = owner's
  guild. `guildStones()` (TEAM + owner in the player's guild) join hub `targets()` (so favorite /
  alias / teleport `reachable()` accept them; Waystones' checked context has no access check)
  and every selection menu via `BuildWaystoneSelectionMenuEvent` (skips sharestone kinds /
  sharestone sources / ADMIN flag; removes TEAM stones from non-guild scoreboard teams unless
  activated). Hub: `WaystoneEntry.GUILD = 4` section after Mine; `HubServer.refreshWaystones`
  after guild actions and admin disband. EditWaystoneMessage fires no event on visibility
  change → hub refreshes on next open.
- FTB Teams replacement/migration: later, if still needed.
- **Step 1 built 2026-09-28** (untested): `hub/guild/{Guild, GuildRank, GuildManager (SavedData
  data/eden_ld/guilds.dat), GuildServer}`, `EdenGuildApi`, GuildTab (client), `/g`, `/guild-admin
  list|disband <tag>`, shared protection `PartyManager.protects(server, a, b)` (party OR guild),
  client relation covers guildmates, Home guild-invite card.
- **Step 2 built 2026-09-28** (untested): `GuildBank` (NBT with the guild; LC, tabs of 54,
  RankRights per rank/tab + LC/day, per-player daily Usage, 300-entry audit log, memberLevel /
  claimLevel), `GuildBankService` (atomic click moves, no cursor item; disband blocked while the
  bank holds anything), `GuildBankServer` (viewer tracking → live pushes), client
  `GuildBankView` (hub-styled, owner's choice). Also: Guild tab right-panel collapse bar;
  per-player "Show guild tag" setting → nameplate (client) + Placeholder API
  `%eden_ld:guild_tag%` / `%eden_ld:guild_name%` for Styled Chat / Styled Player List.
- **Step 3 built 2026-09-29** (untested): `compat/opac/{OpacCompat (only entry point, no-op
  without OPAC), OpacHook (v3 OPACServerAddonRegister), EdenGuildPartySystem (v2
  IPlayerPartySystemAPI<Guild>; officer = Claimer/Moderator, vice+ = Admin, no allies),
  OpacGuildClaims}`. Active only when OPAC's primary system is ours and `partyOwnedClaims` is on
  (read reflectively). `reconcile` runs from `GuildServer.syncAll` + SERVER_STARTED: bonus
  applied as deltas, tracked in `Guild.opacOwner/opacBonus`; leader change → bonus moves and
  all old-leader claims re-owned via `claim(dim, to, -1, x, z, forceload)` (always — no config:
  guild land must not leak into personal land). Disband → `release` takes the bonus back.
  **Anti-abuse (owner found 2026-09-29):** claim with the guild limit → disband → keep land →
  repeat grew without bound. First fix (refuse disband) replaced on owner's request with the
  FTB Chunks model (checked FTB-Chunks: last member leaving → claims to them up to their limit,
  excess unclaimed): `OpacGuildClaims.end` on delete keeps the leader's land up to their own
  limit (base − OPAC party-owner bonus + bonus − Eden's bonus; options read reflectively) and
  releases the rest newest-first (config `guild_release_newest_first`) using `ClaimTimes`
  (SavedData `data/eden_ld/claim_times.dat`, fed by an OPAC IClaimsManagerListenerAPI; transfers keep
  times) — to expired claims if OPAC `playerClaimsConvertExpiredClaims`, else unclaimed.
  Kicks just leave the guild over-limit (OPAC throttles; FTB does the same); land ≤ peak limit. BankData carries
  claimsUsed/claimsLimit for the upgrades box.
- **Build order**: 1) core (guilds, tab, join modes, /g, protection, API) → 2) bank + upgrades →
  3) OPAC → 4) guild waystones. Each step testable on its own.

### Phase 8 — Chat channels
Owner's vision (2026-09-27): **GLOBAL / LOCAL / PARTY / GUILD** channels, switchable while
typing in normal chat (Project Zomboid feel); only GLOBAL goes to the Minestar Discord bridge.
- Foundation is the **ChatChannels core** from phase 2 (all party chat already routes
  through it); GUILD needs phase 7.
- **Switch UX** (Tab is taken by vanilla completion): `Ctrl+Tab` (or `Alt+Up/Down`) cycles
  channels in the chat screen via a small `ChatScreen` mixin; a **channel badge drawn left
  of the chat input** shows the active channel and is clickable to cycle; typed prefixes
  (`!g` `!l` `!p` `!gd`) send one-off messages without switching. Per-player channel choice
  persists.
- **LOCAL** = radius-based broadcast (config radius).
- **Integration check**: Minestar's chat bridge relays in-game chat to Discord on its own —
  verify which event it reads and ensure only GLOBAL reaches it (suppress before the bridge
  sees non-global messages, or ask the devs for a bridge-visibility flag).
- **Owner decision 2026-09-29: Eden replaces Styled Chat** (and later Styled Player List).
  Their Styled Chat config: display name `%luckperms:prefix%<color>name`, colors per group
  (default #00c8c9, admin #931001, moderator #1a39fe, helper #00e014, prime #69118f),
  `${player} <dark_gray>»</dark_gray> ${message}`, admin message orange + no join/leave,
  leave red, links/mentions #7878ff, markdown, spoilers, tag formatting, emoticons
  (joypixels, :shrug:, :item:, :pos:…). Wants Discord emoji integration later.
- **Part 1 built 2026-09-29** (untested): verified Minestar bridge = Fabric
  `ServerMessageEvents.CHAT_MESSAGE` (reads `signedContent()`); Styled Chat formats via a
  mixin on `OutgoingChatMessage.create` (so it must be removed). `ChatChannels`: ALLOW_CHAT_MESSAGE
  → always false; Eden delivers per channel with `recipient.sendChatMessage(OutgoingChatMessage
  .create(msg.withUnsignedContent(styled)), filter, bind(eden_ld:chat "%s%s", senderLine))`,
  fires CHAT_MESSAGE only for GLOBAL, logs. `/p /g /l` + hub panels → same path as system
  messages. Channel choice in HubSettings `chat_channel` (falls back to GLOBAL when
  unavailable); one-shot `!x` via C2S ChatOneShot (client strips so signature stays clean;
  server-side prefix fallback for vanilla clients). `ChatStyle`: Player.getDisplayName mixin
  (ServerPlayer only) = prefix + name(meta `eden_ld.name_color`) + suffix; content with meta
  `eden_ld.chat_color`, links, @mentions (+sound). Join/leave via ALLOW_GAME_MESSAGE (meta
  `eden_ld.silent_join`, red leave). Client `ChatScreenMixin` badge/Ctrl+Tab/click, `/ch`.
- **Part 3 built 2026-09-29** (untested): `chat/TabList` replaces Styled Player List — names via
  a `ServerPlayer#getTabListDisplayName` mixin (guild tag + `ChatStyle.displayName` over the
  team-formatted plain name), header/footer from config (legacy codes, `{online} {max} {player}
  {ping} {tps} {mspt}`, Placeholder API `%…%` when present), refresh every `update_ticks`
  sending only changes (UPDATE_DISPLAY_NAME / TabList packet per player). Rank sort: S2C
  `TabOrder` (uuid → meta `eden_ld.tab_weight` else highest LuckPerms group weight), client
  `PlayerTabOverlayMixin` wraps vanilla's comparator (spectators last, rank desc, vanilla).
- **Formatting built 2026-09-29** (untested in game; parser unit-checked): `chat/ChatFormat` —
  Discord markdown (** * _ __ ~~ || `), tags (colors/#hex/color:x, b i u st spoiler reset, `</x>`
  `</>`), escapes, protected tokens (links, emojis, <:n:id>, :name:, @names). Spans → callers'
  token renderers (ChatStyle.appendTokens, EmojiRegistry.appendTokens, EmojiFont.appendEmojis);
  spoilers = ▌ bars with hover (hub: line tooltip). Discord out: tags → markdown; Discord in:
  markdown applied. Config chat.markdown/color_tags, sent to clients in ChatChannelState.
- **:item: / :pos: built 2026-09-29** (untested): ChatStyle.shortcut (item = getDisplayName with
  SHOW_ITEM hover + ×count; pos = green, copy on click, dimension hover), expandShortcuts for
  Discord (item names escaped). Not ported: :shrug: / :table: text emoticons.
- **Hub chat hover/click (2026-09-29, untested)**: Party/Guild.ChatLine + ChatEntry carry the
  styled Component (ChatStyle.content; guild NBT stores component JSON), client
  `client/hub/ChatText` (TextNode wrapper: style under mouse via StringSplitter → hover
  tooltips SHOW_TEXT/ITEM/ENTITY, click → Screen.handleComponentClicked, RMB emoji favorite);
  HubScreen draws GifViewer; ScreenMixin covers HubScreen. Client-side hub formatting removed.
- **Stickers (2026-09-29, untested)**: owner said no GIF auto-previews — only stickers inline.
  `ChatLinks.Sticker` (id, png|gif, name; favorite id `s:<id>.<ext>:<name>`, link carries
  `&name=`), client `MediaLoader` (shared with GifViewer) + `StickerImages` (LRU 32, 96 px, 60
  frames), hub `StickerNode` (64 px under messages via ChatText.message; 32 px in picker).
  Saved stickers: own HubData list `sticker_favorites`, limit config chat.sticker_favorites /
  meta eden_ld.sticker_favorites, EmojiList carries them; EmojiTabs STICKERS tab (index 1,
  SERVER now 2) in both pickers; RMB on [Sticker] saves (chat screen, hub text, hub image).

### Settings sub-tabs (2026-09-29, untested)
Owner: Settings split like game options — Privacy (party invites, guild tag), Chat, Visual
(theme). Chat idea 1 built: deaths/advancements Everyone / Party & guild / Nobody (filtered in
ChatChannels.onGameMessage by key `death.*` / `chat.type.advancement.*`, subject from the name's
entity hover; re-broadcast per recipient via broadcastSystemMessage(msg, fn, false)); hide
Global/Local/Party/Guild (recipients filtered in deliver) and Discord (lines starting with
config `chat.discord_line_marker`). More chat ideas from the owner to come.
**Reworked same day (owner): hide client-side, not unsent.** `chat/ChatMarker` puts an empty
literal with insertion `eden_ld:line:<kind>[:team][@sender]` in every Eden line (sender line of
chat; Discord, deaths, advancements re-broadcast per recipient with a team flag); client
`ChatFilter` + `ChatComponentMixin` (HEAD addMessageToDisplayQueue) leave hidden kinds out,
`rescaleChat()` on settings/channel change brings them back. Own lines always show. Setting
"Only the selected channel" (`chat_focus`). `ChatHistory`: last `join_history` Global + Discord
lines to joining players.
Also same day: per-player **mention sound** toggle (`chat_mute_mentions`), **Tab after "@"**
completes names (CommandSuggestionsMixin adds "@name" candidates), and the **config split**
into `config/eden_ld/<section>.json` (reflection over EdenConfig's section fields; old
eden_ld.json ignored with a log warning — no migration, per owner's no-legacy rule).

### Modules — reverted (2026-09-29)
Built, then reverted on the owner's call the same day: the chat part is being copied into its
own NeoForge mod (Chat_LD) instead, so Eden_LD stays all-in-one. Kept from that work: the fix
for HubState.onPartyData wiping guild data on every party push. (An explore agent's full
inventory of every system's entry points was made then; redo it if module toggles come back.)

### Phase 9 — Profile (2026-09-30, from Minestar 1.5.0)
Minestar 1.5.0 added to `UserProfile`: `xp()` (level, total XP, XP into / for the level, XP to
next), `rewards()` (id, title, description, image link, rarity COMMON…LEGENDARY), `playtime()` (all
servers) and `registeredAt()`; no new API calls, and `UserFriend` carries none of it (an offline
friend's page costs one profile fetch). Built (owner's pictures: Friends card with a Profile row,
Home card with the level on the left, the Minestar site's profile as the page layout):
- `minestar/ProfileSnapshot` (Eden-typed profile), `ProfileService` (cache for online players,
  5-minute cache for others, refresh every `profile.refresh_minutes`, listeners on the server
  thread), `MinestarHook.profile/fetchProfile/onProfileUpdated`; dev-only `DevProfiles`
  (`-Deden_ld.devProfiles=true` in a dev run) for UI work without the platform.
- `hub/profile/LevelPerks` (rules shared with the client), `ProfileLevels` (level kept in HubData
  `profile.level`; level-up / first-sight messages; OPAC per-player bonus claims as a tracked delta
  `profile.claims`), `ProfileServer` (pages, privacy check `mayView`, background select).
  Perks feed `EmojiRegistry` favorite/sticker limits and `DailyQuests.freeRerolls`. Owner's rules:
  odd levels +1 emoji slot, even +1 sticker slot, 5/15/25… +5 claims, 10/20/30… +1 free reroll.
- Settings → Privacy: **Who can see my profile** (Anyone/Friends/No one) → `profile` flags on
  FriendEntry / ServerPlayerEntry / PartyMember / GuildMember (greyed Profile buttons otherwise).
- Backgrounds: resource PNGs `textures/gui/profile_backgrounds/*.png` (client registry
  `ProfileBackgrounds`, cover-cropped), 7 generated built-ins (`tools/backgrounds/`), unlocks like
  themes (`profile.free_backgrounds`, `/hub background unlock|lock`).
- Client: `ProfileTab`, `LevelRing`, `RewardIcon` + `RemoteImages` (https only, first frame),
  Home card redesign, Profile buttons (Friends cards, party/guild member cards), `/hub profile`.
- Placeholders `%eden_ld:minestar_level|xp|playtime|rewards%`.
- Checked in a dev client (with the dev profile): Home card, Profile page, level track,
  background picker + change, privacy row, reward image + tooltip. Not checked: real Minestar data
  (level-up messages, perks applied, OPAC claims), other players' pages, privacy with 2 players.

### Icon tabs, help, notifications (2026-09-30)
- Tabs are icons (placeholders: colored squares/circles, `HubTab.iconColor`/`round`); the open
  tab adds its name; hover names the others. `TabBookmark` draws skin, icon, name and news.
- "?" left of Settings opens `HubHelp` for the open tab (`gui.eden_ld.hub.help.<tab>`).
- News: counts (friends, party, guild, dailies, shop on Home) and "new" for never-opened tabs
  (`seen_tabs` bits, server side). Style badge / glow / both, color theme (palette
  `notification`) or 8 fixed; Settings → Notifications turns kinds off (`notify_off` bits).

### Next: Mail tab (owner decisions, 2026-09-30)
- An MMO-style mailbox tab (DbD inbox look): list left (kind icon, title, date, "deleted in N
  days", attachment marker, unread dot), message right (text, items/coins, Claim, Claim all,
  Delete read), unread badge on the tab.
- Kinds: rewards (dungeons, raids, duels), shop (Minestar purchases; replaces `/shop-receive`),
  administration (`/mail send <players|all|group> …` with optional items/coins and expiry),
  system (auction and payment results).
- Storage stays in Economy_LD's database (Arenas/Duels already deliver there), extended with
  text-only messages; Economy's own inbox opens Eden's tab when Eden is installed.
- Expired mail is deleted, unclaimed items included. Mail to "all" also reaches players who
  haven't joined yet. No player-to-player mail (auctions and shops cover trading).
- After Mail: fullscreen hub; then shared themes across mods and a small TrueType font (both
  VectorLib changes, need approval).

### Phase 10 — Guild quests and levels (owner decisions, 2026-10-01)
- **Guild XP and levels.** Guilds earn XP and level up (config `guild.level_xp`: XP for each next
  level; list length + 1 = the cap). XP comes from guild quests and, a little, from members'
  own dailies (config `guild.daily_quest_xp`, or a daily's own `guild_xp`). A daily card shows
  its guild XP only to players in a guild.
- **Guild quests**: harder, specific, shared by the whole guild ("shoot 200 arrows", "cast Frost
  Bolt 100 times", "eat 50 food"). Same objective types as dailies. Pool in
  `data/<ns>/guild_quests/` and `config/eden_ld/guild_quests/`; each has a `period` (daily or
  weekly), a level range (`min_level`/`max_level`, higher levels draw harder quests), optional
  `count_per_member`, and rewards: `guild_xp`, `lc` (into the guild bank) and `items` (mailed to
  every member at completion, expiring at the next reset; joining after completion gets
  nothing — owner accepted that).
- **Board**: 5 daily + 2 weekly (config), drawn per guild and period from the quests its level
  allows; guild levels can add slots. Completes automatically (no claim button); the guild chat
  and online members are told. Weekly reset: config day at the daily reset hour.
- **Guild level bonuses**: same rule format as Profile level perks (`from`/`every`/`amount`/
  `until`), config `guilds.level_perks`. Owner's defaults (2026-10-01): L2 +5 emoji slots, L4 25% off
  claims, L5 and L9 +1 daily quest, L6 +1 buyable vault tab (tab_cap 5), L7 +1 weekly quest, L8 25%
  off vault tabs, L10 +5 free daily rerolls for members (new perk `daily_rerolls`). Kinds: emoji
  and sticker favorite slots for members, extra daily/weekly quest slots, discounts (percent) on
  vault tabs / member upgrades / claim upgrades, and more of those purchases than the base caps
  (`guild.tab_cap`, `member_upgrade_cap`, `claim_upgrade_cap`; -1 = the whole cost list).
- **UI**: the Guild tab gets a header (guild name, level in an XP bar, settings gear) and sub-tabs
  Members · Recruiting · Quests · Level · Bank (replacing the Members/Bank switch). Recruiting
  (officers+, owner's idea) holds invite players / applications / pending invites, so Members is
  roster + a full-width chat.
- **Built 2026-10-01** (tested in a dev client: board, completion, mail, bank LC, level-up, perks
  track, discount; Recruiting with real candidates/applications not tested). Level rewards and the
  XP curve set by the owner on 2026-10-01 (see above); quest rewards are per quest file.
- Admin: `/guild-admin xp <tag> add|set <amount>`, `/guild-admin quests <tag> reset`.

## Open items
- ~~Minestar API gaps~~ — all resolved: 1.3.0 brought friend status + add/remove, 1.4.0
  brought request listing; batch profile fetch was declined (their backend caches per
  profile; single fetches are cheap and expected). Nothing pending with the Minestar devs.
- Owner approval to add Eden_LD to VectorLib `syncLibs`.
- **Icon art (owner, 2026-09-27)**: make icons for every hub tab (tabs then become
  fixed-width), for party/guild roles (replace the ♛/★ text prefixes), and for the
  settings gear (replace the ⚙ text button).
- **Duels_LD rewrite**: done (Duels 1.3.0 uses `EdenPartyApi` and suspends party protection
  around matches); in-game two-player checks pending (see todo.md).
- **Rich hub themes — built 2026-10-02** (`background`, `rows`, `decorations`; example `eden_ld:overgrown`; docs/THEMES.md). Original spec: extend the theme JSON beyond
  palette + nine-slice panels:
  - `background`: texture + mode `tile` (patterns) / `stretch` (full picture) / `nine_slice`,
    for the panel and for rows/cards (`row` texture instead of flat fills).
  - `decorations`: image list with anchor (`top_left`, `left`, `tab_bar`, `bottom_right`, …),
    offset, size, `behind`/`in_front`, non-pickable — may sit outside the panel. Needs a
    reserved margin around the hub canvas (counted in the fit-to-window scaling).
  - Later: frame-animated decorations, per-tab accents, tab-label colors.
  Resource-pack only for theme makers; no VectorLib changes expected (ImageNode +
  NineSlicePanel TILE/STRETCH already exist). Owner's mockup: vines down the left edge,
  ornament over the tabs, patterned background.
- No git commits until the owner asks.
