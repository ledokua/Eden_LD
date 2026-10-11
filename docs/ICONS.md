# Hub icons

Every icon the hub needs, for the artist. Until a file exists the hub draws a placeholder (a
colored square or circle, or a text symbol), so icons can be added one at a time.

**Format**: PNG with a transparent background, square, one file per icon. Any power-of-two size
works; the first batch is **64 × 64** and looks good. "Shown at" is the size on screen in hub units:
16 is the same size as an item in an inventory, 8 is half of that. At GUI scale 2–3 a 12-unit icon
is only about 24–36 screen pixels, so fine details (thin lines, small faces) vanish — design for
readability: bold shapes, a dark outline, few colors. Icons larger than they're drawn get smooth
scaling (see Delivered).

**Where**: `assets/eden_ld/textures/gui/icons/<name>.png`. Planned: a theme may override any icon
with its own file in `textures/gui/themes/<theme>/icons/<name>.png` (for a dark theme, a stone
theme, …).

## Delivered

**2026-10-05 — the 16 × 16 set, redrawn:** cleaner outlines and flat colors for all 16 (the tab
icons, `close_clear`, `check`), plus `tab_mail_unread` — a sealed envelope while unread mail waits;
`tab_mail` is now the open envelope shown once everything is read. The replaced files are kept in
`dev/replaced-icons-2026-10-05/`.

