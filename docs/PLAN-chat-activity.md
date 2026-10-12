# Chat activity: channel pips, pings you can find, per-channel notifications

The owner (2026-10-12): a ping only played a sound — no way to see where it came from. A bar of channel tabs like
ModernTech's was too big; option A of the mockup was chosen: small channel pips in the chat input row.

## Decisions (owner, 2026-10-12)

- **Pips** replace the single channel badge left of the chat input: one per channel the player can use (Party and
  Guild only while in one); the one typed into shows its name, the others a letter, in the channel's tag color.
- **A dot** on a pip: messages in that channel the player hasn't seen — **always**, not only with "Only the selected
  channel". **@N**: mentions not seen yet.
- **Click** a pip to switch to that channel — **also a channel switched off** in Settings → Chat (its lines show while
  it is selected). **Ctrl+Tab still skips** switched-off channels.
- **Right-click** a pip: a menu like Discord's — **Mute** ▸ 5 minutes, 15 minutes, 1 hour, 24 hours, until I turn it
  back on (or Unmute); **Notifications** ▸ All messages, Only @mentions, Nothing; and a button to the hub's
  Settings → Chat.

## How it works (my choices, Discord's rules)

- **Seen** = drawn in the open chat. A line from someone else in channel C counts as unseen until the chat window
  draws it; switching to a channel, scrolling, or opening the chat on new lines clears them. Lines the player hid
  stay unseen until they're shown.
- **Mute** (per channel, until a time or for good): no dot and no "All messages" sound. @mentions still come through
  (as on Discord), unless the channel's notifications are Nothing.
- **Notifications** (per channel, default Only @mentions): All messages — a soft sound for every new line there
  (unless muted) plus the mention ping; Only @mentions — the ping and the @N badge; Nothing — neither (the dot still
  shows unless muted). The ping volume stays Settings → Chat → mention volume.
- **Finding the ping**: the server marks each mention in the line with an invisible marker naming who was mentioned
  (`ChatMarker`-style, so shift-click inserts nothing). The mentioned player's client tags that line: a stripe and a
  tint in the notification color (Settings → Notifications), "You were mentioned" on the stripe's hover, and it stays
  on the HUD 30 s instead of 10. A **"↓ N mentions"** button over the chat input jumps to the oldest unseen one
  (switching channel when its lines aren't shown); clicking a pip with @N does the same for that channel.
- **Stored** server side with the other hub settings (they follow the player): `chat_notify` (2 bits per channel) and
  `chat_mute_<channel>` (0 off, -1 until turned on, else the end in epoch minutes; the client sends the length).
- **Settings → Chat** gets a row per channel: notifications (All / Only @mentions / Nothing) and, while muted, when
  it ends with Unmute. The menu's settings button opens the hub on that section.
- Vanilla clients keep today's ping sound (server side), now respecting the channel's Nothing.

## Checked

Dev, 2026-10-12 (Eden 1.1.9, two clients: Player768 and Bob):

- Bob's `@Player768` in Global: the line has the stripe and tint in the notification color on the HUD and in the
  open chat, and stays on the HUD past 20 s (vanilla: 10).
- With "Only the selected channel" on Global, Bob's Local `@Player768`: **@1** on the L pip and "↓ Mentions: 1";
  the button switched to Local and scrolled to the line; badge and button gone (seen).
- Right-click on the Global pip: the menu (Mute ›, Notifications › "Only @mentions", Mark as read grayed out, Chat
  settings); both submenus open on hover. Mute for 5 minutes: tooltip "Muted until 20:46", a plain Global line
  adds no dot, a Global ping still counts @1; the menu then offers "Unmute channel" and Mark as read.
- "Chat settings" opened the hub on Settings → Chat: the Channel notifications rows, "Global is muted — Unmute"
  (unmuted it).
- Guild switched off: dimmed pip; Ctrl+Tab went Local → Global → Local (skipped it); a click selected Guild.
- Not checked: lines from others in a switched-off channel while selected (Bob had no guild), the All messages sound.
