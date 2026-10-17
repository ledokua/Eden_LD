# Eden_LD — todo

Open work and ideas. Phase plans live in `docs/PLAN-hub.md`, `docs/PLAN-mail-moderation.md` and
`docs/PLAN-world-rules.md`; the README documents the current behavior, so anything changed below
needs a README pass too.

## Now

- [ ] **Tutorial in Eden** (owner, 2026-10-11; `docs/PLAN-tutorial.md`) — first-join placement into `tutorial_dim`,
      phasing and private containers ported from the old tutorial mod, the busy state locking teleports, party and
      guild, getting items and spending coins; website purchases as Mail entries. Built 2026-10-11 (Eden 1.1.2) and checked in
      the dev client (see the plan's Checked). The owner's spots are in `tutorial.json` (arrival 80.5 161 -88.5
      south, finish -37.5 91 92.5 west in `spawn_dim`). **Copied to the pack, Prism and the live server 2026-10-11
      14:36** (Eden 1.1.2 + live `tutorial.json`; Eden 1.1.1 and tutorial-mod-for-yggdrasil 1.0.0 moved to the
      backups); running on live since the 2026-10-11 15:41 start (4 commands busy-blocked); the guide's cleanup runs `eden tutorial
      finish {player}` last (NPCs_LD session, live with that start). Still to do: natural mob spawning off in
      `tutorial_dim`; a live run-through (finish and skip, the waystone stage, the alchemy table).
  - [x] Busy locks: `BusyLocks` (guild create/invite/accept/apply/join, the guild bank's items/LC/upgrades, daily
        claims and rerolls, Wardrobe buy and gift, website purchases), messages naming the tutorial; config
        `commands.busy_blocked` (`pay`, `withdraw`, `kit`, `kits` — other mods' commands, every subcommand).
  - [x] Website purchases: one Mail row per purchase (name ×quantity, Claim, Claim all), never expiring; the join
        message opens the Mail tab (`/hub mail`).
  - [x] Tutorial state (`tutorial/Tutorial`, `tutorial.json`, `/eden tutorial start|finish|status`), phasing and
        private containers (`tutorial/phase/*`, `mixin/phase/*`, ported from tutorial-mod-for-yggdrasil with its
        bugs fixed: saved inventory persisted, only first-time players, projectiles phased, shared sounds audible,
        private blocks by list), Waystones limited to the tutorial dimension.
  - [x] Live test 2026-10-11 (kombo, l1llight): both put in on first join. The target dummy was invisible (unphased,
        not shared) — `dummmmmmy:target_dummy` added to `always_visible` (Prism + live, /reload); Eden 1.1.3: shared
        entities can be hit by any phase (dev-checked: a shared villager hittable, an unphased zombie not). Copied to
        the pack, Prism and live 2026-10-11 15:09 (1.1.2 to the backups); applies at the next restart.
  - [x] Missing lang keys in the tutorial (owner, 2026-10-11): Realm Keys is server-only, and its unlock title
        (`message.realm_keys.path_opened`, shown when `commands_on_finish` unlocks the realms) and command replies were
        raw keys — Eden 1.1.7 carries its 47 message strings (en + uk, from its jar). Copied to the pack (not
        committed), Prism and live 2026-10-12 00:53 (1.1.6 to the backups); applies at the next restart.
  - [x] Locked blocks (owner, 2026-10-13): `tutorial.locked_blocks` — signs, chiseled bookshelves, trapdoors by
        default; a right-click is refused, the block resent. Eden 1.2.1, dev-tested 2026-10-13: trapdoor stays shut, sign
        editor doesn't open, books neither go into nor come out of a filled shelf, actionbar message; after finishing
        the trapdoor opens again. Same build: the chat's hover area reaches the background's right edge (owner: moving
        right of ⋯ dropped the toolbar) — tested, ⋯ lights and opens up to its right end. Copied to Prism and live
        2026-10-13 11:01 (chat.3 to the backups), pack a274277 (MCP eden's commit); running on live since the 12:02
        restart, live `tutorial.json` got the default list. Breaking isn't covered (right-click only).
  - [ ] World settings → GLOBAL (owner, 2026-10-13): a **Global** tab first with the six rules that are one value
        for the whole server; Gamerules/Modded keep the defaults a dimension can change, and a rule some dimension
        sets names them in gold ("Set differently in: Nether: Off"). Side panels that slide (owner, 2026-10-14):
        Friends (On this server, requests) and Guild without one (create form, invites), `HubDrawer` — the panel
        clipped in a box whose width animates 160 ms, the rest moving with it; one more build once a panel is in.
        Eden 1.2.3, dev-tested 2026-10-14 (Guild all four slides recorded at 60 fps; the Global tab; the gold line
        after setting Nether doFireTick, reset after). Friends can't show in dev (Minestar): same code, check live.
        Committed fd9bf7c; copied to Prism, live (1.2.2 to removed-mods-2026-10-14) and the pack (d4dc303, pushed)
        2026-10-14 15:35; on live from the next restart.
  - [ ] **Chat v2** (owner, 2026-10-17; `docs/PLAN-chat-v2.md`): GardenMyth-like chat in Eden's themes, VectorLib
        RoundedBox (0.7.0) + UiFont (0.8.0), PM tab in the core. **Phase 0 built (Eden 1.2.5)**: `chat/ChatGuard` —
        `ChatChannels.send` (hub party/guild chat, /p /g /l) refuses § and control chars, >256, BanHammer mutes
        (cached off-thread: event-sourced new mutes, two-reads-to-unmute, re-reads), a rate limit
        (`chat.rate_limit_messages` 6 / `rate_limit_seconds` 5); guild name/MOTD, party name, bank tab names, gift notes
        refuse § too (MOTD and gift note not while muted; note Markdown-escaped); /l shows refusals; ChatPatchesMenuMixin
        removed. Reviewed twice by agents (all findings fixed). Dev-tested 2026-10-17: /g normal, § and >256 refused, 6-message
        burst then "too fast", BanHammer mute refused at once on /g and /l, unmute lifted after ~18 s, a 15 s tempmute
        lifted on time, the mute kept across a rejoin, the hub guild panel shows "You are muted."; 1.2.4's icons seen
        (wardrobe tab, restore button, waystone stars). Deployed
        2026-10-17 11:15 with 1.2.4's icons: Prism (1.2.3 to removed-mods-2026-10-17), live (1.2.3 to
        backup/removed-mods-2026-10-17), pack d01210e (pushed); on live from the next restart. BanHammer's
        `muteBlockedCommands` now also lists `p`, `g`, `l` (Prism Server + live; originals in removed-mods-2026-10-17 /
        backup/old-configs-2026-10-17; BanHammer reads it on restart or its reload). Open: hub panels clear the draft
        even when refused (→ chat v2).
        **Phase 1 built (Eden 1.2.6, 2026-10-17)**: chat model apart from drawing — `client/chat/model/` (tab filters,
        rules, cached counters, viewport, two-slot wrap cache, layout, window, toast queue), `ChatView` instances behind
        the static `ChatPanel`, Eden's lines kept across a reconfiguration (proxy switch), the queue-line click asks the
        view, lines wrap again after F3+T. First JUnit tests (34, incl. a 1.2.5 filter equivalence check), all pass.
        Dev-tested 2026-10-17 (LeDok + Bob): heads, ×2, mention stripe and 30 s linger, pips; System view hides chat,
        L pip @1 + dot, "↓ Mentions: 1" jumps to Local and clears; scrolled up holds still, scroll bar blue → red on new
        lines; F3+T; Chat Delay "[+2 pending lines]" click releases the next at once; hover toolbar, right-click menu.
        Not tested: a real proxy switch (unit-tested only). Committed cd4d13f; not deployed.
        **Phase 2 built (VectorLib 0.7.0, 2026-10-17)**: `RoundedBox`/`BoxStyle`/`BoxShadow` on VectorLib's own
        `rounded_box` shader (one quad per box, per-pixel AA, soft shadows, path fallback), `Easing.IN_CUBIC`,
        `CanvasScreen.blurBackground`, per-corner `Path2D.roundRect`, `Flex.backgroundBox`; synced into Eden/NPCs_LD/
        Kits_LD libs. Dev-tested on Fabric (GUI 1/2/4, F3+T, noSdf, broken shader pack) and NeoForge. Still to try:
        an Iris shader pack in Prism Client. Eden doesn't use it yet (phase 4).
  - [ ] "Not a string" ERROR logged on each player login (before "logged in"; another mod reading player data).
        Not seen on Evora's login after the 2026-10-13 12:02 restart.
  - [ ] **Hub tab tours** (owner, 2026-10-11): ▶ in the help window's title bar (VectorLib 0.6.2
        `Window.titleButton`, owner approved) starts the tab's NPCs_LD dialog (`tutorial.tab_tours`, default
        `config:hub/<tab>` for Guild, Waystones, Activities, Mail; `HubTours`, `NpcsCompat`); highlight ids for those
        tabs in `HubSpotlights`. Eden 1.1.5 built and checked in dev (▶ shown, Mail test tour highlighted the list, then
        Claim all). 1.1.5 deployed 2026-10-11 by the NPCs_LD session (Prism, pack 65113f8, live upload by the owner;
        1.1.4 to removed-mods-2026-10-11). The dialogs: NPCs_LD session (the first four written and dev-tested 2026-10-11).
    - [x] Sub-tab tours (owner): `guild/members|recruiting|quests|level|bank` for members, `activities/duels`; ▶
          follows the open sub-tab and only shows when NPCs_LD has the dialog; highlight ids for the guild sub-views
          and Duels. Dev-checked (no ▶ on Chat, ▶ on Bank, a test Bank tour lit the vault). Dialogs: NPCs_LD session;
          all six run in Eden dev 2026-10-11 (every step framed on the right widget, clean end); on live with 1.1.5.
    - [ ] Tours on the other tabs (owner via NPCs_LD session, 2026-10-13): Home, Profile, Wardrobe, Friends, Party,
          Dailies, Settings — default `tab_tours` entries and their highlight ids (`home/card|actions|news`,
          `profile/level|track|playtime` (+`rewards`), `wardrobe/filters|grid|first` (+`categories`),
          `friends/list|search|here|requests` (a folded side panel's bar stands in), `party/invite|members|chat`,
          `dailies/reset|list|reroll|bonus`, `settings/sections|list` — the list through a Spotlight locator, it's
          inside VectorLib's SettingsView). Eden 1.2.2, dev-tested 2026-10-13 with the NPCs_LD dialogs: every line lit
          on its part (Party with and without a party, ▶ on Settings); `profile/track` and Friends need the Minestar
          platform (not in dev). Committed 41a582b; deployed 2026-10-13 12:07 by the NPCs_LD session (Prism, pack
          725e758, live; 1.2.1 to the backups), on live from the next restart.
    - [x] Moderation: the player page's admin tabs keep their scroll (Unlocks no longer jumps to the top); the
          Ukrainian sidebar label "Світ" (the page title stays "Налаштування світу").
    - [x] Stale LC balance (owner: withdrawing from the guild bank left Home/Wardrobe on the old amount): every LC
          `EconomyCompat` gives or takes, and Economy_LD's `COINS_MOVED`, sends `HubPayloads.Balance`; the client
          patches it into its Home and Mail data (Home rebuilds when open). Dev-checked: bank withdraw and deposit,
          then Home and the Mail wallet; a Mail wallet withdrawal, then Home.
  - [ ] **Kits tab** (owner, 2026-10-11; `docs/PLAN-kits.md`): its own hub tab (after Dailies) for Kits_LD's kits —
        cards with status and countdown, Preview (inventory-shaped), Claim (busy-locked), a badge for ready kits;
        `/kits` opens it for Eden clients. Eden 1.1.6 built and dev-checked 2026-10-11 (see the plan's Checked).
        Copied to the pack (not committed), Prism and live 2026-10-11 19:11 (1.1.5 to the backups); applies at
        the next restart. Live has Kits_LD 0.2.0 but **no kits yet** (the tab and the tutorial step show "No kits for you
        yet" until there are). `tab_tours`: Prism's and live `tutorial.json` have none, so 1.1.6's defaults (with
        `kits`) apply — nothing to add. **Wrong** (MCP eden, 2026-10-12): live's file had a list written by 1.1.5 (no
        `kits`), and a list in the file was taken whole — no ▶ on Kits there. Eden 1.1.8: missing default tours join
        the file on load (`""` turns one off). Copied to the pack (not committed), Prism and live
        2026-10-12 16:45 (1.1.7 to the backups; live checked with cmp); applies at the next restart.
    - [x] Tour and tutorial step (owner said yes; NPCs_LD session, tested 2026-10-11 with 1.1.6 and the test kits):
          tour `config:hub/kits` (list → first card → Preview → Claim → tab badge); step `config:tutorial/kits`
          (hub button → Kits tab → first kit → Claim) opened by the guide's `tutorial_cleanup` with `open_dialog` after
          `eden tutorial finish` (also on skip) — no `commands_on_finish` line (`npcs_ld talk` there would close the
          guide's session mid-list). Both dialogs on Prism Server; the guide change waits in
          `Documents/Minestar/FallenEden/NPCs/pending-eden-1.1.6/guide.json` — the NPCs_LD session installs it with
          the 1.1.6 deploy.
    - [x] `tab_kits` icon: a copy of the old Duels icon (owner, 2026-10-11).

- [x] **Chat activity** (owner, 2026-10-12; `docs/PLAN-chat-activity.md`): channel pips instead of the badge (dot =
      unseen, @N = mentions), mention lines highlighted and lingering, "↓ Mentions", right-click menu (mute 5m-24h /
      until on, notifications All / Only @mentions / Nothing, Mark as read, Chat settings), per-channel rows in
      Settings → Chat; a click picks a switched-off channel, Ctrl+Tab skips it. Eden 1.1.9 (2026-10-12), then 1.1.10:
      the input starts right after the pips (owner: the gap after "Guild"), a "!l " prefix lights its pip.
- [x] **Eden's own chat window** (owner, 2026-10-12; `docs/PLAN-custom-chat.md`; decisions: vanilla input, dark
      see-through, fixed like vanilla, pips): built on the `custom-chat` branch in three steps and dev-checked with two
      clients — the store and panel (1000 messages, heads, scroll that holds still), message actions (hover toolbar,
      right-click menu: Reply, Copy text, Open profile, Ignore, Moderate), spam protection (×N, owner), timestamps on
      hover and the System pip (owner's picks). No private-message channel (owner: `/tell NAME`). Eden 1.2.0-chat.3
      copied to Prism, the pack and live 2026-10-12 22:46; running on live since the 2026-10-13 00:01 restart. Merged
      into `master` and the branch deleted 2026-10-13; README updated.
  - [ ] Still open extras (owner to pick): search, inline GIF/sticker previews, reactions, the hub's party/guild chat
        on the same panel.
  - [x] Chat Heads out (owner, 2026-10-13; Eden draws heads): removed from the pack (pack `702e560`, pushed), Prism
        Client and Server (`removed-mods-2026-10-13`) and live (`backup/removed-mods-2026-10-13`, gone since the
        2026-10-13 02:06 restart). ChatPatches was gone already.
  - [ ] Next master build: drop the "-chat" tag (suggested 1.2.1).

- [x] **Hub spotlight ids** (2026-10-10, done by the NPCs_LD + VectorLib session, owner approved): `HubSpotlights`
      registers ids in VectorLib 0.6.0's Spotlight catalog for the NPCs_LD tutorial — `eden_ld:hub/tabs`,
      `eden_ld:hub/tab/<tab>`, `eden_ld:hub/page`, `eden_ld:hub/page/<open tab>`, the ?/maximize/close buttons,
      `eden_ld:hub/help_window`. **Keep them stable** (NPCs_LD dialogs store them; set the same id wherever the tab
      row or content Flex is rebuilt). Eden now needs VectorLib >= 0.6.0 (libs jar updated). Checked in the dev client
      2026-10-10 with NPCs_LD 1.1.0 (the session's `spotlight_test` dialog: tab row lit, then the Mail tab, the
      `part_shown` wait finished on opening Mail; the dialog editor and its text popup open). Copied to the pack, Prism
      and the live server 2026-10-10 15:07 with NPCs_LD 1.1.0 (old NPCs_LD 1.0.0 moved to the backups); running on live
      since the 2026-10-10 16:09 restart — server and pack must update together (dialog packet changed). Dev runs: argFiles point at the
      remapped VectorLib 0.6.0 (`1c2fe20254`).

- [ ] **Discord review picks** (owner, 2026-10-10, via the Discord Eden session):
  - [x] **FTB Teams parties off** — `FtbTeamsCompat`: an FTB Teams party-creation validator refuses new
        parties while `hub.ftb_teams_parties` is false (button greyed with the reason, `/ftbteams party create`
        refused, both naming the hub key); `/ftbteams` op-only in Prism's `commands.json`. Checked in the dev
        client with the pack's FTB Teams (refusal in chat, greyed button + tooltip, `force-disband` of an older
        party by `<name>#<id8>`). Eden 1.1.0 rebuilt 2026-10-10 14:49; copied to the pack, Prism and the live server
        2026-10-10 (with live `commands.json`); running on live since the 2026-10-10 15:53 restart (3 commands op-only).
        The 2 old parties were disbanded on live (owner's decision; both in `world/ftbteams/deleted/`) — by
        `/ftbteams force-disband <name>#<id8>`.
  - [x] **Death rule** (08-12 poll): YIGD `soulboundSlots` = hotbar 0–8 + armor 36–39 in Prism Server's
        `yigd.json`; offhand and trinkets go to the grave. Checked in dev (kept sword/apple/helmet/boots; ingots and
        shield in the grave and back on claiming it). Live since the 2026-10-10 15:53 restart.
  - [x] **No armor wear** (owner: armor only for now): Sensible Survival `equipment_takes_damage_chance` 0 and
        `equipment_damage_multiplier` 0 in Prism Server's `sensible_survival.json` (hits only; Thorns and elytra
        still wear). Weapons unchanged. Live since the 2026-10-10 15:53 restart.

- [x] **Hazards scale with health** (owner, 2026-10-09; `docs/PLAN-hazards.md`, README "Hazards") — max(vanilla,
      share of health × multiplier): players 1, mobs 0.5, bosses 0.1 (`#falleneden:bosses`, new global pack
      `falleneden_bosses`); burning, freezing, lightning, wither and poison another player caused deal half;
      hazards skip armor. Config `hazards.json`, re-read on /reload. Checked in the dev client (see the plan).
      Copied to the pack, Prism and the live server 2026-10-09 23:56 (Eden 1.1.0 + global pack `falleneden_bosses`);
      applies at the next restart.

- [x] **Ignoring players** (owner, 2026-10-09) — `IgnoreList`: Friends tab right-click → Ignore (friends too),
      `/ignore`, `/unignore`, Settings → Privacy → Ignored players. Hides their chat (client side, marked lines;
      deaths and advancements carry the subject now), drops their whispers and `/me` (server), no mention pings,
      party/guild invites refused like the privacy setting, friend requests without notice/reminder/badge, and
      lists put them last among the online (⊘ mark; friends get an IGNORED group before OFFLINE). Anyone can be
      ignored, staff too; they aren't told. Checked in the dev client with two players (chat, mention, whisper,
      `/me`, party invite, death line, Settings list, Stop ignoring brings the lines back). Not checked: the
      Friends tab itself (needs Minestar, not in dev). Copied to the pack, Prism and the live server
      2026-10-09 16:58 (Eden 1.1.0, rebuilt); running on the live server since the 2026-10-09 restart
      (/ignore and /unignore registered). Still to check in game: the Friends tab menu and IGNORED group.

- [x] **EMI stuck on "Reloading..." after a rejoin** (2026-10-07, diagnosed from the owner's client logs) —
      `EmiReloadFix`: EMI's tags/recipes mask is cleared when the client leaves a world, a reload that
      stopped at "World is null" is redone once the world and recipes are there, and `/emireload`.
      Reproduced and checked in the dev client (`dev/emi-rejoin`). The source of the 3 lone recipe syncs
      after each join on the live server is still unknown (not DataRewriter, not Rechiseled).
- [ ] **Trinket slots datapack** (owner, 2026-10-07) — `falleneden_trinkets` global pack (data + slot names):
      every often-used slot in its own spot, backtank/goggles/Tide/belt behind the chestplate/helmet/ring/
      leggings, second ring on; Tide's gadget slot has a placeholder pocket-watch picture. In Prism, the pack
      and on the live server (enabled there since the 2026-10-09 restart; Trinkets' default layout:
      3 next to the player, one column of 4 on the left). If the owner wants
      one tall column instead: an Eden mixin into Trinkets' group placement (`PlayerScreenHandlerMixin`).
  - [x] Belt behind the leggings (owner, 2026-10-07; was the column's 4th slot), and Stylish Effects'
        inventory effects drawn left of the column instead of under it (`StylishEffectsRendererMixin`),
        and EMI's favorites flow around them (`StylishEffectsEmi`; the move put them in EMI's left panel).
        Checked in the dev client (both mods, the pack, 3–4 effects, 79 EMI favorites, the hovers and
        tooltips). Copied to the pack, Prism and the live server 2026-10-07 11:39 (Eden 1.1.0, rebuilt).
- [x] **Hub button in the inventory** (owner, 2026-10-07) — under the crafting grid (`InventoryHubButton`);
      the inventory closes as with E before the hub opens (no EMI behind the hub), and closing a hub opened
      there goes back to the inventory; from the key, back to the game. Tested in the dev client with Not
      Enough Recipe Book (crafting-grid items came back, Moderation and back, the key from the game).
      Copied to the pack, Prism and the live server 2026-10-07 in Eden 1.1.0 (with the EMI rejoin fix).
- [x] **World rules** (owner, 2026-10-05) — per-dimension gamerules, PvP, difficulty and borders in a
      Moderation → World settings section (GLOBAL + one page per dimension); Eden replaces Multi World
      Borders. All five phases of `docs/PLAN-world-rules.md` done 2026-10-05: gamerule layer
      (`dimensions.json`, `/eden gamerule`), `/eden pvp` and `/eden difficulty`, per-dimension borders
      (`/worldborder` per dimension), Moderation → World settings, and the deploy. The jar is in the pack,
      Prism and the live server, with the Nether's 20,000 border in the server's `dimensions.json`. It
      applies at the next restart.
  - [x] After the restart (checked 2026-10-06 on the live server): the Nether 20,000, the Overworld 30,000
        (set after its reset), both kept in `dimensions.json`; other dimensions without a border.
  - [ ] Try a spell between two players in a dimension with PvP off.
- [x] **VectorLib `NumberOption`** reported every keystroke (via `NumberField.onValue`) though its docs say it
      commits on Enter or leaving the field — typing 15000 sent 1, 15, 150…. Fixed in VectorLib 0.5.1 (2026-10-05,
      owner approved): `NumberField.onCommit` (Enter, leaving the field, a scroll/arrow step; `onValue` stays live)
      and `NumberOption` uses it. Eden's World settings use number boxes since (gamerule numbers, border safe zone
      and warnings); number boxes and sliders on settings pages step with the wheel only once clicked into.
- [x] **VectorLib 0.5.2** (owner approved, 2026-10-07): World settings' search box ran past the Moderation window
      on dimension pages with a Modded tab (4 tabs + the box don't fit in 304 units) — it now drops to its own row
      under the tabs; and search results with only short rows were narrow — rows now always take the full width.
      Checked in the dev client (a Modded tab via Puffish Skills' gamerule). Ships inside Eden (jar-in-jar):
      Eden 1.1.0 rebuilt with it (12:01); copied to the pack, Prism and the live server 2026-10-07 12:36.
- [x] **Saint's Dragons crash fix** (2026-10-05, asked by the world-rework session, owner approved): its
      dragon density check queried the live world during chunk generation on C2ME workers and crashed
      the server; `SaintsDragonsSpawnRulesMixin` skips it while chunks generate and off the server thread.
      Checked on a production-style test server (patched method seen, dragon biomes generated cleanly).
      Copied to the pack, Prism and the live server 2026-10-05 19:06 (with phase 1); applies at the next
      restart. Worth reporting upstream.
- [x] **Arenas standalone** — Arenas_LD 4.6.0 (2026-10-01): no card art or levels in Arenas; Eden
      keeps them in Moderation → Activities and compiles against the 4.6.0 jar.
- [ ] Set `activities.level_category` once the skill tree exists (owner builds the tree first).
- [ ] **Owner's picks 2026-10-03** (from the improvement list):
  - [x] **Shop tab** (built as the **Wardrobe** tab, 2026-10-03) for looks (backgrounds, hub themes) with previews, owned/for-sale, buy and equip —
        built to take more categories later (owner plans character cosmetics: armor, tools, hats,
        accessories).
  - [x] **Gifting** a background or theme to another player (paid by the giver, a mail tells them).
  - [x] **Taller tabs** so the 16 × 16 icons draw at 16 units (pixel-exact).
  - [x] **Duels alerts** outside the hub: sound + message on "match found", Home card while searching.
  - [x] **Activities**: warn when a partymate is below an activity's level (no other clutter).
  - [x] **Theme icon sets**, optional: a theme may ship its own icons; players can turn them off.
  - [x] **Old Yggdrasil quest textures** (25 MB) out of the jar into a pack resource pack (Global Packs).
- Waiting (owner, 2026-10-03): Arenas without the lobby block — decided to wait.
- [x] **Duels tab** — built 2026-10-01, passed the owner's playtest (Ukrainian column widths fixed after).
- [x] **Hub critique round** (2026-10-04, screenshots of every tab × 3 themes × 2 languages on a
      3-player dev server; owner's picks): active sub-tab color (`button_active`), language-aware
      dates, icon-only fixed-width tabs + page titles, Overgrown no longer shrinks, Duels/Party/Guild/
      Wardrobe/Mail/Help fixes, empty states, wording; plus the Friends declutter (face + name opens
      the profile, Remove friend in the right-click menu), plain name on the profile, "Minestar
      activity level", party names translated.
  - [ ] Later (owner): Wardrobe tab icon art; mail kind icons instead of colored squares; rank icons
        for Party/Guild (owner draws them, then one shared icon set).
  - [x] Temporary screenshot tools taken out of the source (2026-10-04); kept in `dev/hub-tour/`
        with how to restore and run them for the next screenshot round.
- [x] **Hub round 2** (2026-10-04, owner feedback): profile without the Change background /
      My profile buttons (backgrounds in the Wardrobe; the bookmark leads home), a plate under the
      profile name on pictures, no theme shadow on picture cards, Steam-style friend cards (status
      stripe, colors, right-click menu, hover mini profile), Waystones rename/original/favorite in a
      right-click menu, Duels folded into Activities as a filter, centered page headings.
- [x] **Resizable hub** (2026-10-04, VectorLib 0.3.0 phase 3): drag the sides/bottom or maximize;
      every tab builds at the hub's width (more cards a row, wider lists and chat); dividers between
      Guild/Party members | chat, Mail list | letter, Waystones favorites | all; Help and the bank
      Log are windows (move, resize, stay open). The bank's open-tab status moved to the left column
      so vault + inventory + hotbar fit the smallest hub (now 440 × 262).
- [x] **Settings pages** (2026-10-04, VectorLib 0.4.0 phase 4): Settings tab declared as a VectorLib
      settings page (search across sub-tabs, reset-to-default arrows); Guild and Party settings dialogs
      as stacked forms (fields save with their button or Enter).
- [x] **Folding lists + drag and drop** (2026-10-04, VectorLib 0.5.0 phase 5): Friends and Waystones sections
      fold (kept in `hub_folds.json`); Waystones favorites dragged into a per-player order (server-side,
      new `waystone_order` payload), dragged in from / out to the right column; guild bank stacks dragged
      between the inventory and the vault. VectorLib also has `TreeView` (not used by the hub yet).

## Handed off

- [x] **Duels_LD onto Eden's parties** (prompt `docs/prompts/duels-eden-parties.md`) — done by the
      Duels_LD agent 2026-10-01 (Duels 1.3.0, `integration/EdenBridge`); passed the owner's in-game
      checks 2026-10-01.
- [ ] **Icon art** — 20 of 53 delivered (the tabs but Wardrobe and maximize/restore, close, check, coin,
      hourglass; the 16 × 16 set redrawn 2026-10-05, with a sealed envelope for unread mail). Next:
      small buttons (reroll, favorites, rename, invite), ranks, mail, Moderation sections. List: `docs/ICONS.md`.

## Considering

- [x] **Rich hub themes** — built 2026-10-02 (`background`, `rows`, `decorations`, `tile_edges`);
      example `eden_ld:overgrown` — owner: good enough (2026-10-03).
- [ ] **Smoother font** — parked (2026-10-01): keep the Minecraft font. If it comes back: an opt-in
      VectorLib smooth-text mode (bundled OFL font drawn at the real pixel size), measured through one
      VectorLib helper so switching is cheap; plus pixel-snapping the hub's shrink and no text below 1×.

## Not important

- [ ] **Shared mob-kill XP in parties** (owner, 2026-09-28): split among partymates within
      `hub.party_xp_radius` with `hub.party_xp_bonus_percent` (config keys exist, unused); needs a
      `LivingEntity#dropExperience` redirect of `ExperienceOrb.award`.
- [ ] ~~Discord lines styled like in-game chat~~ — dropped (Minestar: too complex, not needed; 2026-10-03). `[Discord]`
      lines show the sender's LuckPerms prefix (role), name color, and guild tag when they turned it on —
      the same as their in-game chat lines.
      **Blocked on Minestar:** the chat bridge gives the server only `author` and `content` as plain
      strings, so Eden can't tell a linked account from someone on Discord using a player's name.
      Matching by name would let anyone show up as `[Admin]` in red. Ask the Minestar team to include
      the author's linked Minecraft profile UUID (e.g. `authorProfileUuid`, empty when not linked) and
      pass it to the server mod. Then: look the player up by UUID (LuckPerms user loaded async when
      offline, guild + `HubSettings.showGuildTag`) and restyle the line in
      `ChatChannels.onGameMessage` before `announceDiscord`; unlinked authors keep today's look.
