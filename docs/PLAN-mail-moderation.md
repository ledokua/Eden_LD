# Plan: Mail tab and Moderation window

Status: **approved** (2026-09-30). Phases A–E built (A by the NPCs/VectorLib session: VectorLib 0.2.7;
EMI support needs 0.2.8), plus News and Backgrounds. F built as a player page with tabs
(Punish, Unlocks, Dailies, Dungeons, Emojis) plus Guilds, Parties and Server sections.

## Decisions so far (owner)

- An MMO-style mailbox in the hub replaces Economy_LD's inbox. Mail is stored in Economy_LD's
  database (coins and claims in one place); Eden is the only screen.
- Mail text uses NPCs_LD's Markdown and text editor (toolbar, color picker, placeholders, live
  preview), moved into VectorLib so both mods share one copy.
- One mail can carry several languages; each player reads their own, else the default.
- Expired mail is deleted, unclaimed items included.
- Recipients: players, **all** (also players who join later, until it expires), or one or more
  **LuckPerms groups** — direct members only, resolved when it's sent, among players who have
  joined before.
- No player-to-player mail (auctions and shops cover trading).
- No data migration: Economy_LD's `pending_deliveries` table and inbox screen are removed.
- Staff get a **Moderation** window: functional editor style (like the NPCs_LD editor), EMI
  support, ghost slots for items, per-admin drafts, a log of sent mail, and BanHammer's
  punishments. Sections are gated by permission nodes.

## Phase A — rich text into VectorLib (done, VectorLib 0.2.7)

VectorLib grows a text module next to its rendering (owner approved, 2026-09-30):

- `MarkdownText` (parse / serialize / codec; server-safe), `TextSize` carriers and the run styler
  that draws headings and size spans.
- `TextEditor` + `MarkdownEditing`: the popup editor with its toolbar (bold, italic, underline,
  strikethrough, obfuscated, color picker with the 16 chat colors, headings, sizes) and live
  preview.
- Extension points instead of NPCs-specific code:
  - **Placeholder catalog**: the host mod lists its placeholders (token, label, picker kind); the
    toolbar's {PH} menu shows them.
  - **Preview resolver**: the host mod fills placeholders for the preview.
