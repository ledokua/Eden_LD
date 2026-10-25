# Hub icons

Every icon the hub needs, for the artist (updated 2026-10-25). Until a file exists the hub draws a placeholder (a
colored square or circle, or a text symbol), so icons can be added one at a time.

**Format**: PNG with a transparent background, square, one file per icon. Any power-of-two size
works; the first batch is **64 × 64** and looks good. "Shown at" is the size on screen in hub units:
16 is the same size as an item in an inventory, 8 is half of that. At GUI scale 2–3 a 12-unit icon
is only about 24–36 screen pixels, so fine details (thin lines, small faces) vanish — design for
readability: bold shapes, a dark outline, few colors. Icons larger than they're drawn get smooth
scaling (see Delivered).

**Where**: `assets/eden_ld/textures/gui/icons/<name>.png`. A hub theme may bring its own version of any
icon: `assets/eden_ld/textures/gui/themes/<theme>/icons/<name>.png` (themes today: `parchment`, `dark`,
`stone_tablet`, `overgrown`) is used while that theme is on; a theme without the file keeps the standard
icon, and players can turn theme icons off (Settings → Visual → Theme icons). No theme has its own icons yet.

## Delivered

**2026-10-15 — 10 by Tend1tnuy (16 × 16 pixel art, Discord #dev-text):** `tab_wardrobe`, `tab_maximize`,
`tab_restore` (drawn at 16, pixel-exact), and `favorite_on`, `favorite_off`, `rename`, `invite`, `open_external`,
`tour`, `warning` (drawn at 8, so each has the blur `.mcmeta`). Wired in Eden 1.2.4: the three tab-bar icons, the
waystone stars, `rename` on the Moderation mail draft's rename button (a waystone's rename is in its right-click
menu now), `open_external` in the GIF viewer's title bar, `warning` on Activities cards. Not wired yet: `tour`
(the help window's title-bar buttons take only text — needs a VectorLib change) and `invite` (no envelope button
is left: party invites are on the cards' right-click menu and the *Invite* action).

**2026-10-11 — `tab_kits`:** for now a copy of `tab_duels` (owner's pick), for the Kits tab.

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
| `tab_wardrobe` | Wardrobe (looks to wear, buy and gift) | wardrobe / clothes hanger | ✅ |
| `tab_friends` | Friends | two people | ✅ |
| `tab_party` | Party | three people / crossed swords | ✅ |
| `tab_guild` | Guild | banner / shield | ✅ |
| `tab_waystones` | Waystones | waystone pillar | ✅ |
| `tab_activities` | Activities (dungeons, raids, arenas) | dungeon door / crossed swords | ✅ |
| `tab_duels` | Duels (now a filter inside Activities; kept for a Duels card or button later). Until `pvp` exists it is also the PvP button and the PvP mark above heads (see PvP) | two swords clashing | ✅ |
| `tab_dailies` | Dailies, quests left today | open book | ✅ |
| `tab_dailies_done` | Dailies, all claimed today | book with a check | ✅ |
| `tab_mail` | Mail, everything read | open envelope | ✅ |
| `tab_mail_unread` | Mail, unread mail or a website purchase waiting | sealed envelope | ✅ |
| `tab_kits` | Kits (Kits_LD's kits to claim) | for now a copy of `tab_duels` (owner, 2026-10-11); later a chest / bundle | ✅ |
| `tab_moderation` | Moderation (staff only) | judge's gavel | ✅ |
| `tab_settings` | Settings | gear | ✅ |
| `tab_help` | the "?" button | question mark | ✅ |
| `tab_maximize` | the button that makes the hub fill the window (a □ until drawn) | square / four outward arrows | ✅ |
| `tab_restore` | the same button while maximized, back to the player's size (a ❐ until drawn) | two stacked squares / inward arrows | ✅ |

The tab bar ends with the close button, `close_clear` ✅ (a red X).

## Text icons (2)

Drawn inside text, at the height of a letter (8 units). Kept in their own colors, in the hub and
in chat.

| Name | Where | |
| --- | --- | --- |
| `lc` | after every LC amount ("500" + coin): balance, prices, rewards, the guild bank, chat messages | ✅ |
| `hourglass` | before countdowns and durations: playtime, quest resets, mail deletion, waystone cooldown | ✅ |

## PvP (1, plus 1 optional)

The PvP toggle (Eden 1.3.3): the button in the inventory, right of the hub button, and the mark after a name above
the head of every player whose PvP is on. Both draw `tab_duels` today, so PvP and Duels look the same; a picture of
its own tells them apart. Wiring it is a small code change once the file exists (the button's texture and a font
glyph, U+FFF02, like `lc`).

| Name | Shown at | Where |
| --- | --- | --- |
| `pvp` | 16 × 16 (button), 8 high (mark above heads) | the inventory PvP button — lit while on, dimmed while off, shaded during the 1-minute cooldown — and the nameplate mark (crossed swords, a red edge, a skull…) |
| `pvp_off` | 16 × 16 | optional: the button while PvP is off, if a different picture reads better than the dimmed `pvp` (a sheathed sword, a shield) |

## Buttons (12, plus 2 optional)

Replace the text symbols on small buttons. Shown at **8 × 8** (inside 12–18 unit buttons).

| Name | Replaces | Where |
| --- | --- | --- |
| `close` | ✖ | remove friend, cancel invite, kick, decline, dialogs (delivered, not wired yet) |
| `check` | ✔ | "claimed" on dailies (quest cards and the All missions row) ✅ |
| `accept` | ✔ | accept a friend request, a party invite, a guild invite (may be the same picture as `check`) |
| `reroll` | ↻ | reroll a daily quest |
| `favorite_on` | ★ | waystone in favorites ✅ |
| `favorite_off` | ☆ | waystone not in favorites ✅ |
| `rename` | ✎ | rename a mail draft in Moderation, and an activity in Moderation → Activities (ops; waystones rename from their right-click menu) ✅ |
| `invite` | the envelope button | invite to party — delivered, no button left to put it on (the cards' right-click menu) |
| `open_external` | ↗ | open a GIF in the browser (the GIF viewer's title bar) ✅ |
| `gear` | ⚙ | party settings and guild settings (leaders and officers) |
| `tour` | ▶ | the help window's title bar: starts the open tab's tour — delivered, not wired (needs VectorLib) |
| `sort_ascending` | ↑ | optional: the Activities tab's sort button, lowest first (it was never listed before) |
| `sort_descending` | ↓ | optional: the same button, highest first |
| `warning` | ⚠ | an Activities card a party member's level is too low for (yellow) ✅; Moderation → Activities still shows ⚠ as text (see there) |

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

## Moderation sections (11)

Shown at **12 × 12** on the Moderation window's section buttons (staff only), in the window's order.

| Name | Section |
| --- | --- |
| `mod_mail` | Mail: write mail (quill + envelope) |
| `mod_sent` | Mail log: sent mail and its read / claimed counts |
| `mod_news` | News: the Home tab's news (newspaper / scroll) |
| `mod_customization` | Customization: profile backgrounds and hub themes, their locks and prices (picture frame / palette) |
| `mod_activities` | Activities: which dungeons, raids and arenas the hub shows, their card art and levels (dungeon door with a cog) |
| `mod_players` | Players: the player page — punish, unlocks, dailies, activities, emojis |
| `mod_punish_log` | Punishments: the punishment log (book with a red mark) |
| `mod_guilds` | Guilds: guild admin (disband …) |
| `mod_parties` | Parties: party admin |
| `mod_world` | World settings: gamerules, PvP, difficulty, flight and borders per dimension (globe / portal) |
| `mod_server` | Server: reload, command dump, op-only commands (wrench) |

## Moderation → Activities (2 optional)

Staff only. The list rows end with small markers at 0.8 scale (about 6 units high): ▶ and a count for runs going,
⌛ and a count for instances cooling down, ⚠ for setup warnings, and "Closed" for an activity locked for
maintenance. The Teleport, Admin screen, End run, End all runs and Forget buttons are text buttons and stay text.

| Name | Shown at | Where |
| --- | --- | --- |
| `activity_running` | 8 × 8 | optional: replaces ▶ on a row with runs going (a lit torch, an open door) |
| `closed` | 8 × 8 | optional: a padlock — on Moderation rows instead of "Closed", and on the player hub's dimmed card of a closed activity next to its "Closed" label |

No art needed for the other two: ⌛ can become the delivered `hourglass` and ⚠ the delivered `warning` with a code
change.

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

## Chat (3, optional)

The chat window's hover toolbar, at the right end of the line under the mouse. Shown at **8 × 8**; the text
symbols work too.

| Name | Replaces | Button |
| --- | --- | --- |
| `chat_reply` | ↩ | Reply (someone else's message): `@name` into the chat field |
| `chat_copy` | ⧉ | Copy the message's text |
| `chat_more` | ⋯ | More: the message's menu (Reply, Copy text, Open profile, Moderate for staff) |

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

**Total**: 19 tabs + the tab bar's close + 2 text icons + 1 PvP + 12 buttons + 6 ranks + 7 mail + 11 moderation +
1 guild quest = **60**, plus 17 optional (`pvp_off`, 2 sort arrows, 2 Moderation → Activities markers, 5 guild
sub-tabs, 4 settings sub-tabs, 3 chat) and 11 optional trinket slots.
**Delivered: 31** (all 19 tabs, both close buttons, `check`, `lc`, `hourglass`, and 8 buttons: `favorite_on`,
`favorite_off`, `rename`, `invite`, `open_external`, `tour`, `warning`, plus `close`); `close`, `invite` and `tour`
aren't on screen yet. **Next most visible:** `pvp` (every inventory, and above the head of everyone with PvP on —
today it's the Duels picture), then the rest of the Buttons — `accept` (✔ on every request and invite), `reroll`
(↻ on every daily quest), `gear` (⚙) — then the ranks.
