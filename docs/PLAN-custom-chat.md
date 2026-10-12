# Eden chat panel (our own chat instead of patching Minecraft's)

The owner (2026-10-12), after the channel pips (`PLAN-chat-activity.md`, Eden 1.1.9): "it would be better for us to
make our custom chat instead of modifying minecraft one". ChatPatches won't be needed — what we want from it, we
build in.

## Why

Everything since the channels has been bolted onto vanilla's chat window, which keeps one shared list of at most
100 messages, wrapped into lines it rebuilds on every change: channel filtering re-lays the whole window, "seen"
and "jump to a mention" track line objects by identity, the mention look rides on a message tag meant for "Not
Secure", and every new control (pips, the menu, the emoji picker, the GIF viewer) is drawn by hand inside mixins.
Each next feature (reply, reactions, per-channel scrollback, inline images) would cost more than the last.

**Branch**: the work happens on its own git branch, `custom-chat`, off `master` (owner, 2026-10-12); `master` keeps the
1.1.9 pips (`PLAN-chat-activity.md`) as the chat players use until the panel is ready.

## Proposal: a hybrid

- **Keep vanilla's input line** — typing, command suggestions and their tooltips, ↑/↓ history, signed chat (reports),
  the narrator. They're fiddly, other mods hook them, and they work. The `!g`/`!l` prefixes, `:emoji:` as you type
  and the emoji button stay as they are around it.
- **Replace the message area** (open chat and the HUD lines) with Eden's own panel, built on VectorLib, only on Eden
  servers; other servers keep vanilla's chat untouched.
- **Feed it from vanilla's chat**: every line still enters `ChatComponent.addMessage` (other mods, the narrator, the
  log, chat reports keep working); Eden mirrors it into its own store, sorted by `ChatMarker` (channel, sender,
  kind) — the panel draws from the store, vanilla's window isn't drawn.

## What the store and panel give

- **Per-channel histories** (each its own, longer than 100), an "All" view, and switching that is instant (no
  re-layout of one shared list).
- **Unseen and mentions** as plain per-channel counters and "last read" markers (today's 1.1.9 rules: mute,
  notifications, Mark as read, ↓ Mentions, the highlight) — no line identity tricks.
- **Real widgets**: VectorLib scrolling, popups and context menus (submenus would be one addition to VectorLib's
  ContextMenu — needs the owner's OK), tooltips, the theme.
- **Message actions** on hover or right-click: copy text, reply (quotes the line, mentions the author), open the
  author's profile in the hub, ignore, and for staff the BanHammer actions (mute, warn) from the Moderation window.

## Phase 1 as built (branch `custom-chat`, Eden 1.2.0-chat.1)

- `client/chat/panel/ChatStore` — every message since joining (1000; vanilla keeps 100), each a `ChatEntry` with its
  marker read once (channel, sender, kind), whether it mentions the player, whether the open chat has shown it, and
  its lines wrapped for the current width (cached).
- `client/chat/panel/ChatPanel` — draws instead of vanilla's window on Eden servers, with vanilla's text renderer
  (styles, hovers, clicks and Eden's emoji fonts work as before) and vanilla's chat settings and geometry: the
  channel rules filter entries (no re-layout), a head before each player's message (Chat Heads' job until now), the
  mention stripe and tint, the 30 s linger, the queue line, the scroll bar, a scroll that holds still while new
  lines arrive. Vanilla's chat screen asks it for the style or tag under the mouse and to scroll.
- `ChatComponentMixin` — the store takes a copy of each message (and follows deletions and clears); render,
  scrolling and the style/tag questions go to the panel. The 1.1.9 hacks (a mention tag on vanilla's messages,
  tracking vanilla's line objects) are gone; `ChatActivity` counts off the store.
- The input line, pips, menu and settings are unchanged. VectorLib comes in for menus and message actions later.

Checked in dev 2026-10-12 (two clients): the HUD lines with Bob's head (and the wrapped link line indented under
it), the mention stripe and tint; the open chat (heads, tint, hover "Click to switch to Global" on a tag, the
scroll bar); scrolling up with new lines arriving holds still (fixed: server lines didn't count); the rejoin's
Global history replay; "↓ Mentions" scrolled to a mention in the history; a Local mention while viewing only
Global — @1 on L, its pip switched to Local with the line highlighted. Then: `:item:` (hover shows the sword's
tooltip), a server emoji (KEKW) and a standard one (😂), a spoiler (hover reveals it), [GIF] (the viewer plays it),
[Sticker] (the viewer opens), the emoji's hover name (what the right-click favorite reads); vanilla settings
(scale 0.7, width 0.5, line spacing 0.4, opacity 0.6, background 0.8) — the panel and heads follow them; chat delay
2 s — "[+2 pending lines]" under the newest line. Not checkable in dev: an animated emoji (none in dev's
emoji.json; same font path as the static ones), a message deleted by the server (no command sends one), and the
hidden chat setting wasn't tried (the panel draws nothing, like vanilla).

## Phase 2 as built (Eden 1.2.0-chat.2; owner: message actions in the first version)

- **Hover toolbar** in the open chat: the hovered message's lines light up, and its line gets ↩ Reply (someone
  else's message), ⧉ Copy and ⋯ (the menu) at the right end, with tooltips.
- **Right-click a message** (an emoji or sticker keeps its favorite toggle): `MessageMenu` — Reply, Copy text, Open
  profile, Ignore / Stop ignoring, and for staff who may punish, Moderate. The server's lines and the player's own
  only copy.
- **Reply** switches to the message's channel and types "@Name " (the writer's name from the tab list, else from their
  name in the line). **Copy** takes the text after the sender part — server emojis back as `:name:`, [GIF] and
  [Sticker] links back as their addresses, spoilers as their text — and says so above the hotbar. **Open profile**
  opens the hub on that player's profile; **Moderate** opens the Moderation window on their page (once the window's
  info is in). The "may moderate" flag comes with the chat channel state on join (not only once the hub opened).
