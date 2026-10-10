#!/bin/bash
# Puts the screenshot tour into the source (dev only — run remove.sh before building a release jar).
set -e
cd "$(dirname "$0")/../.."
B=src/main/java/net/ledok/eden_ld
cp dev/hub-tour/DevTour.java $B/client/hub/DevTour.java
cp dev/hub-tour/DevSocial.java $B/command/DevSocial.java
python3 - <<'PY'
def edit(p, old, new):
    s = open(p).read()
    if new in s:
        return
    assert old in s, (p, old[:60])
    open(p, 'w').write(s.replace(old, new, 1))
B = 'src/main/java/net/ledok/eden_ld/'
edit(B + 'client/hub/HubState.java', '''        return homeData != null && homeData.friendsAvailable();''',
     '''        return DevTour.fakeFriends() // TEMPORARY: hub critique screenshots
                || homeData != null && homeData.friendsAvailable();''')
edit(B + 'command/EdenCommands.java', '''    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
''', '''    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        DevSocial.register(dispatcher); // TEMPORARY: hub critique test data
''')
edit(B + 'client/EdenLDClient.java', '''        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (hubKey.consumeClick()) {''', '''        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            net.ledok.eden_ld.client.hub.DevTour.tick(client); // TEMPORARY: hub critique screenshots
            while (hubKey.consumeClick()) {''')
PY
echo "tour applied"
