# Plan: hazards scale with health

**Status (2026-10-09): built, checked in the dev client** (see Build) **and copied to the pack, Prism and the live server** (applies at the next restart).

Owner's request (2026-10-09): environmental damage (fire, lava, poison, wither, freezing, …) deals at
least its vanilla damage, and otherwise a share of the target's max, current or missing health — so it
stays dangerous from a fresh 20 HP player to a late-game one.

## Why

Max health spans about ×25 on Fallen Eden (numbers from the Eden Loot session, 2026-10-09): armor gives
nearly all of it — Dungeon Difficulty adds max health to every armor piece by tier and item level, robes
too, so a glass cannon is only 20–30 HP below a tank.

| Stage | Max HP today (everyone at loot stage 1) | Once loot stages follow the skill tree |
| --- | --- | --- |
| Fresh | 20 (28 with a food buff) | 20 |
| Early (T1/T2) | 25–60 | 25–60 |
| Mid | 60–120 | 180–220 |
| Late | 200–230 | ~500 (~770 if Limit Breaker's +60% scales the armor HP) |

Vanilla hazards are flat (lava 4 a hit, burning 1 a second), so they stop mattering after the first sets.

## The rule

**damage = max(vanilla damage, share of health × target multiplier)**

- **Share of health**, per hazard: of **max** health (equally deadly for everyone), of **current** health
  (never kills by itself), of **missing** health (worse the weaker you are), or **per point** of vanilla
  damage (falls: vanilla counts the blocks, the share is per block).
- **Target multiplier** (owner's choice): **players 1**, **mobs 0.5**, **bosses 0.1** (`#falleneden:bosses`,
  the pack's global pack `falleneden_bosses`: every boss with a boss bar, the Warden and Elder Guardian, and
  `#c:bosses`; without the pack, `#c:bosses`). Lava traps and fire can't melt a boss by percent.
- **Vanilla as the floor**: a fresh player takes exactly vanilla damage; the share takes over at about
  70–100 max HP (lava at 80, burning at 67, drowning at 40).

### Values (player multiplier; mobs ×0.5, bosses ×0.1)

| Hazard (damage type) | Vanilla | Rule | At 500 HP |
| --- | --- | --- | --- |
| Lava (`lava`) | 4, twice a second | 5% max | dead in ~10 s |
| Fire block, campfire, magma (`in_fire`, `campfire`, `hot_floor`) | 1 (soul fire 2), twice a second | 1.5% max | ~3%/s |
| Burning (`on_fire`) | 1 a second | 1.5% max | a 15 s burn ≈ 110 HP |
| Burning lit by another player | 1 a second | half the share (see below) | |
| Freezing in powder snow (`freeze`) | 1 every 2 s | 3% max | |
| Drowning (`drown`) | 2 a second | 5% max | 20 s without air |
| Suffocating, cramming (`in_wall`, `cramming`) | 1 twice a second | 4% max | |
| Starving (`starve`) | 1 every 4 s | 4% max | |
| Void (`out_of_world`) | 4 a tick | 10% max | |
| World border (`outside_border`) | per block outside | 1% max per point | |
| Cactus, berry bush (`cactus`, `sweet_berry_bush`) | 1 | 1% max | |
| Lightning (`lightning_bolt`) | 5 | 15% max | |
| Falling, elytra crash, dripstone (`fall`, `fly_into_wall`, `stalagmite`) | 1 per block past 3 | 3.5% max per block | ~30 blocks kill |
| Poison effect | 1 every 25 ticks (II: 12, …) | 4% current, still can't kill | 10 s ≈ 140 HP |
| Wither effect (`wither`) | 1 every 40 ticks (II: 20, …) | 1% max + 4% missing | |
| Modded terrain: BoP bramble and fumarole, FD stove, Frights berry bushes, Ube kalan, YUNG prickly vines, Create fan fire/lava | 1–2 | 1–1.5% max (fire ones as fire, the lava fan as lava) | |

Not hazards, left vanilla: mob and player attacks, explosions, falling anvils and blocks, dragon breath,
the warden's boom, harming potions, and class or mob skill damage over time (More RPG Classes' bleeding,
fatal poison and molten, Soulslike's bleed, Mine Cells' bleeding) — those are attacks.

### Player against player (owner, 2026-10-09)

**Burning, freezing, lightning, wither and poison** another player caused deal **half** their share to a
player (`pvp: 0.5`, rules with `"pvp": true`). Vanilla doesn't record who caused most of these, so:

- **Poison, wither**: the player who gave the effect (`addEffect`'s source: themselves, or the owner of the
  arrow, potion or cloud) — until the effect is given again by someone else.
- **Lightning**: the player whose Channeling trident called the bolt.
- **Burning**: set alight while another player hit them in the last 5 seconds (Fire Aspect, Flame and fire
  spells all hit) — until the fire goes out; a fire started later is judged again.
- **Freezing**: while another player hit them in the last 5 seconds.

## What still protects

- **Armor doesn't**: every damage type with a rule skips armor (in code, so it follows the config and the
  switch) and doesn't wear it down, so a 90%-armor tank can't sit in lava.
- **Protection enchantments do**, as in vanilla (4% per point, at most 80%): Protection for all of them,
  Fire Protection for fire and lava, Feather Falling for falls, Respiration for drowning time.
- **Effects**: Fire Resistance (immune to fire and lava), Resistance (5% per level, Resistance Controller),
  Slow Falling, Water Breathing; leather armor against freezing.
- Totems still save.

## Build

1. ✅ **Config** `config/eden_ld/hazards.json`: switch, multipliers (player, mob, boss, pvp), the PvP window,
   one rule per damage type (`max_percent`, `current_percent`, `missing_percent`, `per_point_percent`, `pvp`,
   `lethal`), and the poison rule; re-read on `/reload`.
2. ✅ **Hooks** (`world/Hazards`): `LivingEntity.hurt` scales the incoming amount; `getDamageAfterArmorAbsorb`
   skips armor for rule types; poison is wrapped at the effect (its damage type, `minecraft:magic`, is shared
   with harming potions), clamped to leave 1 health; the PvP facts are kept on the entity.
3. ✅ **Bosses**: `#falleneden:bosses` in the pack's global pack `falleneden_bosses`.
4. ✅ **Docs**: README "Hazards", todo.
5. ✅ **Checked in the dev client (2026-10-09)**, 500 max health unless said: lava 25 a hit, the same in
   full netherite; burning 7.5 a second; poison 4% of current a tick (500 → 480 → 460.8); wither 1% + 4% of
   missing (8.01, 8.33); a 20-block fall 280 (16 points × 3.5%); at 20 health lava 4 and a 10-block fall 7
   (vanilla); a 1000-health zombie 25 a hit, an Elder Guardian (boss) at 1000 health 5; burning started in a
   fight with another player 3.75 a second, still after the 5 seconds; poison from another player's splash
   potion 2% of current; lightning 75 a flash; `/reload` picking up a changed share. Not checked: a Channeling
   trident (lightning by a player), freezing, drowning.

Later, if wanted: per-dimension overrides (World settings), a Hazard Resistance stat on the same curve as
armor, more modded damage types.
