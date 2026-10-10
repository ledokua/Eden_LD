# Task for the Duels_LD agent: move parties onto Eden_LD

Duels_LD (`/home/ledok/Projects/Mods/LeDok/Fabric/Duels_LD`, Fabric 1.21.1, Mojang mappings) has its
own 2-player party system. The server now runs **Eden_LD** (`../Eden_LD`, Fabric-only), the hub mod
that owns parties and guilds for every mod. Rewrite Duels_LD to use Eden's parties and to work with
Eden's friendly-fire protection. American English in code, UI and docs; keep the en_us and uk_ua
lang files in step. Don't commit unless the owner asks.

## Why

- Players already form parties in Eden's hub (F4). A second party system in Duels confuses them.
- Eden blocks damage between **partymates and guildmates** — melee, projectiles, pets, and Spell
  Engine / Better Combat targeting. Without suspending that, two Eden partymates (or guildmates)
  matched against each other in a duel can't hurt each other.

## Eden's API (`net.ledok.eden_ld.api.hub.EdenPartyApi`, server thread only)

- `Optional<PartyInfo> getParty(MinecraftServer, UUID player)`. `PartyInfo(UUID id, String name,
  UUID leader, Set<UUID> members)` is a snapshot. Parties are temporary and the leader can change
  (succession), so don't cache it and don't key anything by the leader.
- `boolean sameParty(server, a, b)`, `boolean isLeader(server, uuid)`.
- `void addMembershipListener(MembershipListener)` with `onJoin(partyId, player)` and
  `onLeave(partyId, player)` (leave also fires for kicks and disbanding).
- `void suspendFriendlyFireProtection(ServerPlayer, ResourceLocation reason)` and
  `resumeFriendlyFireProtection(ServerPlayer, ResourceLocation reason)`. Reasons stack (use one
  reason, e.g. `duels_ld:match`); protection returns when none are left. Eden clears suspensions
  when a player disconnects.
- `boolean isProtected(server, a, b)`, `boolean isFriendlyFireProtectionSuspended(server, uuid)`.
- `notifyJoin` / `notifyLeave` are internal — don't call them.

**Dependency**: Eden_LD should be optional for Duels (`suggests` in `fabric.mod.json`), reached only
through one compat class (e.g. `compat/EdenCompat`) guarded by
`FabricLoader.getInstance().isModLoaded("eden_ld")`, so Duels still runs without Eden (then 2v2
needs no party, or falls back to solo pairing — your call, keep it simple). Add the jar the way
`build.gradle` already adds BusyLib and Economy_LD from `libs/` (`modCompileOnly` + local runtime);
the jar is `../Eden_LD/build/libs/Eden_LD-1.0-SNAPSHOT.jar` — copy it into `libs/`.

## What to change

1. **Friendly fire (the important part).** In `DuelManager`:
   - Suspend for every participant when a match starts (`startDuel` for 1v1, next to
     `setPlayerBusy`; `startMatchmakingDuel2v2`).
   - Resume in `endDuel` and `endTeamDuel` (next to `ActivityManager.setPlayerFree`), for online
     players only.
   - `onServerStopping` clears matches without the end path: resume there too.
   - `handlePlayerReconnect`: suspend again when a player returns to an active duel. Eliminated
     2v2 players in spectator stay suspended until the match ends.
   - Duels' own team rules (`AttackEntityCallback`, `ALLOW_DAMAGE`, the `duel_team_*` scoreboard
     teams) stay as they are — they decide who may hit whom inside the match.
2. **Parties → Eden.** Replace `manager/PartyManager` with Eden parties:
   - 2v2 queueing: the queuing player must be the Eden party's leader and the party must have
     exactly 2 members (Eden parties can be bigger — refuse with a clear message rather than
     picking members).
   - Key the 2v2 queue by **Eden party id**, not the leader UUID. Register a membership listener
     that drops a party from the queue when its membership changes.
   - Remove the party disbanding in `startMatchmakingDuel2v2` (around lines 501-508): Eden parties
     outlive a match.
   - Delete Duels' party payloads (`PartyInvite/Accept/Decline`, `SyncPartyPayload`), the
     `/duel party create|invite|accept|leave|disband` commands, and the party panel in
     `client/MatchmakingScreen` (`buildPartyPanel`, invite banner, `applyPartyState`) — invites
     happen in Eden's hub. The matchmaking screen can show "Party: <name> (2/2)" from data the
     server sends, with a hint to open the hub (F4) to form one.
   - Note: Eden refuses party invites while a player is busy (BusyLib), and Duels marks duelists
     busy — that's intended.
3. **Docs**: README/changelog — parties come from Eden_LD; the commands that were removed; Eden is
   needed for 2v2 parties. Bump the mod version (currently 1.2.5).

## Check

Build (`./gradlew build`). If you can, test in a dev run with Eden_LD in `libs/`: two Eden
partymates matched 1v1 against each other can damage each other; after the match they can't; a 2v2
party queues and plays; leaving the Eden party while queued drops it from the queue.
