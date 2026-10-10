# LuckPerms setup

Commands for setting up LuckPerms on the Fallen Eden server: groups, prefixes, and every
permission and meta key that Eden_LD, Economy_LD, Kits_LD and Arenas_LD read. Type them in the server
console without the `/`, or in game with it.

Group names below (`default`, `prime`, `media`, `helper`, `moderator`, `admin`) are examples;
use your own.

## Basics

| Command | What it does |
| --- | --- |
| `/lp editor` | Opens the web editor (the page from your screenshot). Changes apply when you press Save and paste the command it gives you. |
| `/lp listgroups` | All groups with their weights. |
| `/lp group <group> info` | A group's parents, prefix, weight. |
| `/lp user <player> info` | A player's groups and prefix. |
| `/lp user <player> permission check <node>` | Whether a player has a node, and where it comes from. |
| `/lp user <player> meta info` | A player's meta values (the `eden_ld.*` keys below) and where they come from. |
| `/lp sync` | Reloads everything from storage (after editing files or the database by hand). |

Values with spaces go in quotes: `"&c[Admin] "`. Durations: `30d`, `12h`, `1w`, `90m`.

## 1. Groups

```
/lp creategroup prime
/lp creategroup media
/lp creategroup helper
/lp creategroup moderator
/lp creategroup admin
```

`default` already exists; every player is in it.

**Weight** decides the order: which prefix wins when a player is in several groups (see
priority below), and the tab list order (highest first).

```
/lp group default setweight 0
/lp group prime setweight 10
/lp group media setweight 20
/lp group helper setweight 50
/lp group moderator setweight 70
/lp group admin setweight 100
```

**Display name** (shown by LuckPerms and other mods, optional):

```
/lp group prime setdisplayname Prime
```

**Inheritance**: a group gets everything its parents have. A chain means each staff rank has
everything below it:

```
/lp group prime parent add default
/lp group media parent add prime
/lp group helper parent add prime
/lp group moderator parent add helper
/lp group admin parent add moderator
```

## 2. Prefix and suffix

```
/lp group <group> meta setprefix <priority> "<text>"
/lp group <group> meta setsuffix <priority> "<text>"
/lp group <group> meta removeprefix <priority>
/lp group <group> meta removesuffix <priority>
```

The **priority** number picks the winner when a player gets prefixes from several groups (the
highest one shows). Use the group's weight so the two agree.

Formatting in the text:

| Code | Result |
| --- | --- |
| `&0`–`&9`, `&a`–`&f` | the 16 standard colors |
| `&#ff8800` | any hex color |
| `&l` `&o` `&n` `&m` `&k` | bold, italic, underline, strikethrough, obfuscated |
| `&r` | back to plain |
| `<#ff8800>text</#ff8800>` | a hex color, as the TAB plugin writes it |
| `<#ffffff>text</#ffff00>` | a gradient from the first color to the second (TAB's form) |
| `<red>`, `<color:gold>` | a named color |
| `<b>` `<i>` `<u>` `<st>` `<obf>` (or `<bold>` …), `<reset>` | formatting |

Codes and tags can be mixed. A tag ends at its closing tag (`</red>`, `</b>`, or `</>` for the
last one), else at the end of the prefix. Anything that isn't a known tag stays as text. Not
supported: the Spigot form `&x&f&f&8&8&0&0`.

**End the prefix with a space** — nothing is added between it and the name. A suffix starts with
one.

```
/lp group prime meta setprefix 10 "&#e8c070&l[Prime]&r "
/lp group media meta setprefix 20 "&#b070f0[Media]&r "
/lp group helper meta setprefix 50 "&a[Helper]&r "
/lp group moderator meta setprefix 70 "&9[Mod]&r "
/lp group admin meta setprefix 100 "&c&l[Admin]&r "
/lp user LeDok meta setsuffix 100 " &b✔"
```

The prefix shows in chat, the tab list and the hub's player names; a change shows within a
second, no relog.

## 3. Eden_LD meta keys

Meta is a key with a value, set per group (or per player):

```
/lp group <group> meta set <key> <value>
/lp group <group> meta unset <key>
/lp user <player> meta settemp <key> <value> <duration>
```

A player gets the value of their highest-weight group that sets the key; a value set on the
player wins over every group.

| Key | Value | Without it | What it does |
| --- | --- | --- | --- |
| `eden_ld.name_color` | a color: `#ff4040`, `&c`, `gold` | `chat.default_name_color` (`#00c8c9`) | Color of the player's name in chat, the tab list and the hub. The prefix's color doesn't carry into the name. |
| `eden_ld.chat_color` | a color | white | Color of their message text. |
| `eden_ld.silent_join` | `true` / `false` | `false` | `true`: no join/leave messages for them (staff, vanish). |
| `eden_ld.tab_weight` | a number | the group weight | Tab list order (highest first) when it should differ from the weight. |
| `eden_ld.emoji_favorites` | a number | `chat.emoji_favorites` (5) | Favorite emoji slots. Level perks and `/emojis bonus` add on top. |
| `eden_ld.sticker_favorites` | a number | `chat.sticker_favorites` (5) | Saved sticker slots. Level perks and `/emojis bonus` add on top. |
| `eden_ld.daily_free_rerolls` | a number | `dailies.free_rerolls` (1) | Free daily quest rerolls per day. Level perks and `/dailies give-rerolls` add on top. |
| `eden_ld.waystone_cooldown` | seconds | `hub.waystone_cooldown_seconds` (30) | Cooldown between hub waystone teleports; `0` = none. |

Examples:

```
/lp group admin meta set eden_ld.name_color #ff4040
/lp group moderator meta set eden_ld.name_color #5c9dff
/lp group prime meta set eden_ld.name_color #e8c070
/lp group prime meta set eden_ld.emoji_favorites 20
/lp group prime meta set eden_ld.sticker_favorites 20
/lp group prime meta set eden_ld.daily_free_rerolls 3
/lp group prime meta set eden_ld.waystone_cooldown 10
/lp group admin meta set eden_ld.waystone_cooldown 0
lp group media meta set eden_ld.tab_weight 60
lp group prime meta set economy_ld.auction_max_listings 25
lp group default meta set xaero.pac_max_claims 20
lp group default meta set xaero.pac_max_forceloads 1
lp group prime meta set xaero.pac_max_claims 50
lp group prime meta set xaero.pac_max_forceloads 5
/lp group admin meta set eden_ld.silent_join true
/lp group media meta set eden_ld.tab_weight 60
```

## 4. Permission nodes

```
/lp group <group> permission set <node> true
/lp group <group> permission unset <node>
/lp user <player> permission settemp <node> true <duration>
```

`*` at the end covers everything below it: `economy_ld.admin.*`.

### Eden_LD

| Node | Without it | What it gives |
| --- | --- | --- |
| `eden_ld.emoji_picker` | ops only | The emoji picker button next to the chat box. Typing `:name:` works for everyone. |
| your emoji group nodes | — | Emoji groups in `config/eden_ld/emoji.json` with a `"permission"` (e.g. `eden_ld.emoji.cats`): only players with it see and use those emojis. |

Give the picker to everyone:

```
/lp group default permission set eden_ld.emoji_picker true
```

**Admin commands.** Unset, OPs have them (the console always does); set to `true` a group gets
them without OP, set to `false` an OP loses them. Changes apply live.

| Node | Commands |
| --- | --- |
| `eden_ld.admin.themes` | `/hub theme unlock\|lock`, the Moderation window's Customization → Hub themes tab, and the player page's Unlocks tab |
| `eden_ld.admin.backgrounds` | `/hub background unlock\|lock`, the Moderation window's Customization → Backgrounds tab and the Unlocks tab |
| `eden_ld.admin.emojis` | `/emojis reload`, `/emojis bonus …`, the Emojis tab and Server → Reload emojis |
| `eden_ld.admin.dailies` | `/dailies give-rerolls`, `/dailies reset`, and the Dailies tab |
| `eden_ld.admin.dungeons` | `/activities complete\|reset\|level`, and the Activities tab of the Moderation player page |
| `eden_ld.admin.guilds` | `/guild-admin list\|disband`, and the Guilds section |
| `eden_ld.admin.parties` | `/party-admin list\|disband`, and the Parties section |
| `eden_ld.admin.commands` | `/eden commands dump` (every command and the level it needs, to a file), and Server → the op-only command list |
| `eden_ld.admin.mail` | the Moderation window's mail composer, and your own mail's log and recall |
| `eden_ld.admin.mail.log` | everyone's mail in the log, and recalling any of it |
| `eden_ld.admin.news` | the Moderation window's News section (publishing the Home tab's news) |
| `eden_ld.admin.punish.log` | the Moderation window's Punishments log (everyone's BanHammer punishments) |
| `eden_ld.admin.world` | Moderation → World settings, `/eden gamerule`, `/eden pvp`, `/eden difficulty` (a dimension's own gamerules, PvP, difficulty and border; the server-wide gamerules) |
| `eden_ld.admin.*` | all of the above |

```
/lp group admin permission set eden_ld.admin.* true
/lp group moderator permission set eden_ld.admin.guilds true
/lp group moderator permission set eden_ld.admin.parties true
/lp group helper permission set eden_ld.admin.dailies true
```

**Punishing** in the Moderation window's Players section uses BanHammer's own nodes — the same ones
its commands check (`banhammer.punish.ban`, `.tempban`, `.ban-ip`, `.tempban-ip`, `.mute`,
`.tempmute`, `.warn`, `.tempwarn`, `.kick`; `banhammer.unpunish.unban`, `.unban-ip`, `.unmute`,
`.unwarn`, `.pardon`). Anyone with at least one of them gets the Moderation tab with only that
section; a helper who may only mute sees only Mute.

```
/lp group helper permission set banhammer.punish.mute true
/lp group helper permission set banhammer.punish.tempmute true
/lp group helper permission set banhammer.punish.warn true
/lp group moderator permission set banhammer.punish.* true
/lp group moderator permission set banhammer.unpunish.* true
```

### Economy_LD

Same rules as Eden's: unset, OPs have them (the console always does); `true` grants one without
OP, `false` takes it from an OP. `/balance <player>` is open to everyone until you set
`economy_ld.balance.others` to `false`. Command changes apply live.

| Node | What it gives |
| --- | --- |
| `economy_ld.balance.others` | `/balance <player>` (everyone by default) |
| `economy_ld.auction.remote` | `/ah` from anywhere, when `accessMode` is `"block"` (a VIP perk) |
| `economy_ld.admin.give` / `take` / `set` / `balance` / `history` | `/eco` money commands |
| `economy_ld.admin.reload` | `/eco reload` |
| `economy_ld.admin.adminmode` | Admin mode |
| `economy_ld.admin.auction_cancel` | Cancel anyone's auction |
| `economy_ld.admin.auction_limit` | Auction limit bonus commands |
| `economy_ld.admin.shop.place` / `shop.manage` | Place and manage admin shops |
| `economy_ld.admin.*` | all admin nodes |

```
/lp group admin permission set economy_ld.admin.* true
/lp group default permission set economy_ld.balance.others false
/lp group moderator permission set economy_ld.balance.others true
/lp group prime permission set economy_ld.auction.remote true
```

Meta:

| Key | Value | Without it | What it does |
| --- | --- | --- | --- |
| `economy_ld.auction_max_listings` | a number | `auction.defaultMaxListingsPerPlayer` (10) | Active auction listings. `/ah bonus_listing_limit` grants add on top. |

```
/lp group prime meta set economy_ld.auction_max_listings 25
```

### Kits_LD

Without a node set, ops have it.

| Node | What it gives |
| --- | --- |
| `kits_ld.manage` | Every kit admin command and the kit editor |
| `kits_ld.kit.<name>` | Claiming kit `<name>` (and seeing it in `/kits`) |

```
/lp group default permission set kits_ld.kit.starter true
/lp group prime permission set kits_ld.kit.prime true
/lp group admin permission set kits_ld.manage true
```

### Arenas_LD

Meta only, no permission nodes. The perks boost a raid win, a dungeon completion and the mob
arena; per-room dungeon rewards never change.

| Key | Value | Without it | What it does |
| --- | --- | --- | --- |
| `arenas_ld.raid_loot_rolls` | a number, 1–10 | 1 | How many times the raid's per-player loot table rolls; everything is given together. |
| `arenas_ld.dungeon_loot_rolls` | a number, 1–10 | 1 | The same for the dungeon completion loot table. |
| `arenas_ld.arena_loot_rolls` | a number, 1–10 | 1 | Multiplies the player's rolls on every per-player mob-arena wave reward. Shared floor drops don't change. |
| `arenas_ld.currency_multiplier` | a decimal, 0–10 | 1.0 | Currency × this, rounded (`1.1` = +10%), after the hardcore ×2. Raid win, dungeon completion, arena end. |
| `arenas_ld.xp_multiplier` | a decimal, 0–10 | 1.0 | Skill XP × this, rounded, at the same three payouts. |

```
/lp group default meta set arenas_ld.raid_loot_rolls 2
/lp group default meta set arenas_ld.dungeon_loot_rolls 2
/lp group default meta set arenas_ld.arena_loot_rolls 2
/lp group prime meta set arenas_ld.raid_loot_rolls 3
/lp group prime meta set arenas_ld.dungeon_loot_rolls 3
/lp group prime meta set arenas_ld.arena_loot_rolls 3
/lp group prime meta set arenas_ld.currency_multiplier 1.5
/lp group prime meta set arenas_ld.xp_multiplier 1.25
```

A player who logs off before the payout still gets their perks: Arenas_LD saves each player's
values when the run starts and pays offline winners with those (LuckPerms only has online players
loaded).

### Open Parties and Claims

OPAC reads its own meta keys. In the server's `config/openpartiesandclaims-server.toml` set
`permissionSystem = "luck_perms"` (the pack ships `"prometheus"`), and set `maxPlayerClaims` /
`maxPlayerClaimForceloads` to the normal players' values — they apply to anyone without the meta.
Eden's level perk (+5 claims every 10 levels) adds on top of either.

| Key | What it does |
| --- | --- |
| `xaero.pac_max_claims` | Claimed chunks, replaces `maxPlayerClaims` |
| `xaero.pac_max_forceloads` | Forceloaded chunks, replaces `maxPlayerClaimForceloads` |

```
/lp group default meta set xaero.pac_max_claims 20
/lp group default meta set xaero.pac_max_forceloads 1
/lp group prime meta set xaero.pac_max_claims 50
/lp group prime meta set xaero.pac_max_forceloads 5
```

OPAC can only read these while the player is online.

## 5. Players

```
/lp user <player> parent add prime                 add a group
/lp user <player> parent addtemp prime 30d         add a group for 30 days (a bought rank)
/lp user <player> parent remove prime
/lp user <player> parent set admin                 only this group (plus default)
```

**Tracks** (optional) promote along a ladder:

```
/lp createtrack staff
/lp track staff append helper
/lp track staff append moderator
/lp track staff append admin
/lp user <player> promote staff
/lp user <player> demote staff
```

## Import file

[`luckperms/eden-perms-new.json.gz`](luckperms/eden-perms-new.json.gz) holds the whole setup below as a
LuckPerms export (`eden-perms-new.json` next to it is the same, readable). Put it in the server's
`config/luckperms/` and run `lp import eden-perms-new` in the console. Import **merges**: nodes the
server already has stay, so remove old ones by hand. `lp export <name> --without-users` saves the
current setup first.

## A full setup to paste

In the server console (no `/`), one line at a time:

```
lp creategroup prime
lp creategroup media
lp creategroup helper
lp creategroup moderator
lp creategroup admin
lp group prime setweight 10
lp group media setweight 20
lp group helper setweight 50
lp group moderator setweight 70
lp group admin setweight 100
lp group prime parent add default
lp group media parent add prime
lp group helper parent add prime
lp group moderator parent add helper
lp group admin parent add moderator
lp group prime meta setprefix 10 "&#e8c070&l[Prime]&r "
lp group media meta setprefix 20 "&#b070f0[Media]&r "
lp group helper meta setprefix 50 "&a[Helper]&r "
lp group moderator meta setprefix 70 "&9[Mod]&r "
lp group admin meta setprefix 100 "&c&l[Admin]&r "
lp group prime meta set eden_ld.name_color #e8c070
lp group helper meta set eden_ld.name_color #7fd08f
lp group moderator meta set eden_ld.name_color #5c9dff
lp group admin meta set eden_ld.name_color #ff4040
lp group prime meta set eden_ld.emoji_favorites 20
lp group prime meta set eden_ld.sticker_favorites 20
lp group prime meta set eden_ld.daily_free_rerolls 3
lp group prime meta set eden_ld.waystone_cooldown 10
lp group admin meta set eden_ld.waystone_cooldown 0
lp group default meta set arenas_ld.raid_loot_rolls 2
lp group default meta set arenas_ld.dungeon_loot_rolls 2
lp group default meta set arenas_ld.arena_loot_rolls 2
lp group prime meta set arenas_ld.raid_loot_rolls 3
lp group prime meta set arenas_ld.dungeon_loot_rolls 3
lp group prime meta set arenas_ld.arena_loot_rolls 3
lp group prime meta set arenas_ld.currency_multiplier 1.5
lp group prime meta set arenas_ld.xp_multiplier 1.25
lp group default permission set eden_ld.emoji_picker true
lp group default permission set economy_ld.balance.others false
lp group moderator permission set economy_ld.balance.others true
lp group helper permission set banhammer.punish.warn true
lp group helper permission set banhammer.punish.tempmute true
lp group moderator permission set banhammer.* true
lp group admin permission set eden_ld.admin.* true
lp group admin permission set economy_ld.admin.* true
lp group admin permission set kits_ld.manage true
```

In game, put `/` in front of each line.
