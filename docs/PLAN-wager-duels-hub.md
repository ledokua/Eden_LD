# Wager duels in the hub (Eden 1.3.12)

Duels_LD's wager duels (`../Duels_LD/docs/PLAN-wager-duels.md`, the moderator's plan "Дуелі на ставку", stages 2–5)
get their hub pages here. The split is the owner's: Duels_LD owns the rules, the duel bank, the fights and every
screen that moves items; Eden draws Activities → Duels from `DuelsApi` data in its own themes and calls `DuelsApi`
actions. Built 2026-10-26 against `libs/duels_ld-1.6.0.jar`; not run in game.

## What Eden draws

- **The rename.** Today's Duels filter is **Ranked PvP** to players ("Рейтинговий PvP"): the filter, its new
  tooltip, the Home queue card, Settings → Notifications, the help text. Stored ids stay: the `Filter.DUELS` enum,
  the lang keys, the spotlight ids `eden_ld:hub/duels/…` and the tour `activities/duels`.
- **Duels** (`Filter.WAGERS`, `client/hub/WagersTab`), right after Ranked PvP, with tooltips telling the two apart:
  - **Announcements**: `DuelsApi.listings(server, viewer)` as cards — number, author, W/L/D, coins, the first 3 items
    (full tooltips) and "+N more", time left (counted down on the client from a remaining time sent by the server;
    the state instead while paused), offers, "Challenge to …", the comment. Sort Newest / Coins ("coins only"),
    filter All / Money / Items (`hub/duels/WagerLists`, unit-tested). The viewer's own pinned on top with Offers and
    Cancel (confirmed).
  - **My duels**: `DuelsApi.myWagers` — the bank line (items in the tooltip), the own announcement and its offers
    (online dot, stake, Review / Accept / Decline; Accept shows Eden's loss warning unless
    `DuelsApi.skipsLossWarning`, and the warning names Settings → Privacy → Duels), the player's offers (Withdraw),
    personal challenges (Offer a stake / Decline).
  - **History**: `DuelsApi.wagerHistory(server, player, 50)` — number, opponent, both stakes (compact, items in
    tooltips), result, fight time, date, Report (a modal, 300 characters → `DuelsApi.report`; "Reported" once
    `reportedByViewer`). The record (`wagerStats`) on top.
  - The fairness warning (a dim box) on Announcements and My duels, in both languages.
  - Every action's `WagerResult.message()` comes back as a `HubNotice` toast (green when `ok()`).

## Payloads (both new; no existing codec changed)

| Payload | Direction | Fields | Guard |
|---|---|---|---|
| `eden_ld:wager_data` (`WagerData`) | S2C | `parts` (LISTINGS 1, MINE 2, HISTORY 4), `enabled`, `actions` (CREATE 1, TERMS 2, REVIEW 4: which Duels screens open from the hub), `skipLossWarning`, `wins/losses/draws`; then only the asked parts: listings, mine (listing?, offers on it, my offers, challenges, bank), history | `ServerPlayNetworking.canSend` |
| `eden_ld:wager_action` (`WagerAction`) | C2S | `action` (VIEW, LEAVE, CANCEL_LISTING, WITHDRAW_OFFER, DECLINE_OFFER, ACCEPT_OFFER, DECLINE_CHALLENGE, REPORT, OPEN_CREATE, OPEN_TERMS, OPEN_REVIEW), `id` (page, offer id or duel number), `text` (the complaint, ≤ 512) | `ClientPlayNetworking.canSend`, and the server registers it only with the wager API |

Stakes travel as whole stacks (their tooltips); names are resolved on the server (Duels' names, else online, else
the profile cache); listing and offer states go by enum name, so a state a newer Duels adds shows as "—".

`hub/duels/WagersHub` (server): `VIEW` subscribes the player to a page, `LEAVE` (the filter or tab left, the hub
closed or replaced) stops it. `onWagerChanged(uuid)` re-sends that player's page; `onListingsChanged()` re-sends
Announcements to everyone on it; My duels also refreshes every 5 s (online dots). Sends are queued per player and
flushed at the end of the tick (at most one per player per tick); a page too big for one packet loses its oldest
halves. On join each player gets the header alone, which shows the filter.

## The reflection guard

Live may run an older Duels_LD (1.5.0 has no wager API), so every wager type is used in one class,
`compat/duels/DuelsWagers`. `DuelsIntegration.WAGERS` checks once, by reflection, that `DuelsApi` has every wager
method Eden calls (with its return type), the listener's `onWagerChanged` / `onListingsChanged`, and every record
accessor read (`WagerListing`, `WagerOffer`, `WagerRecord`, `WagerStake`, `WagerStats`, `MyWagers`,
`WagerResult.ok/message`). `DuelsHook.wagers()` is false without it: nothing is registered, the client can't send
`WagerAction`, and the Duels filter doesn't show. Ranked PvP is untouched.

## Waiting for Duels' open calls

**Create duel**, **Terms** / **Offer a stake** and **Review** stay disabled with "Coming soon" until `DuelsApi` has
(frozen by Duels_LD, not in 1.6.0):

```java
public static WagerResult openCreate(ServerPlayer player, @Nullable UUID target)   // null = public
public static WagerResult openTerms(ServerPlayer player, int number)
public static WagerResult openReview(ServerPlayer author, int offerId)
```

`DuelsWagers` looks each up by reflection (static, those parameters, returning `WagerResult`) and calls it through
reflection, so the Duels_LD that adds them lights the buttons up with no Eden change. OK: Duels' screen replaces
the hub (no toast, no return to the hub armed). Not OK: `message()` as a red toast. A missing or misshaped method
keeps its button disabled. The hub only opens public announcements (`target` null): personal challenges stay
`/duel <name>`.

**Deployed (2026-10-26, Eden 1.3.12 with Duels_LD 1.7.0):** Prism Client/Server (Eden 1.3.10 and Duels 1.5.0 to
removed-mods-2026-10-26), pack ce147e7 (pushed), live (both old jars to backup/removed-mods-2026-10-26; cmp +
unzip -t). Includes 1.3.11 (reports, settings API, unread-mail line). Tested by the Duels session in dev with two
clients (pages, Review hand-off, "Don't ask again"); History and Report not yet. Live from the owner's next restart.

**Deployed (2026-10-26, Eden 1.3.13 with Duels_LD 1.7.1):** Duels' screens follow the hub theme (EdenHubThemeApi).
Prism Client/Server (1.3.12 and 1.7.0 to removed-mods-2026-10-26), pack e52055f (pushed), live (both to
backup/removed-mods-2026-10-26; cmp + unzip -t). Live from the owner's next restart.
