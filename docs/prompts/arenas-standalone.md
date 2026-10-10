# Task for the Arenas_LD agent: take the hub fields out of Arenas (standalone again)

Arenas_LD (`/home/ledok/Projects/Mods/LeDok/Fabric/Arenas_LD`, MultiLoader common/fabric/neoforge,
Minecraft 1.21.1, Mojang mappings, 4.5.0) got stable ids and `ArenasApi` for Eden_LD's Activities tab
(see `Eden_LD/docs/prompts/arenas-eden-dungeons.md` and your `API.md`). The owner tested it in game:
everything works, but they want **Arenas to be a standalone mod** with nothing hub-specific in it.
Eden_LD now keeps everything hub-related itself (shown or hidden, card art, required levels per
difficulty) in its Moderation window. American English in code, UI and docs; keep en_us and uk_ua in
step. Don't commit unless the owner asks.

## Remove

- **Admin screens:** the **card art** field (next to the id), the **required level** field on each
  difficulty tab, and the arena's single **required level** field. Their lang keys (en_us, uk_ua).
- **Saved data:** stop writing those values (controller NBT and `arenas_ld_activities.dat`). Old saves
  may still contain the keys — just ignore them on load; no migration.
- **API** (`net.ledok.arenas_ld.api.ArenasApi`):
  - `Difficulty(String id, int requiredLevel)` → `Difficulty(String id)`.
  - `Activity(..., int requiredLevel, @Nullable ResourceLocation cardArt)` → drop both components:
    `Activity(ResourceLocation id, ActivityType type, Component name, List<Difficulty> difficulties)`.
  - The activities listener fires on id, name or enabled-difficulty changes (no longer on levels or
    art).

Eden_LD's adapter only calls `id()`, `type()`, `name()`, `difficulties()` and `Difficulty.id()`, so it
keeps working with both 4.5.0 and this version.

## Keep (generic, works without Eden)

- Stable ids, the id field and the arena **name** field in the admin screens, `/arenasld activity …`.
- `activities` / `activity` / `addActivitiesListener`, `openLobbies` (remote lobby screens),
  `registerEntryGate` with every gate check and the lobby screen's locked/dimmed difficulties (the
  reason text comes from whichever mod registered the gate), `addRunListener`.
- Nothing in Arenas_LD references Eden_LD (verify).

## Docs and version

README (admin screen section: no hub fields), API.md (record shapes; say Eden_LD keeps its hub
settings itself), CHANGELOG (the removed fields as a breaking API change), version **4.6.0**.

## Check

`./gradlew build` for all loaders, unit tests and GameTests (update the ones that set required levels
or card art). In a dev run: the admin screens show only the id (and name for arenas); an old save with
the removed keys loads without errors; a test gate still dims a difficulty in the lobby screen.
