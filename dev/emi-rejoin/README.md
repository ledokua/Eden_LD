# EMI rejoin test (dev only, not built)

Checks `EmiReloadFix` (EMI stuck on "Reloading..." after a rejoin). Not on the classpath; for a run,
copy `DevEmiRejoin.java` to `src/main/java/net/ledok/eden_ld/client/compat/` and add
`ClientTickEvents.END_CLIENT_TICK.register(net.ledok.eden_ld.client.compat.DevEmiRejoin::tick);` at the end
of `EdenLDClient.onInitializeClient`. Take both out again before building a release jar.

1. Dev server (`run-server`, port 25598, Player768 is op): `./gradlew runServer`.
2. Dev client with `-Deden_ld.devEmiRejoin=A,A,B,B,CMD,R` and
   `--username Player768 --quickPlayMultiplayer localhost:25598`.

Steps: `A` sends three lone recipe syncs (as the live server does after a join), drops the connection
(the server-stop path) and joins again; `B` does the same and sets the stale "recipes" bit again right
after the rejoin starts, as if Eden's reset had been missed, so EMI's reload stops at "World is null"
and Eden's retry has to redo it; `CMD` runs `/emireload`; `R` a server `/reload`. Each step logs
`PASS` or `FAIL` (logger `EmiRejoinTest`), then the client quits with a summary. Without the fix,
the first `A` fails ("World is null", then EMI waits for tags; checked 2026-10-07).