**2026-10-03 — the 16 × 16 set** (replaced the first 64 × 64 tab icons; marked ✅ below):
all 14 tab icons (`tab_home`, `tab_profile`, `tab_friends`, `tab_party`, `tab_guild`,
`tab_waystones`, `tab_activities`, `tab_duels`, `tab_dailies` + `tab_dailies_done`, `tab_mail`,
`tab_moderation`, `tab_settings`, `tab_help`), `close_clear` (the tab bar's close X) and `check`
(the claimed mark on dailies). Pixel art at 16 × 16 is drawn without smoothing.

**2026-10-01 — first batch, still in use:** `lc` and `hourglass` (64 × 64, also text characters —
`assets/minecraft/font/default.json` and `uniform.json`, U+FFF00 / U+FFF01, `client/HubGlyphs`;
the coin follows every amount, the hourglass precedes countdowns; the font copies are trimmed and
shrunk to 24 px), and the framed `close` (unused).

- **Smooth scaling:** an icon drawn *smaller* than its file (the 64 × 64 ones) has a
  `<name>.png.mcmeta` with `{"texture": {"blur": true}}`, so shrinking blends pixels; icons drawn
  *bigger* than their file (the 16 × 16 set) have none — blur would make them soft.

## Tabs (19)

Shown at **16 × 16** in the tab bar, pixel-exact (every tab is the icon alone; the open tab's name is
the page title). An empty tab shows its icon at 32 × 32.

| Name | Tab | Idea | |
| --- | --- | --- | --- |
| `tab_home` | Home (also the inventory's hub button) | house | ✅ |
| `tab_profile` | Profile | person bust / ID card | ✅ |
| `tab_wardrobe` | Wardrobe (looks to wear, buy and gift) | wardrobe / clothes hanger | |
| `tab_friends` | Friends | two people | ✅ |
| `tab_party` | Party | three people / crossed swords | ✅ |
| `tab_guild` | Guild | banner / shield | ✅ |
| `tab_waystones` | Waystones | waystone pillar | ✅ |
| `tab_activities` | Activities (dungeons, raids, arenas) | dungeon door / crossed swords | ✅ |
| `tab_duels` | Duels (now a filter inside Activities; kept for a Duels card or button later) | two swords clashing | ✅ |
| `tab_dailies` | Dailies, quests left today | open book | ✅ |
| `tab_dailies_done` | Dailies, all claimed today | book with a check | ✅ |
| `tab_mail` | Mail, everything read | open envelope | ✅ |
| `tab_mail_unread` | Mail, unread mail or a website purchase waiting | sealed envelope | ✅ |
| `tab_kits` | Kits (Kits_LD's kits to claim) | for now a copy of `tab_duels` (owner, 2026-10-11); later a chest / bundle | ✅ |
| `tab_moderation` | Moderation (staff only) | judge's gavel | ✅ |
| `tab_settings` | Settings | gear | ✅ |
| `tab_help` | the "?" button | question mark | ✅ |
| `tab_maximize` | the button that makes the hub fill the window (a □ until drawn) | square / four outward arrows | |
| `tab_restore` | the same button while maximized, back to the player's size (a ❐ until drawn) | two stacked squares / inward arrows | |

The tab bar ends with the close button, `close_clear` ✅ (a red X).

## Text icons (2)

Drawn inside text, at the height of a letter (8 units). Kept in their own colors, in the hub and
in chat.

| Name | Where | |
| --- | --- | --- |
| `lc` | after every LC amount ("500" + coin): balance, prices, rewards, the guild bank, chat messages | ✅ |
| `hourglass` | before countdowns and durations: playtime, quest resets, mail deletion, waystone cooldown | ✅ |

## Buttons (8)

Replace the text symbols on small buttons. Shown at **8 × 8** (inside 12–18 unit buttons).

| Name | Replaces | Where |
| --- | --- | --- |
| `close` | ✖ | remove friend, cancel invite, kick, dialogs (delivered, not wired yet) |
| `check` | ✔ | "claimed" on dailies (quest cards and the All missions row) ✅ |
| `reroll` | ↻ | reroll a daily quest |
| `favorite_on` | ★ | waystone in favorites |
| `favorite_off` | ☆ | waystone not in favorites |
| `rename` | ✎ | rename a waystone |
| `invite` | the envelope button | invite to party (Friends cards) |
| `open_external` | ↗ | open a GIF in the browser |

## Ranks (6)

Shown at **8 × 8** before names in member lists (replace ♛ ♚ ★ ·).

| Name | Rank |
| --- | --- |
| `rank_party_leader` | party leader |
| `rank_party_vice` | party vice-leader |
| `rank_guild_leader` | guild leader |
| `rank_guild_vice` | guild vice-leader |
| `rank_guild_officer` | guild officer |
| `rank_guild_recruit` | guild recruit (members have no mark) |

## Mail (7)

| Name | Shown at | Where |
| --- | --- | --- |
| `mail_reward` | 16 × 16 | a reward (dungeon, raid, duel, quest) in the mail list |
| `mail_shop` | 16 × 16 | a website shop purchase |
| `mail_admin` | 16 × 16 | a message from the administration |
| `mail_system` | 16 × 16 | auction and payment results |
| `mail_attachment` | 8 × 8 | "has items or coins" marker on a list row (paperclip / pouch) |
| `mail_claimed` | 8 × 8 | attachments already taken (open pouch / check) |
| `mail_expiring` | 8 × 8 | expires within a day (the delivered `hourglass` can be reused) |

The unread dot is drawn in the notification color, no file needed.

## Moderation sections (10)

Shown at **12 × 12** on the Moderation window's section buttons (staff only).

| Name | Section |
| --- | --- |
| `mod_mail` | write mail (quill + envelope) |
| `mod_sent` | mail log: sent mail and its read / claimed counts |
| `mod_news` | Home news (newspaper / scroll) |
| `mod_backgrounds` | profile background rules (picture frame) |
| `mod_activities` | Activities: which dungeons, raids and arenas the hub shows, their card art and levels (dungeon door with a cog) |
| `mod_players` | player page: punish, unlocks, dailies, activities, emojis |
| `mod_punish_log` | punishment log (book with a red mark) |
| `mod_guilds` | guild admin (disband …) |
| `mod_parties` | party admin |
| `mod_server` | server tools: reload, command dump, op-only commands (wrench) |

## Guild sub-tabs (5, optional)

Shown at **8 × 8** before the sub-tab name in the Guild tab header; the text alone works too.

| Name | Sub-tab |
| --- | --- |
| `guild_members` | Members (roster + chat) |
| `guild_recruiting` | Recruiting (officers: invites, applications) — person with a plus |
| `guild_quests` | Quests (scroll with a sword) |
| `guild_level` | Level (star / rising arrow) |
| `guild_bank` | Bank (chest / coin stack) |

## Guild quests (1)

| Name | Shown at | Where |
| --- | --- | --- |
| `guild_mailed` | 8 × 8 | "items are mailed to every member" marker on a quest card (replaces ✉) |

## Settings sub-tabs (4, optional)

Shown at **8 × 8** before the sub-tab name; the text alone works too.

| Name | Sub-tab |
| --- | --- |
| `settings_privacy` | Privacy (lock / eye) |
| `settings_chat` | Chat (speech bubble) |
| `settings_notifications` | Notifications (bell) |
| `settings_visual` | Visual (brush / palette) |

## Trinket slots (11, optional)

Not hub icons: the empty-slot pictures of the inventory's trinket slots, set up by the
`falleneden_trinkets` datapack (pack repo `global_packs/required_data/falleneden_trinkets`, 2026-10-07).
Each slot uses another mod's picture today, so all of these are optional. The **Tide gadget** had the
ring's picture (hovering the ring showed two rings); it now has a placeholder traced from Tide's pocket
watch (already in place at the path below — a drawn one simply replaces the file).

**Format**: **16 × 16**, drawn like vanilla's empty armor slots — a light gray outline on a
transparent background, no smoothing. **Where**:
`global_packs/required_resources/falleneden_trinkets/assets/falleneden/textures/gui/slots/<name>.png`;
the slot's `icon` in the datapack then becomes `falleneden:gui/slots/<name>` (switched once the file exists —
a missing file shows the purple-and-black texture).

| Name | Slot | Where it shows | Picture today |
| --- | --- | --- | --- |
| `spell_book` | Spell book | next to the player | Spell Engine's book |
| `necklace` | Necklace | next to the player | Trinkets' necklace |
| `ring` | Ring (first) | next to the player | Trinkets' ring |
| `relic` | Relic (the spell trinkets: relics, Loot n Explore) | column left of the inventory | Spell Engine's trinket |
| `quiver` | Quiver / rune pouch | left column | Spell Engine's quiver |
| `totem` | Totem (charm) | left column | Trinkets' charm |
| `ring_second` | Ring (second) | left column | Trinkets' ring |
| `tide_gadget` | Tide's fishing gadgets (fish finder, depth meter, …) | hovering the first ring | placeholder traced from Tide's pocket watch (2026-10-07) |
| `goggles` | Face (Create goggles) | hovering the helmet | Trinkets' face |
| `backtank` | Back (Create backtank) | hovering the chestplate | Trinkets' back |
| `belt` | Belt (Tom's Storage wireless terminal) | hovering the leggings | Trinkets' belt |

**Total**: 18 tabs + close + 2 text icons + 8 buttons + 6 ranks + 7 mail + 10 moderation + 1 guild
quest = **53**, plus 9 optional (5 guild sub-tabs, 4 settings sub-tabs) and 11 optional trinket slots. **Delivered: 20** (15 of 18
tabs — all but `tab_wardrobe`, `tab_maximize` and `tab_restore` — both close buttons, `check`, `lc`,
`hourglass`). **Next most visible:** the Buttons (reroll,
favorites, rename, invite…), then ranks.
