#!/bin/bash
# Takes the screenshot tour back out of the source (saving any edits made to it while applied).
set -e
cd "$(dirname "$0")/../.."
B=src/main/java/net/ledok/eden_ld
[ -f $B/client/hub/DevTour.java ] && cp $B/client/hub/DevTour.java dev/hub-tour/DevTour.java
[ -f $B/command/DevSocial.java ] && cp $B/command/DevSocial.java dev/hub-tour/DevSocial.java
rm -f $B/client/hub/DevTour.java $B/command/DevSocial.java
python3 - <<'PY'
def edit(p, old, new):
    s = open(p).read()
    if old in s:
        open(p, 'w').write(s.replace(old, new, 1))
B = 'src/main/java/net/ledok/eden_ld/'
edit(B + 'client/hub/HubState.java', '''        return DevTour.fakeFriends() // TEMPORARY: hub critique screenshots
                || homeData != null && homeData.friendsAvailable();''',
     '''        return homeData != null && homeData.friendsAvailable();''')
edit(B + 'command/EdenCommands.java', '''        DevSocial.register(dispatcher); // TEMPORARY: hub critique test data
''', '')
edit(B + 'client/EdenLDClient.java', '''            net.ledok.eden_ld.client.hub.DevTour.tick(client); // TEMPORARY: hub critique screenshots
''', '')
PY
grep -rq 'DevTour\|DevSocial' $B && { echo "still referenced:"; grep -rn 'DevTour\|DevSocial' $B; exit 1; } || echo "tour removed"