- NPCs_LD switches to the moved classes with no visible change (its `{t:}`, `{item:}`, `{key:}`,
  `{image:}` stay NPCs_LD's own, plugged in through the extension points).
- `API.md` documents the module.

## Phase B — mail storage in Economy_LD (done)

**Tables** (SQLite, Economy_LD's existing database):

| Table | Holds |
| --- | --- |
| `mail` | id, kind (`reward`, `shop`, `admin`, `system`), sender name, sender uuid (an admin, or none), content (JSON, below), audience (`players` or `all`), created, expires |
| `mail_items` | mail id, item (NBT), count — any number per mail; coins as one `lc` amount on the mail row |
| `mail_status` | mail id, player uuid, read at, claimed at, deleted at — primary key (mail, player) |

- **Content JSON**: `{"default": "en_us", "texts": {"en_us": {"title": "…", "body": "…"}, "uk_ua": {…}}}`
  for admin mail (Markdown); system mail stores a lang key and its values instead, so it's
  translated like any game text.
- **Audience**: for players and groups, a `mail_status` row per recipient is written at send
  time (the group is resolved then). For **all**, rows appear when a player first opens or
  claims it, so players who join later still get it.
- **Claiming**: all items must fit in the inventory, else "Not enough space" and nothing
  changes. The status row is marked claimed before items and coins are handed out, in one
  transaction with the coin deposit, so a claim can never pay twice.
- **Expiry**: a cleanup task deletes expired mail with its items and status rows. The existing
  "expires within a day" warning moves to mail.
- **API** (`EconomyMail`): `send(MailDraft)` — kind, sender, content, items, coins, expiry,
  recipients — returns the mail id; `list(player)`, `markRead`, `claim`, `claimAll`, `delete`,
  `recall(mailId)` (removes it from everyone; claimed items stay claimed); an event when a player gets
  new mail (Eden pushes the tab badge).
- **Senders switched to mail**: Economy's own (auction sold / won / expired / cancelled, offline
  payments, failed coin deposits). Arenas_LD and Duels_LD needed no change: `deliverItem` /
  `deliverCurrency` keep their signatures and now collect one tick's deliveries per player and topic
  (`MailBatcher`) into one mail — a run's loot and coins arrive together.
- The inbox's wallet bar (WITHDRAW / DEPOSIT ALL) went with it; `/withdraw`, `/deposit`, the money
  bag and sneak + right-click deposits remain.
- **Removed**: `pending_deliveries`, `InboxScreen`, the inbox packets and client state, `/ah
  inbox`, the auction block's inbox button.

## Phase C — the Mail tab (Eden_LD) (done)

- New tab `tab_mail` with an unread-count badge (a new notification kind, on by default).
- Layout like the owner's Dead by Daylight screenshots:
  - **Left**: the list, newest first — kind icon, title, date, "deleted in N days", an
    attachment marker, an unread dot. Filters: All / Unread / With items.
  - **Right**: the open mail — title, sender, date, expiry, the text (Markdown, placeholders
    filled in for this player), attachments with **Claim**; below the list, **Claim all** and
    **Delete read**.
- **Language**: all versions come with the mail; the client shows its own language, else the
  default.
- **Placeholders** in mail are filled on the server for each reader when the list is sent
  (they're online then).
- **Shop purchases**: pending Minestar purchases show as mail (kind `shop`); Claim runs the
  delivery. `/shop-receive` stays as a command.

## Phase D — the Moderation window (Eden_LD) (done)

- Opened from a **Moderation** tab in the hub, shown to staff only (any `eden_ld.admin.*` node
  or op). Its own full window in a plain, dense editor style — not the hub theme.
- A plain canvas screen with an EMI screen-bounds provider (EMI's panels show beside it, no menu
  needed): items drag from EMI onto **ghost slots**; the window draws the admin's inventory and a
  click there puts a copy in the next free slot. Attachments are copies; nothing is taken.
- **Mail composer** (node `eden_ld.admin.mail`):
  - **To**: players (search picker, several), **All**, or LuckPerms groups (several) — the
    send button shows how many players it will reach.
  - **Sender name** (default "Administration"), **expiry** (days, up to the config maximum,
    30), **coins**, item slots (config, 12).
  - **Language tabs**: the default plus any added language, each with its own title and text,
    written in the VectorLib text editor.
  - **Placeholders button**: every placeholder that exists on the server — Eden's mail ones
    (`{player}` …) and all Placeholder API ones from every mod (`%player:name%`,
    `%eden_ld:minestar_level%`, …) — each with its value for you as a preview; click to insert.
  - **Live preview** on the right, exactly as the Mail tab will show it, with a language switch.
  - **Drafts**: saved on the server per admin, automatically while typing and when the window
    closes — they survive leaving the server and restarting the game. Several drafts per admin;
    the last one reopens.
- **Mail log** (node `eden_ld.admin.mail.log` to see everyone's; everyone sees their own): each
  sent mail with sender, time, recipients, text, attachments, and how many read and claimed it;
  **Recall**.

## Phase E — BanHammer in the Moderation window (done)

- Built on BanHammer's API (`eu.pb4.banhammer.api.BanHammer`, LGPL-3.0, optional dependency): it
  keeps its database, commands, IP bans, messages and Discord webhooks.
- **Player page**: search any player who has joined; their active punishments; buttons for
  **Ban, Tempban, IP ban, Mute, Tempmute, Warn, Tempwarn, Kick** with a reason and duration
  presets, and **Pardon / Unmute / Unwarn** on active ones.
- **Permissions**: the same nodes BanHammer's commands use (`banhammer.punish.ban`, …), so a
  helper sees only what they may do.
- **Log**: BanHammer has no public way to read its history, so Eden records every punishment
  from its event (commands included) in the moderation log, from the day this is installed.

Built as planned with one change: punishing runs **BanHammer's own commands** as the admin (not
the API), so its protected players, duration limits, messages, warn actions and Discord webhooks
apply and lifting sends its usual messages; replies show as notices in the window. The log reads
BanHammer's own history table (every punishment, commands included, since BanHammer was installed)
instead of recording one in Eden. The Punishments log needs `eden_ld.admin.punish.log`.

## Added on the way (done)

- **News**: the Home tab's news moved from the config into the window (Markdown per language,
  Publish), `eden_ld.admin.news`.
- **Backgrounds**: per-background rules (locked by default, LuckPerms groups, hidden while locked,
  price in LC) replace `profile.free_backgrounds`; players buy priced ones from the picker.
- **Text screen**: mail and news text are written on a screen of their own with the live preview;
  drafts can be named.
- **EMI** shows beside the window (VectorLib 0.2.8 `CanvasHandledScreen.overlays(true)`).

## Phase F — later sections (done)

One per admin command, each behind its node: guilds and parties (list, disband), theme and
background unlocks, dailies (give rerolls, reset), dungeon unlocks, emoji bonuses, the command
dump, and editing the op-only command list.

## Settled details (owner, 2026-09-30)

- The server runs BanHammer **0.11.1**; Eden compiles against that version.
- Mail limits are config options: 12 item slots and a 30-day maximum expiry by default.
- **Recall** removes the mail from everyone, including players who already claimed it (they keep
  what they claimed).