- **Spam protection** (owner, like ChatPatches): the very same line again (same sender, same text) right after it
  counts on the last one — one line ending in a gray "×N", back on the HUD and unseen again.

Checked in dev 2026-10-12: "spam spam" three times → one line "×3"; the toolbar on hover; Copy put
"copy me :KEKW: https://media.giphy.com/…/giphy.gif hidden" on the clipboard; Reply typed "@Bob "; the menu's rows;
Ignore hid Bob's lines (and /unignore brought them back); Open profile showed Bob's profile; Moderate (right after
joining) opened Bob's page with the Punish tab.

## Phases

1. **Parity** (~2–3 days): the store, the panel in the open chat and the HUD (fading lines, the 30 s mention linger),
   channel switching, scroll, everything chat shows today (hover items, spoilers, links, emojis incl. animated,
   stickers and the GIF viewer, click a tag to switch, the filters and ignore list), chat settings (vanilla's
   opacity, scale, width, height and line spacing, or our own in the hub). Then 1.1.9's pips, menu and settings move
   onto it. Hub party/guild chat panels keep theirs for now.
2. **Message actions** (~1 day): copy, reply, profile, ignore, staff actions.
3. **Extras**, each ~0.5–1 day, picked by the owner: timestamps (hover or always), a System tab (server messages, deaths, advancements), search, inline GIF and
   sticker previews, reactions (server-stored and synced), the hub's party/guild panels on the same renderer.
4. **Cleanup**: drop the vanilla-window mixins this replaces, the ChatPatches compatibility, and (with the owner)
   Chat Heads from the pack once the panel draws heads.

## Decisions (owner, 2026-10-12)

1. **Input**: vanilla's input line stays.
2. **Look**: dark and see-through like vanilla's chat (its opacity settings).
3. **Place and size**: fixed bottom-left like vanilla, sized by its chat settings.
4. **Channels**: the pips as in 1.1.9 (dots, @N, the right-click menu).
5. Message actions (phase 2) come with the first version. No private-message channel — `/tell NAME` covers it.
   Still open: which other extras (phase 3).

## Risks

- Mods that draw into vanilla's chat window would draw nothing. In the client pack (checked 2026-10-12): **Chat
  Heads** (player heads beside chat lines) — the panel can draw heads itself (the hub's `PlayerFace`), then Chat
  Heads can go too; **No Chat Reports** works on signing, not drawing — unaffected; Simple Voice Chat isn't text
  chat. ChatPatches is no longer in the pack (Eden's `ChatPatchesMenuMixin` compat can go in phase 4).
- Vanilla chat settings and accessibility (narrator, text background opacity) need carrying over.
- One more client-side system to keep in step with the server's `ChatMarker` format.
