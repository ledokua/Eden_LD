# Hub screenshot tour (dev only, not built)

Used for the hub critique (2026-10-04). Not on the classpath. `./apply.sh` puts it into the source,
`./remove.sh` takes it out again (run it before building a release jar). By hand:

- `DevTour.java` → `src/main/java/net/ledok/eden_ld/client/hub/`, and add
  `net.ledok.eden_ld.client.hub.DevTour.tick(client);` at the top of the hub key's
  `END_CLIENT_TICK` handler in `EdenLDClient.registerHub()`. For a filled Friends tab (needs
  Minestar otherwise), make `HubState.friendsAvailable()` also return `DevTour.fakeFriends()`.
- `DevSocial.java` → `src/main/java/net/ledok/eden_ld/command/`, and call
  `DevSocial.register(dispatcher);` first in `EdenCommands.register`.

Run (offline dev server on port 25599, `run-server/ops.json` has Player768 as op):

1. `JAVA_TOOL_OPTIONS="-Deden_ld.devSocial=true -Deden_ld.devProfiles=true" ./gradlew runServer`
2. Two extra clients (Alice in `run`, Bob in `run-bob`) with
   `--quickPlayMultiplayer localhost:25599 --username <name>`.
3. The main client in `run-main` with `-Deden_ld.devTour=<output folder>` (optionally
   `-Deden_ld.devTourTabs=friends,activities` for just those tabs) and
   `--width 1920 --height 1080 --username Player768`. Once three players are online, the server
   puts them in a party and a guild, gives everyone creative/peaceful/noon and unlocks the themes;
   the tour then screenshots every tab (Activities on the Duels filter, the Guild bank and its log
   window too) and the Help window in each theme and language, and ends on Friends with sample
   friends so a card can be hovered by hand. The server also gives the guild two bank tabs with a
   few items.

Narrow a run with `-Deden_ld.devTourLangs=uk_ua`, `-Deden_ld.devTourThemes=parchment,dark`
(also `overgrown`, `stone_tablet`) and `-Deden_ld.devTourSizes=min,560x310,max` (hub sizes; each
size gets its own shots — `run-main/options.txt` has `guiScale:3`, so the window holds up to
632 × 352). The Waystones tab needs the Waystones mod: copy the pack's `waystones-fabric-*.jar` and `balm-fabric-*.jar`
into `run/mods`, `run-bob/mods`, `run-main/mods` and `run-server/mods` (the loader remaps them); the server
then places seven "Dev …" waystones by Player768 once per world, activates them and stars three.

After a VectorLib update the extra clients' launch command still points at the old remapped
VectorLib jar (`NoClassDefFoundError` on new classes): run `./gradlew runClient` once, or replace
the old `VectorLib-fabric-*/<hash>/…jar` path in `build/loom-cache/argFiles/runClient` with the new
one under `.gradle/loom-cache/remapped_mods/remapped/unspecified/` (any Gradle build remaps it).
