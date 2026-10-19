# PvP toggle (Eden 1.3.3)

Owner's spec, 2026-10-19. Players choose whether they take part in PvP; explorers who don't want PvP can still play
and explore the world.

## Rules

- **Button** in the inventory, right of the hub button (`InventoryHubButton`): shows on / off, the cooldown left,
  "in combat" and "PvP is off here". `/pvp` shows the state, `/pvp on|off` switches. Admins:
  `/eden pvp player <players> on|off` (no cooldown, no combat check).
- **Default off** for everyone (new and existing players).
- **Cooldown:** 1 minute after any switch, both ways; it survives relog.
- **Combat tag:** a combat-tagged player (`CombatTag`, 30 s) can't switch.
- **World modes** (`dimensions.json` `"pvp"`, `/eden pvp <dimension> off|toggle|on|reset`, the hub's World settings):
  - `off` — everyone counts as PvP off (spawn).
  - `toggle` — each player's own choice (overworld, nether, end). **The default** for a dimension without its own
    setting while the server's `pvp=true`; with `pvp=false` the default is `off`.
  - `on` — everyone counts as PvP on (a future forced-PvP zone).
  - `dimensions.json` keeps reading `true` (= `on`) and `false` (= `off`); `"toggle"` is new.
- **Relations** between two players (their pets and summons count as their owner). "Team" = same party or guild
  (`PartyManager.protects`), or the same scoreboard team with friendly fire off (Arenas run teams, Duels sides).
  "On" = the player's *effective* state: the world's mode, or their own flag in a `toggle` world.

  | | Same team | Different or no team |
  |---|---|---|
  | Both on, or both off | **Friends**: heals and buffs land, no damage | both on: **Enemies** (damage, no heals); both off: **Ignore** |
  | One on, one off | **Ignore** (party/guild) — a run team stays **Friends** | **Ignore** |

  - **Ignore**: nothing lands either way — no damage, no heals or buffs, no slows/pulls/knockback, AOE passes over
    them, they aren't spell targets or highlighted, arrows and spell projectiles fly through, splash and lingering
    potions skip them. (The owner's reason: a PvP-off guildmate must not heal or buff a PvP-on fighter risk-free.)
  - **Duels**: two players in the same running match follow the duel's teams (opponents = enemies, partners =
    friends) whatever their flags; a duelist and someone outside the match ignore each other.
  - Run teams (dungeons, raids, arenas) stay friends whatever the flags.
- **Nameplate icon**: the hub's Duels tab icon (`textures/gui/icons/tab_duels.png`) beside the name above the head of
  every player whose effective state is on where they stand.
- **Hitting someone you ignore** (melee, a direct hit): an action bar line "Bob has PvP off" (or "…is in a duel"),
  at most every 2 s.
- **Tutorial**: the NPCs_LD session gets the button's spotlight id (`eden_ld:inventory/pvp_button`) to explain it.

## How it plugs in

- **One decision**: `CombatTeamsCompat.resolve(attacker, target)` gains an `ignore` kind besides
  `(teammates, friendlyFire)`. Friends → `(true, false)`; enemies → `(false, true)`; ignore → `(true, false, ignore)`.
  Everything that already asks it follows: `HubServer` `ALLOW_DAMAGE`, `PlayerMixin.canHarmPlayer`,
  `ProjectileMixin.canHitEntity`, Spell Engine's and Better Combat's team matchers (ALLY / FRIENDLY for ignore, so
  summons, Holy Barrier and sweeps leave them alone; HOSTILE for enemies).
- **Spell Engine has no "ignore" relation** (every relation allows damage or healing), so a mixin on
  `net.spell_engine.internals.target.EntityRelations.actionAllowed(FocusMode, Intent, LivingEntity, Entity)` (HEAD,
  cancellable, `remap = false`, `@Pseudo`) returns false for an ignored pair. It's the one gate for spell targeting,
  every impact, spell projectiles and melee skills (SpellTarget, SpellImpacts, SpellProjectile, Melee).
- **Potions**: `ThrownPotion` (splash) and `AreaEffectCloud` (lingering) — wrap their `addEffect` and instant-effect
  calls: ignore → nothing; friends → beneficial effects only; enemies → harmful (and neutral) effects only.
- **Client side** (targeting highlights, Better Combat swings): the matchers run on the client too, so it gets each
  player's flag (`PvpPlayers`: the online players with PvP on, broadcast on change and join) and the world mode where
  it stands (`PvpState`: own flag, cooldown left, combat-tag-blocked, mode here; on join, change, dimension change).
  The client's team knowledge stays its own party and guild (as `ClientPartyRelation`) plus synced scoreboard teams;
  the server stays authoritative.
- **Storage**: `HubSettings` per player — `pvp` (0/1, absent = off) and the switch time (epoch seconds) for the
  cooldown. No migration.
- **Payloads**: `PvpToggle` C2S (wanted state), `PvpState` and `PvpPlayers` S2C, each guarded by `canSend`.

## Tests

JUnit for the relation matrix as a pure function (team, run team, duel, effective states, world modes), the cooldown
and combat-tag rules, and `dimensions.json` mode parsing (true/false/"toggle"). In game: two players and a third in a
guild — every matrix cell with melee, arrows, an AOE damage spell, an AOE heal, a direct heal, a Better Combat sweep, a
splash healing and a splash poison potion; the button states; the nameplate icon; spawn (`off`).

## Built (2026-10-19, Eden 1.3.3)

Not run in game yet; `./gradlew build` green (184 tests, 23 new). Where it lives:

- **The matrix**: `combat/PvpRelation` (pure: `of(sameGroup, sameRunTeam, matchA, matchB, onA, onB)`, and the
  action bar `reason`), `combat/PvpMode` (off/toggle/on, `serverDefault`, `effective`, `parse` of
  `true`/`false`/`"toggle"`), `combat/PvpSwitch` (cooldown and combat-tag rules). Tests in
  `src/test/java/net/ledok/eden_ld/combat/`.
- **Server**: `combat/PlayerPvp` — flag and switch time in `HubSettings` (`pvp`, `pvp_switched`; never through the
  Settings tab's `set`), `request` / `adminSet`, `relation` for `CombatTeamsCompat`, the action bar (`attacking`,
  from `PlayerMixin` at `Player#attack` HEAD — vanilla and Better Combat melee), `PvpState` on join, change,
  dimension change, respawn and mode change, `PvpPlayers` at the end of a tick when anything changed (switch,
  join, leave, a duel's start or end via `DuelsHub.changed`). Duels matches got numbers (`DuelsHook.match`) so
  "the same running match" is known; partners are the same scoreboard team without friendly fire.
- **One decision**: `CombatTeamsCompat.resolve` → `Relation(teammates, friendlyFire, ignore)` (`FRIENDS`,
  `ENEMIES`, `IGNORE`); null only without two player sides. Callers unchanged (`HubServer` ALLOW_DAMAGE,
  `PlayerMixin.canHarmPlayer`, `ProjectileMixin`, both mods' matchers) plus `SpellRelationMixin`
  (`EntityRelations.actionAllowed` HEAD, `@Pseudo`, `remap = false`, `require = 0`), `ThrownPotionPvpMixin`
  (`applySplash`: instant effects and `addEffect`) and `AreaEffectCloudPvpMixin` (`tick`: `isAffectedByPotions`
  so the cloud passes over and doesn't shrink, then per effect).
- **World modes**: `DimensionHolder` holds a `PvpMode`; `WorldRules.pvpMode` (own, else `serverDefault`),
  `WorldRules.pvp` for vanilla's switch (off only in an `off` dimension, a duelist excepted), `setPvp` writes
  `true` / `false` / `"toggle"` (on and off stay readable by an older Eden). `/eden pvp <dimension>
  off|toggle|on|reset`, `/eden pvp player <players> [on|off]`; World settings: Server's / Off / Each player's
  choice / On (`WorldAdmin` sends `pvp_mode`, and the old boolean for older windows).
- **Client**: `client/hub/ClientPvp` (state, list, the client's `relation`; an older server → party/guild only,
  as before), `client/hub/InventoryPvpButton` (added by `InventoryHubButton` when the server accepts `PvpToggle`),
  `HubSpotlights.INVENTORY_PVP_BUTTON`, nameplate glyph U+FFF02 (`HubGlyphs.PVP`, font provider on
  `gui/icons/tab_duels.png`) appended in `PlayerRendererMixin`.
- **Deviations**: the action bar has three more lines besides "has PvP off" / "is in a duel" — "Your PvP is off",
  "You're in a duel", "PvP is off here" — for when the reason is the attacker or the world. `/pvp` works for any
  client (an older one just has no button). An admin's change doesn't start or reset the player's own cooldown.
