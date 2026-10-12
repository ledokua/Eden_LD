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

## Phases

1. **Parity** (~2–3 days): the store, the panel in the open chat and the HUD (fading lines, the 30 s mention linger),
   channel switching, scroll, everything chat shows today (hover items, spoilers, links, emojis incl. animated,
   stickers and the GIF viewer, click a tag to switch, the filters and ignore list), chat settings (vanilla's
   opacity, scale, width, height and line spacing, or our own in the hub). Then 1.1.9's pips, menu and settings move
   onto it. Hub party/guild chat panels keep theirs for now.
2. **Message actions** (~1 day): copy, reply, profile, ignore, staff actions.
3. **Extras**, each ~0.5–1 day, picked by the owner: timestamps (hover or always), private messages (`/msg`) as their
   own channel with unseen counts, a System tab (server messages, deaths, advancements), search, inline GIF and
   sticker previews, reactions (server-stored and synced), the hub's party/guild panels on the same renderer.
4. **Cleanup**: drop the vanilla-window mixins this replaces, the ChatPatches compatibility, and (with the owner)
   Chat Heads from the pack once the panel draws heads.

## Decisions (owner, 2026-10-12)

1. **Input**: vanilla's input line stays.
2. **Look**: dark and see-through like vanilla's chat (its opacity settings).
3. **Place and size**: fixed bottom-left like vanilla, sized by its chat settings.
4. **Channels**: the pips as in 1.1.9 (dots, @N, the right-click menu).
5. Still open: which extras (phase 3), and whether message actions (phase 2) come with the first version.

## Risks

- Mods that draw into vanilla's chat window would draw nothing. In the client pack (checked 2026-10-12): **Chat
  Heads** (player heads beside chat lines) — the panel can draw heads itself (the hub's `PlayerFace`), then Chat
  Heads can go too; **No Chat Reports** works on signing, not drawing — unaffected; Simple Voice Chat isn't text
  chat. ChatPatches is no longer in the pack (Eden's `ChatPatchesMenuMixin` compat can go in phase 4).
- Vanilla chat settings and accessibility (narrator, text background opacity) need carrying over.
- One more client-side system to keep in step with the server's `ChatMarker` format.
