# Kits tab

The owner's idea (2026-10-11): a place in the hub where players claim their kits, especially right after the
tutorial, plus a tutorial step for it. Kits come from Kits_LD (the pack's 0.2.0; its own `/kits` window and
`/kit editor` stay its own).

## Decisions (owner, 2026-10-11)

- **Its own tab** in the hub's tab row (after Dailies), not a Home card.
- **`/kits` opens the hub's Kits tab** for players with Eden on their client; vanilla clients keep Kits_LD's chat
  listing. `/kit claim <name>` and the admin `/kit editor` stay as they are.
- **Tutorial:** a kits step right after the finish, with no new NPC (owner: yes). The NPCs_LD session's guide ends its
  `tutorial_cleanup` with `open_dialog config:tutorial/kits` — applied after the whole action list, so after
  `eden tutorial finish` (inventory back, teleport) — instead of an `npcs_ld talk` in `commands_on_finish`, which would
  close the guide's session mid-list. The step: hub button → Kits tab → the first kit → Claim; skipping the tutorial
  gets it too. The tab's tour is `config:hub/kits`.

## How it works

- **Server:** `compat/KitsCompat` is the only class touching Kits_LD types: the kits a player sees (Kits_LD's rules:
  `hidden_when_locked`, permission `kits_ld.kit.<name>`, cooldown or one-time), and the claim through Kits_LD's own
  `KitService.tryClaim` (its one claim path: permission, cooldown, give, commands). `hub/kits/KitsHub` answers the
  tab's request, claims (refused while busy — the tutorial — like every hub reward), pushes the list on join (the tab
  and its badge show up) and wraps `/kits`.
- **Payloads:** `KitsRequest` (C2S), `KitsData` (S2C: one `KitView` per kit — id, styled name, icon, the wait, locked
  and its message, the cooldown, the contents by area and slot; never the kit's commands), `KitClaim` (C2S).
- **Client:** `client/hub/KitsTab` — one card per kit (icon, name, status: Ready / Ready in 2 h 5 min / Claimed
  (one-time) / the lock message; Preview, Claim). Countdowns tick locally; at zero the card turns claimable. Preview
  is a parchment dialog shaped like the inventory the kit lands in (armor and offhand, the three rows, the hotbar,
  trinkets below), with vanilla tooltips.
- **Badge:** ready kits count on the tab (Settings → Notifications → "Kits to claim").
- **Highlight ids** (for the tour and the tutorial step): `eden_ld:hub/kits/list`, `kits/first` (the first card),
  `kits/claim` (the first Claim that can be pressed), `kits/preview` (the first Preview), `kits/preview_window`
  (the open Preview); the tab's own
  `hub/tab/kits` and `hub/page/kits` as for every tab. Tour: `tutorial.tab_tours` `kits` → `config:hub/kits`.

## Checked

Dev, 2026-10-11 (Eden 1.1.6, Kits_LD 0.2.0, five test kits: anytime, 90 s, one-time with armor and offhand, a
1-day kit with a lock message, a hidden staff kit):

- `/kits` as the player opened the hub on Kits; again after `/reload` (the wrap is re-applied). The badge counted 5.
- Preview: armor column, offhand, the rows and the hotbar in their slots, item tooltips; Claim from the preview.
- Claim: a notice ("Claimed Starter kit."), the items in their saved slots and the armor worn, the card "Claimed: a
  one-time kit" with Claim off, the badge one less. The 90 s kit counted down "Ready in 1m 28s", "1m 25s", …
- Without op (no permission): every kit locked, the Prime kit with its own message, the hidden kit gone, no badge.
- In the tutorial (busy): Claim refused with "Not during the tutorial — it opens up once you finish it."
- The help window's text; no ▶ without a `config:hub/kits` dialog (as intended).
