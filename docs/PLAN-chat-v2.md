# Eden chat v2, VectorLib rounded boxes and smooth font — plan

Design of 2026-10-17 (a research + three-draft + judge workflow; nothing built yet), with the adversarial review's
fixes folded in at the end (**Review amendments** — they override the plan where they differ). Path prefixes:
- **EDEN** = `Eden_LD/src/main/java/net/ledok/eden_ld/`
- **VL** = `VectorLib/common/src/main/java/net/ledok/vectorlib/client/`
- **GM** = `minestartat-gardenmyth/`
- **MC** = the 1.21.1 Mojmap sources jar in the loom cache

Base versions: VectorLib 0.6.2 (`VectorLib/gradle.properties:8`), Eden 1.2.4 (`Eden_LD/gradle.properties:8`). Eden requires `"vectorlib": ">=0.6.2"` (`Eden_LD/src/main/resources/fabric.mod.json:32`).

I checked these seams in the code before writing:
- `ChatScreen.input` is `protected`, `initial` is `private`, and `commandSuggestions` is package-private (MC `ChatScreen.java:28-30`).
- The input is an anonymous `EditBox` that already has no border (`:41-50`).
- `render` runs in this order (`:178-195`): chat history at `:179`, input-row fill at `:180`, `input.render` at `:181`, suggestions at z+200 at `:185`.
- `Minecraft.openChatScreen` creates the screen at MC `Minecraft.java:993`; `InBedChatScreen` is created at `:1766`.
- `GuiGraphics.setColor` flushes first (MC `GuiGraphics.java:183-186`).
- VL has no `Easing.IN_CUBIC` (`VL canvas/Easing.java` has only OUT_CUBIC, IN_OUT_CUBIC and OUT_BACK).
- `ContextMenu` and `TabBar` have no submenu or badge support.
- `WidgetStyle.Skin` is sealed and has only `Flat` (`VL canvas/widget/WidgetStyle.java:24,39`).
- The Window shadow is two hard rects (`VL canvas/widget/Window.java:394-396`).
- VectorLib mixins are per-loader only (`VectorLib/fabric/src/main/resources/fabric.mod.json:21-22`, `neoforge.mods.toml:12-13`).
- `ChatChannels.send` passes `signed=null` with no mute, `§` or rate check (`EDEN chat/ChatChannels.java:235-251`).
- Parchment has no theme.json; it is defined in Java (`EDEN client/theme/HubTheme.java`). The themes folder holds only dark, overgrown and stone_tablet.

---

## Goals and decisions (the owner's 2026-10-17 answers)

1. **Eden keeps its four hub themes** (parchment, dark, stone_tablet, overgrown). From GardenMyth we take the structure, polish and motion, drawn in Eden's theme colors. GM's palette is only a map of which role each color plays.
2. **Eden's chat becomes GardenMyth-like.** The 2026-10-12 decisions are reopened: fixed bottom-left, pips instead of tabs, no PM channel, vanilla look (`Eden_LD/docs/PLAN-custom-chat.md:111-119`). This plan supersedes them with a dated 2026-10-17 block in that file.
3. **The VectorLib SDF rounded-box node is approved**: anti-aliased fill and border, per-corner radius, soft box shadow, one quad per box. Of the optional extras, this plan includes **glow** (free) and an **opt-in vanilla menu blur** for CanvasScreen. **SVG path parsing** waits until stroked icons are wanted.
4. **Try a smooth TTF font with the Minecraft font as fallback.** It is opt-in, built in code, and falls back per glyph to `minecraft:default`.

**Standing constraints this plan keeps:**
- Fabric first.
- Light on RAM and load time.
- Eden keeps its strengths:
  - typed chat stays signed through `ChatScreen.handleChatInput`;
  - full Components with hover, click and spoilers;
  - MessageActions, mentions, ×N, emoji, stickers and GIFs;
  - the Discord bridge;
  - the 1000-line `ChatStore` fed by `EDEN mixin/ChatComponentMixin.java:25-40`;
  - server-validated `HubSettings`;
  - en_us and uk_ua.
- No LGPL code. GM code may be adapted (with permission); javaui is not used.
- No Eden data migrations.
- API.md is updated with every VectorLib change.
- VectorLib work is built and synced (`syncLibs`) before Eden.
- American English.

**Not copied from GM:**
- the unsigned send payload;
- client-trusted ItemStacks;
- `/chatadmin` without validation;
- the `§` bypass;
- synchronous archive writes;
- reflection polling of vanilla chat;
- channel modes kept only in memory.

---

## Scores

| Draft | Correctness vs code | Owner fit | Risk / shippability | Effort realism | Overall |
|---|---|---|---|---|---|
| incremental | 8 | 6 | 9 | 7 | **7.5** |
| faithful | 6 | 9 | 5 | 7 | **6.5** |
| foundation | 7 | 8 | 7 | 5 | **7.0** |

- **incremental:** The seams are accurate and it is the safest order: fixes, then server extras, then search and alerts, then toasts, then SDF, then the screen. But the first GM look arrives only after about 30 days. Search and toasts are built twice, once in the old mixin and then again. Its `setBordered(false)` step does nothing, because vanilla already turns the border off (`ChatScreen.java:46`).
- **faithful:** It is closest to what the owner asked for and has the most precise port of GM's constants. However, it swaps the vanilla `EditBox` and `CommandSuggestions` *after* `super.init()`, which drops the anonymous narration subclass and risks NCR, owo and Debugify. E1 is a 9-day all-at-once release. The token `:item@N:` can only reach the player's own inventory, so chest items lose their data. It is also the riskiest plan to ship.
- **foundation:** The architecture is best: a chat model separate from drawing, theme tokens, `Skin.Box`, opt-in UiFont per canvas, and the 1-texel bleed risk on font sheets. But "override `EditBox.renderWidget`" is impossible without replacing the box. 41 days is optimistic: SDF in 4.5 days and the whole screen in 6. It also restyles the hub before the chat, which the owner did not ask for.

**What the synthesis takes from each:**
- From foundation: the model and tokens first, opt-in font per canvas, `Skin.Box`.
- From faithful: the GM constants, the motion catalogue and the feature set.
- From incremental: release sizing, opt-in VectorLib defaults, server-validated word alerts, and the server-extras track that runs in parallel.
- New in the synthesis:
  - a silent-input seam built from two `@WrapWithCondition`s, so no EditBox is replaced;
  - a server-side chip stash so chest items keep their full data;
  - the screen split into two releases (screen, then tab strip).

---

## Phase plan

There are two tracks. **Track V** is VectorLib. **Track E** is Eden. Phase 10 (server extras) needs only Phase 0 and can run in parallel. Each phase is a release and leaves the chat working. Effort is in working days.

**Rules for every phase:**
- Bump `mod_version` in VectorLib and update CHANGELOG and API.md, then run `syncLibs` into `Eden_LD/libs/VectorLib-fabric.jar` and raise `fabric.mod.json:32`.
- Add every new string to both en_us and uk_ua.
- Update the README Chat section and `docs/PLAN-custom-chat.md`.

**Dev-test rules for every phase:**
- Dev server on port 25598; clients on their own Xvfb `:98` with no Wayland.
- Kill only the KnotClient processes under this project's loom-cache path.
- New worlds: `/gamerule doMobSpawning false`, then `/kill @e[type=!player]`.
- Use two quick-play clients.
- Check GUI scales 1–4 and both languages.
- Test VectorLib pages with `/vectorlib widgets` at 1000×600, and watch for the stale argFile trap.
- Check compatibility in the owner's Prism "Client" instance with production jars (NCR 2.9.1, owo, Debugify, Iris 1.8.14).

### Phase 0 — Eden 1.2.5: safety fix and small groundwork (1.5 d)

**Goal:** close the hub chat bypass. No visible change.

**Scope:**
- New `EDEN chat/ChatGuard.java`. It is called at the top of `ChatChannels.send` (`chat/ChatChannels.java:235`), so the hub panels and `/p`, `/g`, `/l` all get it. It does four things:
  1. Mute check: `BanHammerCompat.muted(UUID)`, using the same API as `compat/BanHammerCompat.java:27,62-69`.
  2. Rejects characters that fail `StringUtil.isAllowedChatCharacter` (`§` and control characters).
  3. Per-player token bucket: 6 messages per 5 s, set in the server `chat.json`. The refusal uses the lang key `chat.eden_ld.rate_limited`.
  4. Keeps the 256-character cap (`hub/HubPayloads.java:333-337`).
- Delete `mixin/ChatPatchesMenuMixin.java` and its `eden_ld.mixins.json` entry (ChatPatches has left the pack).

**Dev tests:**
- A BanHammer-muted Bob is refused in the hub Party panel.
- `§cHi` is refused.
- 10 fast messages are throttled.
- Typed chat and `/p` are unchanged.

### Phase 1 — Eden 1.2.6: chat model, separate from drawing (3 d)

**Goal:** the multi-view, cache-friendly model that every later phase uses. No visible change.

**Scope:**
- **`ChatPanel` becomes instances.** Its static state (`client/chat/panel/ChatPanel.java:57-71`) moves into `client/chat/panel/ChatView` instances. Each has its own rect, scale, filter, scroll and row cache. `ChatPanel` stays as a static facade over `ChatViews.main()`, so the `ChatComponentMixin` redirects stay as they are.
- **Two-slot wrap cache.** `ChatEntry`'s single-width cache (`ChatEntry.java:36-37,88-96`) becomes two slots keyed by `(width, fontEpoch)`. Add a lazily cached lowercase `plain()` for search and alerts.
- **New package `client/chat/model/`:**
  - `ChatTabSpec(id, kind{CHANNEL, SYSTEM, ALL, SPY, PM}, channel, labelKey, colorToken, filter)`. Channel colors move here from `ClientChatChannel.java:191-198`.
  - `ChatCounters`: unseen, mentions and alerts per tab, cached by `ChatStore.version()` plus a settings epoch. Today `drawPips` rescans the store several times per frame (`mixin/ChatScreenMixin.java:474-510`).
  - `ChatStore` gains per-tab lifetime `received` counts for the "↓ New: N" pill.
  - `ChatWindow(id, tabs, active, Layout)`. Exactly one window for now.
  - `ChatViewport(scroll, anchorReceived, search, newCount)`.
  - `ToastQueue`. Fed from `ChatActivity.added` (`client/chat/ChatActivity.java:55`). It respects `CHAT_NOTIFY` and `CHAT_MUTE` (`hub/HubSettings.java:47,53`).
- **`ChatComponent.restoreState` TAIL inject.** Rebuilds `ChatStore` after the configuration phase, which fixes the empty-chat gap after a proxy switch.
- **Redirect `handleChatQueueClicked`** to the main view.
- **First JUnit source set** in `Eden_LD/src/test/java`. It covers ToastQueue transitions, counter caching, layout clamping and the tab filters.

**Dev tests:**
- Pips, the mention pill, the System view, ×N and the queue-line click behave exactly as in 1.2.5.
- Compare F3 profiler frame time with chat open before and after.

**Built (2026-10-17, Eden 1.2.6).** As planned, with amendments 1–3 and 19, except:
- `client/chat/model/` holds the plain code: `ChatTabSpec` (the filters — `shows(mark, rules)`; 1.2.5's
  `ChatFilter.hidden` is now the pips' tab: System, the selected channel with "only the selected channel", else All),
  `ChatRules` (self, ignore list, switches, typing channel — built by `ChatFilter.rules()`), `ChatCounters`,
  `ChatViewport`, `WrapCache` (the two-slot wrap cache), `ChatLayout` (anchor + fractions + zoom, clamped),
  `ChatWindow` (id + layout; tabs come with the tab strip in phase 5), `ToastQueue`.
- `client/chat/panel/ChatView` (instances) + `ChatViews` (`main()`, owned by no screen, per amendment 1); `ChatPanel` is
  the static front ChatComponentMixin calls.
- **No lifetime `received` counts in ChatStore**: `ChatViewport.newCount` is counted by the view itself when a line it
  shows arrives while scrolled up — exact under the filters and search. The scroll bar's red uses it.
- **Counters** are keyed on `ChatStore.version()` + `seenEpoch` only; mutes and notification levels are applied when
  read (ChatActivity), so a mute ending by the clock needs no key (amendment 2's intent).
- Reconfiguration: `storeState` RETURN snapshots the store (keyed by vanilla's State object), `restoreState` TAIL puts
  it back; nothing is replayed through `added`. `handleChatQueueClicked` asks the main view.
- `fontEpoch` is bumped by the client resource reload listener (F3+T, pack changes): lines wrap again.
- `ToastQueue` is fed (lines the main view isn't showing, by the channel's notifications and mute) but not drawn yet.
- Tests: `./gradlew test` — 34 JUnit tests, plain JUnit 5 (no game started); `ChatFilterEquivalenceTest` checks the
  new filters against a copy of 1.2.5's `ChatFilter.hidden` over every marker, setting and pip choice (≈162k cases).
- Dev-tested 2026-10-17 with two clients: pips and their tooltips, @N and dots in the System view, "↓ Mentions" jump,
  ×N, hold-still scrolling and the red scroll bar, F3+T, the Chat Delay line click, toolbar and menu — as in 1.2.5.
  The reconfiguration snapshot is unit-tested only (no proxy in dev). No profiler comparison was made: the pips now
  read cached counters instead of rescanning the 1000-line store ~15 times per frame.

### Phase 2 — VectorLib 0.7.0: SDF `RoundedBox` (approved) (6 d)

**Goal:** the approved node. The look of existing consumers does not change.

**Scope** (full spec in the RoundedBox section below):
- The node, records, shader, render type and loader glue.
- Tessellated fallback.
- `RoundedBox.draw(GuiGraphics…)` for code outside a canvas.
- Glow mode.
- `Easing.IN_CUBIC`, a trivial enum entry that Eden's close and menu animations need.
- Opt-in `CanvasScreen/CanvasHandledScreen.blurBackground(boolean)`, default false.
- `WidgetStyle.Skin.Flat` and the Window shadow stay as they are in 0.7.0. VectorLib is jar-in-jar in NPCs_LD, Economy_LD, Arenas_LD, Kits_LD and Eden, and the highest version wins, so any default change restyles every mod at once.

**Files, effort split and tests:** see the spec section.

**Built (2026-10-17, VectorLib 0.7.0).** As specified, with amendments 11, 13 and 17:
- `RoundedBox`, `BoxStyle`, `BoxShadow`, `BorderAlign` (`client.canvas`); `BoxRenderer` (shader quads + path
  fallback) and `BoxCodec` (the packing, package-private); `VectorShaders` + `VectorRenderTypes.roundedBox/
  roundedBoxShadow` (`render.primitive`); shader files `assets/vectorlib/shaders/core/rounded_box.{json,vsh,fsh}`
  (glslang-validated, namespaced stage names).
- Modes 4/5 encode a centered/outside border (the fill's inset); outer shadows and glows use a render type without
  depth writes (amendment 11). Not done: the "all shadows before all boxes" batch for GuiGraphics runs — boxes are
  emitted in painter's order instead (a run without shadows is still one draw call; shadows split it).
- No public `CanvasRenderContext.box()` (only `sdf()`): custom nodes use `RoundedBox.draw(ctx, …)`.
- The GuiGraphics overload draws at the pose's z (its path fallback too), so text drawn after it shows.
- Also shipped: `Easing.IN_CUBIC` (appended, ordinals kept), `CanvasScreen.blurBackground`, `Path2D.roundRect` per
  corner, `Flex.backgroundBox`, `/vectorlib boxes` (+ a button in `/vectorlib widgets`).
- Tests: `RoundedBoxCodecTest` (12: every normal code under both GL rules, clamping, unsigned shorts, radius codes,
  hit-testing, and emitted quads decoded with a Java mirror of the vertex shader). Dev clients: Fabric at GUI scale
  1, 2 and 4, F3+T, `-Dvectorlib.noSdf=true`, a broken `.fsh` in a resource pack (one warning, reload fine, paths
  used), rotation/scale/opacity, ScrollPanel clip, menu blur; NeoForge 21.1.249 draws with the shader too. Not yet
  tried: an Iris shader pack in the owner's Prism Client, before/after screenshots of existing screens (no existing
  call site changed).

### Phase 3 — VectorLib 0.7.1: motion and widget hooks (3 d; needs approval)

**Goal:** generic pieces for GM-style polish, so Eden carries no private copies.

**Scope:**
- **`VL canvas/Motion.java`**, written from the math (behavior matches GM `client/gui/UiMotion.java:38-115`; no code copied):
  - `Motion.Spring(tauMs)`: `value`, `target`, `step(dtMs)` using `1-exp(-dt/tau)`; snaps on the first frame.
  - `Motion.outBack(t, c1)`.
  - `Motion.Presence(inMs, outMs)`: OUT_CUBIC in, IN_CUBIC out, reverses from the current value.
  - All of them snap when `Animation.enabled(false)`.
- **`ContextMenu`** (`VL canvas/widget/ContextMenu.java`):
  - `Item.submenu(label, Supplier<ContextMenu>)`, `Item.checked(boolean)` (radio dot), `separator()` and `header(Component, color)`.
  - Open: 150 ms rise of 4 units plus fade. Close: 110 ms IN_CUBIC lift of 3 units. Both durations have setters.
- **`WidgetStyle.Skin.Box(BoxStyle)`**, a new permitted record of the sealed `Skin`, so popups, buttons and fields can draw rounded and themed. Nothing outside VectorLib switches over `Skin`.
- **`VL presentation/CanvasEmbed.java`:** hosts a `VectorCanvas` inside any vanilla `Screen`. It forwards render, mouse, key, char and scroll through the existing public `CanvasInput`, draws `CanvasTooltips`, peels Esc, and returns whether each event was consumed.
- **Opt-in default switch:** `VectorLib.defaults().roundedFlat(true)` and `windowElevation(true)`. They become default-on only if the owner approves after the demo (see Decisions).

**Dev tests:**
- A "Motion" page: springs, presence, submenus.
- An embed demo screen that extends a vanilla `Screen`.
- Esc and tooltips work in the embed.

**Built (2026-10-17, VectorLib 0.7.1).** As planned, with amendment 8:
- `Motion.Spring` / `Presence` / `outBack` (`client.canvas`): wall-clock, snap with animations off; each clock read also
  takes a time for tests.
- `ContextMenu`: `Submenu`, `Check`, `Separator`, `Header` are new `Entry` records beside the unchanged `Item`; submenus
  open beside their row on a 180 ms rest or a click (left when the right has no room), a pick closes the whole menu
  chain (popups under it stay), Esc peels one menu. Open 150 ms OUT_CUBIC rise 4 + fade; close 110 ms IN_CUBIC lift 3
  in the overlay layer. `Button.leading()` (protected) makes room for the check dot.
- `WidgetStyle.Skin.Box(BoxStyle)`; `VectorLib.defaults().roundedFlat()/windowElevation()` — off (decision 5 waits
  for the owner's sign-off on the look).
- `CanvasEmbed` (`client.presentation`): render/mouse/keys/scroll handed on; only content counts as taken; a click
  outside open popups closes them and is taken; Tab only while the canvas has focus; `escape()`; `removed()`.
- Binary-compat check: `./gradlew :common:apiCompat` (japicmp 0.4.3) against `VectorLib/compat-baseline/` (now 0.7.1);
  0.7.1 vs 0.7.0: no incompatible change.
- Tests: `MotionTest` (6), `ContextMenuTest` (5; rows pressed directly — no font headless). Dev client:
  `/vectorlib motion` (a vanilla `Screen` + `CanvasEmbed`): spring highlight, presence pill, embed tooltip, menu rise,
  hover-opened submenu with the dot, pick closes both, Esc ×3 peels submenu → menu → screen; `/vectorlib boxes`
  "Defaults: try them…" (flat skins as boxes, window elevation).

### Phase 4 — Eden 1.3.0: Eden chat screen, plates, motion, menus (5.5 d; needs VL 0.7.1)

**Goal:** the first GM look. Same geometry as today (bottom-left, vanilla chat width, height and scale). The pips stay for this release.

**Scope:**
- `EdenChatScreen extends ChatScreen`, swapped in through a mixin.
- Silent vanilla input with Eden's painted overlay.
- Chat theme tokens.
- RoundedBox history and input plates.
- Open and close motion; popups that rise.
- The "↓ New: N" pill; the "↓ Mentions" pill restyled.
- Rounded hover row and toolbar buttons.
- `ChannelMenu` and `MessageMenu` rebuilt as VectorLib `ContextMenu` with submenus inside a `CanvasEmbed`.
- The emoji picker moved from `client/hub` and shared.
- HUD lines drawn as rounded strips.
- An escape hatch setting.

**Files:**
- New: `client/chat/ui/{EdenChatScreen, InputOverlay, ChatChrome, ChatTheme}.java`.
- New: `mixin/MinecraftChatScreenMixin.java`.
- Changed: `mixin/ChatScreenMixin.java` (shrinks), `mixin/ChatScreenAccessor.java` (add `initial`, `commandSuggestions`), `mixin/ChatComponentMixin.java`.
- Changed: `client/chat/{ChannelMenu, MessageMenu}.java`, which become ContextMenu builders.
- Moved: `client/hub/{HubEmojiPicker, ChatText, ChatMentions, StickerNode}.java` → `client/chat/ui/`, made public.
- Changed: `client/theme/HubTheme.java`, `HubThemes.java`, and the three theme.json files.

**Dev tests:**
- Signing: the line is not marked "Not Secure", and it appears on the chat-report screen.
- NCR's button and encryption, owo, Debugify.
- Brigadier completion, coloring and usage; @name Tab; ↑/↓ history.
- `!x` one-shots.
- `:emoji:`, stickers, GIF viewer, spoilers, links and `:item:` hover.
- Reply and Copy; the Mute and Notifications submenus.
- Bed chat unchanged; non-Eden servers use vanilla chat.
- Rapid open and close leaves no stuck alpha.
- All four themes; chat scale 0.5 and 1; opacity sliders; Chat Delay above 0.

**Built (2026-10-17, Eden 1.3.0 — Phase 4a; needs VectorLib 0.7.2).** Split per amendment 18; 4b (VectorLib menus in a
`CanvasEmbed`, the picker refactor of amendment 16, HUD strips) is next. Decisions that differ from the scope above
(design and critique: the session's `p4/design-4a.md`, `p4/critique.md`):
- **No `EdenChatScreen`, no silent overlay.** One host, `client/chat/ui/EdenChatUi`, per `ChatScreen` (bed chat
  included, amendment 1), made by `ChatScreenMixin` the first time the screen draws itself (`minecraft.screen == this`:
  NCR's headless screen never gets one). **Vanilla geometry is kept:** history at its vanilla frame (no jump between
  open and closed), vanilla's `EditBox` where it is and drawn by vanilla — caret, inline suggestion, Brigadier
  formatter and NCR/owo hooks untouched. A taller input plate would collide with NCR's buttons, Leave Bed and the
  suggestion list, so amendment 6's overlay waits for Phase 7 (wrapped input).
- **Plates:** the history plate is docked to the left edge (radii 0,4,4,0) and drawn by `ChatView` before its lines
  (one plate, no line backgrounds; mentions are a rounded inset box per message). The input plate replaces vanilla's
  input strip (`@WrapWithCondition` on that `fill`), x 0..w-2, y h-14..h-1, translucent (hotbar shows through), with
  the theme's focus border. *Text Background Opacity* scales both (0.5 = the full look, 0 = none).
- **Motion:** `ChatLook.CHROME` (`Presence` 200/140 ms). Open fades the plates in; the input row rises 4 units. The
  close is decided in `Minecraft.setScreen` (`MinecraftMixin`, HEAD): back to the game → the HUD chat draws the
  panels fading out (`ChatLook.drawClosing`); another `ChatScreen` (bed → chat) keeps them; any other screen covers
  them at once. Fade rule: lines the HUD was showing stay as they were, older ones come in with the panels (no blink).
  Text alpha under 4 isn't drawn (the font draws it opaque).
- **Pills:** "↓ New: N" (or "↓ To newest") centered in the plate's bottom row, "↓ Mentions: N" at its right end (moved
  into the plate: over NCR's buttons before). Each has its own `Presence`. They are hidden under command suggestions,
  the emoji picker and the GIF viewer. While a menu is open they don't light up. The lines under them can't be
  hovered or clicked (`ChatView.block`), and a hovered line's toolbar moves left of them.
- **Chips:** the pips drawn as rounded chips under the rise (lit foot line on the selected one, unseen dot, the @N
  badge is a pill with a ring in the plate's color), System chip included; the emoji button sits in the plate.
- **Theme tokens:** `ChatTheme` (tokens, `derive`, `liftFor`/`contrast` to WCAG targets, `BoxStyle` presets, cached
  per resolved theme). A light panel gets dark glass (plate = the palette's ink). Optional `chat` block in
  `theme.json` (`HubTheme.Chat`, the record's last component); a bad color keeps the derived one. The built-in
  theme.json files need no `chat` block.
- **Escape hatch without a wire break:** `SettingsData` is unchanged. The new S2C `ChatFlags` (bit 0, value 1 = new look) is
  sent before it, only when the client can receive it (`canSend`). Old clients and old servers keep working. The
  Settings → Chat toggle shows only after the server has sent the flags. Absent = on. The flag is read at
  `ChatScreen.init` (it applies the next time chat opens). Remove the hatch two releases after 1.3.0 ships.
- **VectorLib 0.7.2:** `RoundedBox.draw(graphics, x, y, w, h, style, alpha, flush)` (an alpha multiplier, no
  per-frame styles), additive and checked with japicmp.
- **Reviewed** by a 5-dimension agent review with an adversarial check of every finding: 7 confirmed and fixed
  (pills under the picker/GIF viewer took their clicks; the toolbar covered the Mentions pill; the @N badge covered
  its chip's label; a bad `chat` color turned text invisible; the toggle did nothing on 1.2.x servers; the pill lit
  under menus; the Chat Delay box's click band). 4 were refuted.
- **Dev-tested** (dev server 25598, two clients): all four themes; bed chat (plate stops at Leave Bed, the button works,
  the close leaves no ghost); chat scale 0.5; opacity 0 and 1; toggle off → the 1.2 look and back; empty view;
  pills (New → newest, Mentions → its channel); command list over the input plate; emoji picker; sending. Review
  fixes rechecked in game at GUI scale 3 (426×240): the picker hides the Mentions pill and its emoji take the click;
  the @N badge clears its label; a hovered line's toolbar sits left of the pill; the Chat Delay box's bottom edge
  releases lines. That pass found three more issues, all fixed and rechecked. (1) Lines under the picker or GIF viewer
  were still hovered, so their toolbar drew over the picker and took its clicks; this happened in the 1.2 look too.
  Now `ChatView.cover` takes those panels in either look. (2) Line text drew over the pills (text at z 100 plus the
  font's offset), so the pills now draw at z 150. (3) The first chip's badge ran off the screen edge. A two-agent
  check of those fixes found three more, fixed but not rechecked in game. (a) In the 1.2 look the Mentions tooltip still
  showed over the picker. (b) A pill fading out showed through a panel opening over it; it now snaps away. (c) The
  picker and GIF viewer moved to z 150, so history text no longer shows through them; this was a 1.2.x bug too. Not
  yet tested: NCR in Prism.

**Built (2026-10-17, Eden 1.3.0 — Phase 4b; still VectorLib 0.7.2, no VectorLib change).** Design and final decisions
in the session's `p4b/design-4b.md` + `p4b/resolutions.md` (two critiques resolved there). Implemented in three
stages by agents, reviewed by a 5-lens agent review + adversarial check (2 findings, fixed) and a second
scenario-walk round (1 finding, fixed: picker tab icons now refresh when server emoji images arrive). Differences
from the scope above:
- **Menus:** `ChannelMenu`/`MessageMenu` gained pure `entries(Spec, Actions)`; the new look builds VectorLib
  `ContextMenu`s from them (Mute ▸ with 5 durations, Notifications ▸ as checks, a muted header + Unmute, Mark as read
  disabled with nothing unread). They live in `client/chat/ui/ChatCanvas` — one `CanvasEmbed` per host at **z 300**
  (over the history, pills 150 and suggestions 200; under every tooltip), covering (0,0)–(w,h-16), idle (not drawn,
  no allocation) when empty. ChannelMenu opens upward from its chip (the canvas clamp puts its bottom at h-16);
  MessageMenu's bottom sits at the pointer (`ChatMenus.height` mirrors ContextMenu's private sizes; a reflection
  test fails if they drift). Esc closes all levels; a click off a menu closes it and is consumed; a held row never
  fires after Esc/resize/theme change (capture cancelled). The 1.2 hand-drawn menus stay for the old look.
- **Picker:** `HubEmojiPicker` + `StickerNode` moved (git mv) to `client/chat/ui` as the public, host-agnostic
  `EmojiPicker` (State/Host/Look/Panel, sized by column count: 8 = 108 wide in the hub, 10 = 132 in the chat) and
  `StickerNode`. `ChatText`/`ChatMentions` stay in `client/hub` (the chat uses neither). The hub gets
  `client/hub/HubPicker` (parchment look, Toasts, refocus of the chat field after a pick — fixes the hub losing
  focus after a cell click). In the chat the panel rises from h-16 beside the emoji button (150 ms) and lifts away
  (110 ms); search keeps the keyboard across in-panel clicks and Shift-picks; one row per wheel notch. `EmojiFont`
  has a `revision()` counter (favorites, stickers, server list) the panel polls instead of whole-tab rebuilds. The
  new look creates no vanilla search `EditBox`; the old look keeps the hand-drawn picker.
- **Input:** `ChatScreenMixin` merges `charTyped`/`mouseReleased` (no other mod merges them; both end in `super`)
  and implements the `EdenChatHost` duck; new `InBedChatScreenMixin` (keyPressed HEAD) so Esc over a menu/picker
  doesn't wake the player and Enter in the picker search doesn't send.
- **HUD:** per-message rounded strips (docked, radii 0,2.5,2.5,0, gap ≥ 1 physical pixel, alpha
  `min(255, 300·textBackgroundOpacity)`), crossfading with the plates on open/close, Chat Delay strip, only with the
  rounded-box shader. Setting `chat_hud_style` (Settings → Chat → Closed chat lines: Rounded strips / Classic, shown
  with the new look). `ChatFlags` gained a "known bits" half (bits 16+, `chat/ChatFlagBits`) so a later flag reads
  as on, and its option stays hidden, on a server that doesn't know it.
- **Accepted behavior changes:** right/middle click on a menu row does nothing; VectorLib's click sound on picks;
  rows 14 tall, menus ≥ 110 wide; submenus after a 180 ms rest or a click; menu state is a snapshot at open; rise 4;
  the picker's tabs wrap into a grid; panel 132 wide; opening resets the search; a blank Enter in the chat search
  inserts nothing (the hub keeps inserting the tab's first emoji).
- **Tests:** 76 JUnit (menus entries, height mirror, picker layout, flag bits, HUD strip geometry, theme tokens).
- **Dev-tested 2026-10-17** (GUI scale 3): strips with mention tint and gaps; channel menu, Mute ▸ 15 min → muted
  header + Unmute → unmute; Notifications ▸ check; one Esc; message menu upward from the pointer, Reply inserts
  "@Bob " and Enter sends the line; picker search, Shift-picks keep the search, Enter inserts the first match and
  doesn't send, Esc closes only the picker; bed chat (menu + Esc and picker + Esc don't wake, Enter in search
  inserts); Classic ↔ Strips live; hub party picker (pick keeps the field's focus, Shift-picks); old look's menus and
  picker unchanged. Then GUI scales 1, 2 and 3 (4 needs a taller window than the 1280×720 test display) and all four
  themes for the channel/message menus and the picker (readable on each; the focus border in the theme's accent),
  strip gaps visible at GUI 1. Not yet: NCR/owo/Debugify in Prism, `-Dvectorlib.noSdf`.

### Phase 5 — Eden 1.3.1: tab strip replaces the pips (4 d)

**Goal:** GM's channel chips with the eased lit plate, badges and a channel-switch slide.

**Files:**
- New: `client/chat/ui/ChatTabStrip.java`.
- Changed: `EdenChatScreen`, `ChatScreenMixin` (the pip code goes), and `hub/HubSettings.java` (`CHAT_FOCUS` is dropped; no migration).
- Docs: `docs/PLAN-chat-activity.md`, README.

**Dev tests:**
- Switching by click, by Ctrl+Tab and Shift+Tab, and by clicking a `[Local]` tag.
- Joining or leaving a party or guild adds or removes its tab live.
- Dots and @N match the counters.
- A narrow chat wraps the strip.
- uk_ua label widths.

### Phase 6 — Eden 1.3.2: closed-chat toasts that fold into pills, and the editor (4 d)

**Files:**
- New: `client/chat/ui/{ChatToasts, ChatToastEditorScreen}.java` (the editor is a VL `CanvasScreen`).
- Changed: `hub/HubSettings.java` (new keys) and `client/hub/SettingsTab.java`. The `SettingsTab.chat` section (`:137-181`) is pulled out into a shared `client/chat/ChatSettings.build(Settings, data)`.

**Dev tests:**
- Muted and Nothing channels raise no toast.
- A card folds into a pill after the hold time; opening the channel clears its pill.
- Hidden under F1 and while any screen is open.
- The editor's settings survive a restart and a GUI-scale change.
- No HUD cost when the queue is empty (profiler).

### Phase 7 — Eden 1.3.3: in-chat tools (5 d)

**Scope:**
- Ctrl+F search that keeps the draft.
- A settings popover hung from the gear button.
- @ player picker with heads.
- Word alerts (server-validated).
- Help "!" button.
- Screen-local channel hotkeys.
- Wrapped input display, up to 4 rows.

**Files:**
- New: `client/chat/ui/{ChatSearch, ChatSettingsPanel, PlayerPicker}.java`.
- New: `client/chat/ChatAlerts.java`.
- Changed: `hub/HubSettings.java` (alerts validation).

**Dev tests:**
- The draft survives a search, and Enter never sends while searching.
- Search over 1000 lines.
- `арена*` matches "Арени"; own lines stay silent.
- A 25th rule is refused by the server.
- Hotkeys never leak a typed character.
- Caret and selection across wrapped rows in Ukrainian; suggestions with a wrapped input.

### Phase 8 — VectorLib 0.8.0 `UiFont` (5 d; needs approval) and Eden 1.3.4 font wiring (1.5 d)

**Goal:** a smooth font that is opt-in per canvas. Built-in themes stay `pixel` during the trial. Phase 8 can start right after Phase 2, in parallel with Phases 4–7.

**Dev tests:** see the smooth font spec.

### Phase 9 — Eden 1.3.5: move, resize, zoom, lock (3 d)

**Scope:**
- Move button; 9×9 resize grip; lock (Ctrl+click the move button).
- Ctrl+=, Ctrl+−, Ctrl+0 and Ctrl+wheel zoom, 50–200% in steps of 5.
- Layout stored as anchor plus fractions in the client file.
- Command suggestions translated to the moved input.
- Flip mode (input on top) is optional.

**Dev tests:**
- Move and resize at GUI 2 and 3, then change the GUI scale and the window size.
- The suggestion list sits right at the moved input.
- Hover and click at 50% and 200% zoom.

### Phase 10 — Eden 1.3.6 (any time after Phase 0): server extras (3.5 d)

**Scope:** async daily archive, slow mode, staff spy (see the server section).

**Files:**
- New: `chat/{ChatArchive, SlowMode, ChatSpy}.java`.
- Changed: `config/EdenConfig.java` (`Chat`), `chat/ChatChannels.java`, `chat/ChatMarker.java` (a `~spy` flag).

**Dev tests:**
- Slow-mode refusal; then the bypass node, granted live.
- Spy receives a Local line from 300 blocks away; revoking the node stops it.
- `jq . logs/eden_chat/*.jsonl` is valid.
- spark shows no tick spikes at about 1000 lines per minute.
- `/stop` flushes the archive.

### Phase 11 — Eden 1.4.0: item, recipe and quest chips (5 d; quests +1.5 d)

**Goal:** chips that the server resolves, shown inline. The server and client must update together.

**Dev tests:**
- A renamed, enchanted sword from a **chest** slot shows its exact tooltip on Bob's client.
- A forged `:item#999:` or a stale stash id stays literal text.
- Discord gets `[Name]`.
- Works with and without EMI.
- Copy, reply and search treat a chip as `[Name]`.

### Phase 12 — optional, after the core

| Item | Effort |
|---|---|
| Eden 1.5.0: detached chats, up to 4 | 6 d |
| PM tab using GM's pin model, sent as a signed `/msg` underneath | 2.5 d |
| Hub look adoption: theme `shape` block, `Skin.Box` popups, a shadow-only box behind nine-slice panels, `HubNotifications` badges as `RoundedBox.circle` with glow | 3 d |
| VL SVG paths with anti-aliased capsule strokes | 2.5 d |
| Region backdrop blur | 3 d, plus 1–2 MB VRAM |

### Effort summary

| Phase | Release | Days |
|---|---|---|
| 0 | Eden 1.2.5 | 1.5 |
| 1 | Eden 1.2.6 | 3 |
| 2 | VL 0.7.0 | 6 |
| 3 | VL 0.7.1 | 3 |
| 4 | Eden 1.3.0 | 5.5 |
| 5 | Eden 1.3.1 | 4 |
| 6 | Eden 1.3.2 | 4 |
| 7 | Eden 1.3.3 | 5 |
| 8 | VL 0.8.0 + Eden 1.3.4 | 6.5 |
| 9 | Eden 1.3.5 | 3 |
| 10 | Eden 1.3.6 | 3.5 |
| 11 | Eden 1.4.0 | 5 |
| **Core total** | | **about 50 days** (about 38 calendar days with Phases 8 and 10 in parallel; subagents allowed) |

**First GM look (Phases 0–4):** about 19 days.

---

## VectorLib 0.7.0: SDF rounded-box node spec

### API (`net.ledok.vectorlib.client.canvas`)

```java
public enum BorderAlign { INSIDE, CENTER, OUTSIDE }      // INSIDE default (CSS/GM); CENTER = today's ShapeNode stroke geometry

public record BoxShadow(float dx, float dy, float blur, float spread, int color, Kind kind) {
    public enum Kind { OUTER, INSET, GLOW }              // OUTER clipped under the box (CSS); GLOW = unclipped, no offset
    public static BoxShadow outer(float dx, float dy, float blur, float spread, int argb);
    public static BoxShadow inset(float dx, float dy, float blur, float spread, int argb);
    public static BoxShadow glow(float blur, float spread, int argb);
    public static List<BoxShadow> elevation(int level);  // 0..4, ambient + key, black
}

public record BoxStyle(int fill, int fillEnd, Gradient gradient, int borderColor, float borderWidth,
                       BorderAlign borderAlign, float tl, float tr, float br, float bl, List<BoxShadow> shadows) {
    public enum Gradient { NONE, VERTICAL, HORIZONTAL }
    public static final float PILL = Float.POSITIVE_INFINITY;
    public static final BoxStyle NONE;
    public static BoxStyle fill(int argb);
    // withFill, withGradient(Gradient,int,int), withBorder(int,float[,BorderAlign]), withRadius, withRadii,
    // withShadow, withShadows, withElevation  — immutable; hosts prebuild presets, no per-frame allocation
}

public class RoundedBox extends CanvasNode implements layout.Resizable {
    public static RoundedBox of(float w, float h);
    public static RoundedBox of(float x, float y, float w, float h);
    public static RoundedBox circle(float cx, float cy, float r);
    public RoundedBox bounds(...); size(...); @Override resize(w, h);      // field writes only
    public RoundedBox radius(float); radii(tl, tr, br, bl); pill();
    public RoundedBox fill(int); verticalGradient(int, int); horizontalGradient(int, int);
    public RoundedBox border(int, float); border(int, float, BorderAlign);
    public RoundedBox shadow(dx, dy, blur, spread, argb); insetShadow(...); glow(blur, spread, argb);
    public RoundedBox elevation(int); clearShadows(); style(BoxStyle); BoxStyle style();
    public static void draw(CanvasRenderContext ctx, float x, float y, float w, float h, BoxStyle s);
    public static void draw(GuiGraphics g, float x, float y, float w, float h, BoxStyle s, boolean flush);
    public static boolean accelerated();
    @Override public Rect bounds();                      // box only; shadows are paint-only overflow
    @Override public boolean hitTest(float x, float y);  // CPU SDF <= 0
}
```

**Semantics:**
- **Radii:** `PILL` resolves to `min(w,h)/2`. Every radius is clamped to `[0, min(w,h)/2]`.
- **Shadows:** at most 4 per box. Values are clamped to the encodable ranges, never wrapped.
- **Draw order inside a node:** OUTER and GLOW shadows, then the box (skipped when fill alpha and border are both 0), then INSET shadows.
- **Depth:** all quads of one node share one `ctx.nextZ()` (`VL canvas/CanvasRenderContext.java:25,113-116`). With LEQUAL, painter's order holds.
- **Opacity:** every color passes through `ctx.applyAlpha` (`:94-107`), so there are no uniforms and batching is preserved.
- **`draw(GuiGraphics, …, flush)`:** Eden's chat passes `flush=false` for a run of plates, then calls one `graphics.flush()` before its text. A frame's 20–60 plates become one draw call.

**Additions elsewhere in VectorLib:**
- `CanvasRenderContext.box()` / `sdf()`, where `sdf() = VectorShaders.roundedBox() != null && (graphics != null || !VectorShaders.shaderPackInUse())`.
- `VectorRenderTypes.roundedBox(boolean depthTest)`.
- `Path2D.roundRect(x, y, w, h, tl, tr, br, bl)`, for the fallback.
- `Flex.backgroundBox(BoxStyle)`, which reuses one node across resizes and hooks the factory at `Flex.java:83-96,325-329`.

### Shader

**Vertex format** (36 bytes per vertex). The custom attribute names make it never equal to `NEW_ENTITY`, so neither BufferBuilder's fast path nor Iris's checks fire.

```java
BOX_FORMAT = VertexFormat.builder()
  .add("Position", POSITION).add("Color", COLOR).add("BoxLocal", UV0)
  .add("BoxAux", UV1).add("BoxRadii", UV2).add("BoxParams", NORMAL).padding(1).build();
```

**Rules for writing vertices:**
- Every vertex must write all six elements; BufferBuilder throws "Missing elements" otherwise.
- Use the raw `setNormal(float, float, float)` overload, never the `PoseStack.Pose` one.
- Quads use mode QUADS, with corners in the order TL, BL, BR, TR. The pose is applied on the CPU.

**Packing:**

| Element | Mode 0: box | Mode 1 / 2: outer / glow | Mode 3: inset |
|---|---|---|---|
| Color | fill (per vertex, for gradients) | shadow color | shadow color |
| BoxLocal (float2) | position relative to box center | relative to shadow-rect center | relative to padding-box center |
| BoxAux (short2) | border color `(A<<8\|R, G<<8\|B)` | offset ×8 (±4095) | offset ×8 |
| BoxRadii (short2) | `(tl\|tr<<8, br\|bl<<8)`, 0.5 steps, ≤127; 255 = round | box radii | padding-box radii |
| BoxParams (snorm3, codes 0..127) | x: border ×0.25; y: mode; z: pad flags | x: sigma ×0.25; y: mode; z: spread+63 (×0.5) | same as 1/2 |

**Encoding details:**
- A Normal code is written as `min(1, (c+0.5f)/127f)`.
- It is decoded with `int(floor(n*127.0+0.25))`, which is exact under both GL snorm rules: `c/127`, and the pre-4.2 `(2c+1)/255` rule (macOS GL 4.1).
- No signed codes are stored; spread is stored with a +63 offset.
- Half sizes are not sent. The vertex shader derives them from `|BoxLocal|` minus the anti-aliasing margin `PAD = 2` (plus `3σ` for outer and glow shadows).
- The CPU places geometry using the *quantized* sigma and spread.

**Gradients:** three quads, at rows `-(h+PAD)`, `-h`, `h`, `h+PAD`, with colors c0, c0, c1, c1. The inner edge vertices carry pad flag 0. Horizontal gradients are transposed.

**Fragment shader** (GLSL 150; files `common/src/main/resources/assets/vectorlib/shaders/core/rounded_box.{json,vsh,fsh}`; the JSON declares only `ModelViewMat`, `ProjMat` and `ColorModulator`):
- **Coverage.** The pixel footprint is `upp = sqrt(0.5*(dot(dFdx(vLocal),dFdx(vLocal)) + dot(dFdy(vLocal),dFdy(vLocal))))`, so anti-aliasing is exactly one pixel at any GUI scale, canvas scale, rotation or perspective. `fwidth(d)` is avoided because it breaks at corners. Coverage is `clamp(0.5 - sd/upp, 0, 1)`.
- **Shape.** Inigo Quilez's rounded-box SDF, picking the corner radius by quadrant. The border is blended in premultiplied form against the inner SDF `sd(p, half-bw, r-bw)`.
- **Shadows.** A Gaussian-blurred rounded rect: an exact erf in x per row (Abramowitz-Stegun 7.1.27, error under 5e-4). The straight y band is exact; each corner band uses 4 sub-intervals; everything is clipped to ±3σ. The effective sigma is `sqrt(σ² + upp²/12)`, so blur 0 degrades to a normal anti-aliased edge.
  - Mode 1 (outer) discards under the box.
  - Mode 3 (inset) is `1 - blurred(hole)`, times the box coverage.
  - Interleaved-gradient-noise dither of ±0.5/255 prevents banding.
- **Discard.** Pixels with `alpha <= 0.002` are discarded, so transparent pixels never write depth.
- **Sources.** The vl-sdf research GLSL is used as written, implemented from the public math only (IQ SDF, A&S erf, separable Gaussian integral, IGN). No copied code.

**Render type:** `VectorRenderTypes.roundedBox(depthTest)`:
- Built as a `ShardRenderType` named `vectorlib_rounded_box[_seethrough]`, in the same way as `fill` (`VL render/primitive/VectorRenderTypes.java:67-69,135-163`).
- `BOX_FORMAT`, mode QUADS, buffer 1536, `sortOnUpload` false.
- Shards: `new ShaderStateShard(VectorShaders::roundedBoxOrSafe)`, `TRANSLUCENT_TRANSPARENCY`, LEQUAL with `COLOR_DEPTH_WRITE` (or `NO_DEPTH_TEST` with `COLOR_WRITE`), `NO_CULL`.
- `roundedBoxOrSafe` returns `GameRenderer.getPositionColorShader()` when the SDF shader is null. Attributes 0 and 1 line up, so a race draws plain quads instead of crashing.

### Loader glue

**common, `VL render/primitive/VectorShaders.java`:**
- `ROUNDED_BOX = vectorlib:rounded_box`, `BOX_FORMAT`, and a `volatile ShaderInstance`.
- `loaded(ShaderInstance)` accepts the shader only if `GlStateManager.glGetProgrami(id, GL_LINK_STATUS) != 0`. Vanilla link failures only log (`ProgramManager.java:43-48`).
- `failed(Exception)` nulls the field and warns once.
- `-Dvectorlib.noSdf=true` forces the fallback.
- `shaderPackInUse()` calls `IrisApi.getInstance().isShaderPackInUse()` through a MethodHandle resolved once. It returns false when Iris is absent or its API changed.

**Fabric (`FabricRenderEvents.register`):**
```java
CoreShaderRegistrationCallback.EVENT.register(ctx -> {
    try { ctx.register(VectorShaders.ROUNDED_BOX, VectorShaders.BOX_FORMAT, VectorShaders::loaded); }
    catch (IOException | RuntimeException e) { VectorShaders.failed(e); }  // escaping fails the whole resource reload (GameRenderer.java:754-756)
});
```

**NeoForge (`NeoForgeRenderEvents.register(modBus)`):**
```java
modBus.addListener((RegisterShadersEvent e) -> {
    try { e.registerShader(new ShaderInstance(e.getResourceProvider(), VectorShaders.ROUNDED_BOX, VectorShaders.BOX_FORMAT), VectorShaders::loaded); }
    catch (IOException | RuntimeException ex) { VectorShaders.failed(ex); }
});
```

F3+T reloads swap the instance through the callback, and vanilla closes the old one.

### Fallback

**Used when:** the shader is missing, failed to compile or did not link; `noSdf` is set; or an in-world canvas draws while an Iris pack is active (Iris skips unknown core shaders during world rendering). GUI drawing under Iris and Sodium is unaffected.

**What it draws:** `BoxRenderer` uses `Path2D.roundRect` (per-corner) through `Tessellator.fill`.
- The border is a centered stroke of the path inset by bw/2.
- Each outer shadow becomes 3 stepped, expanding translucent rings; glow becomes 1 ring; inset shadows are skipped.
- Contours are cached per node only while the fallback is active.

### Cost

- No textures, framebuffers or caches.
- One GL program; one shader compile per reload, about 1–5 ms.
- 4 vertices (144 B) per box, plus 4 per shadow and 12 for gradients. Today a stroked roundRect is hundreds of vertices plus O(n²) ear clipping per frame (`Path2D.java:20,83-85`; `Tessellator.java:27,141`).

### Effort and tests (6 d)

**Effort split:**

| Work | Days |
|---|---|
| Shader, format and render type | 2.5 |
| Node, API and fallback | 1.5 |
| Loader glue, blur, IN_CUBIC | 0.5 |
| Tests and docs | 1.5 |

**Tests:**
- **JUnit** `common/src/test/.../RoundedBoxCodecTest`:
  - a Java mirror of the GLSL decode under both snorm rules, for every code;
  - radius and `PILL` clamping;
  - CPU `hitTest`;
  - gradient pad flags.
- **Dev client:**
  - a "Boxes" page in `/vectorlib widgets`;
  - GUI scale 1–4, `fitScale` below 1;
  - rotation and scale animations, opacity fade;
  - `ScrollPanel` clip;
  - F3+T;
  - `-Dvectorlib.noSdf=true`;
  - a deliberately broken `.fsh` in a test resource pack: the reload still succeeds and the fallback is used;
  - one NeoForge `runClient`;
  - an in-world canvas with the Iris pack on and off in the Prism Client;
  - before and after screenshots of an NPCs_LD dialog, the Economy_LD shop and the Eden hub (they must be identical in 0.7.0).

### API.md changes

- **Nodes, after Shapes (`API.md:141`):** a new "RoundedBox and BoxStyle" section covering the API, semantics, presets pattern, the GuiGraphics overload and `accelerated()`.
- **Z-ordering (`:110`):** one `nextZ()` per box covers all its quads.
- **Animation (`:119`):** `Easing.IN_CUBIC`. In 0.7.1 also `Motion.Spring`, `Presence` and `outBack`.
- **ContextMenu (`:543`, in 0.7.1):** submenus, check rows, separators, headers, open and close timings.
- **Presentation (`:766`):** `blurBackground`. In 0.7.1 also `CanvasEmbed`.
- **Gotchas (`:955`):**
  1. Outside a canvas, flush before changing the scissor, because `GuiGraphics.applyScissor` flushes only in managed mode.
  2. Shadows overflow `bounds()` and can be clipped by ScrollPanel or VirtualList viewports.
  3. In-world boxes fall back while an Iris pack is on.
  4. Do not build `BoxStyle`s per frame.
- **CHANGELOG 0.7.0:** state that no default look changes.

---

## Smooth font spec

**Font and license:**
- Noto Sans Medium (body) and Bold (bold runs), v2.015. These are the same files GM uses through javaui (`/home/ledok/Projects/Minestar/javaui/core/src/main/resources/com/silkysoftware/jui/text/fonts/`).
- License: SIL OFL 1.1 with no Reserved Font Name, so a cut-down copy may keep the name. Ship `OFL-NotoSans.txt` next to the files.
- Cut down offline with fontTools to: Basic Latin, Latin-1, Latin Ext-A, Cyrillic plus Supplement (Ґ Є І Ї ’), General Punctuation and Currency (₴). Drop GSUB, GPOS and kern. Keep TrueType outlines (vanilla rejects CFF).
- Size: about 80–120 KB per weight. Files: `VectorLib/common/src/main/resources/assets/vectorlib/font/sans/notosans-medium.ttf` and `notosans-bold.ttf` (lowercase names).

**Provider definition with fallback.** The logical definition of `vectorlib:sans` (and `sans_bold`) is:

```json
{ "providers": [
  { "type": "ttf", "file": "vectorlib:sans/notosans-medium.ttf", "size": 9.0, "oversample": "<int GUI scale>", "shift": [0, 0], "skip": "" },
  { "type": "reference", "id": "minecraft:default" } ] }
```

This **is not shipped as a font JSON file.** A loaded JSON has four problems:
- It bakes one oversample with GL_NEAREST (`TrueTypeGlyphProvider.java:38-46,168`), so text is sharp at only one GUI scale.
- One failed provider drops the whole font id (`FontManager.java:138-149,359-371`). Text then draws as missing boxes, and the `reference` cannot save it.
- It loads on every reload for every player and walks all of unifont on the main thread (`FontSet.java:67-92`).
- Bold is a 1-unit double draw.

Instead, VectorLib builds the same chain in code:
1. **TTF provider.** `new TrueTypeGlyphProviderDefinition(file, 9f, guiScale, Shift.NONE, "")` → `unpack().left()` → `load(resourceManager)`. It runs only on first use, may run on a worker thread, and the file is read through the resource manager, so a resource pack can replace it.
2. **`TunedProvider` wrapper:**
   - shadow offset 0.5;
   - bold offset 0 for the Bold face;
   - each glyph is uploaded with a 1-texel transparent border, and its sheet is switched to `GL_LINEAR`. Vanilla sheets start with undefined contents and pack glyphs with a 1-texel gap, so linear filtering would otherwise bleed. Check this in game.
3. **One `FontSet` per id, reloaded in place** with `set.reload(...)` (`FontSet.java:47-56`). Replacing the set would let `FontManager.lastFontSetCache` serve a closed one (`FontManager.java:63,277-286`).
4. **The "reference" step is a common mixin.** `FontSetMixin` at RETURN of `getGlyph`/`getGlyphInfo`: when a `vectorlib:*` set returns MISSING, it returns the `minecraft:default` glyph. The per-glyph order becomes sans → `minecraft:default` (bitmaps, unifont, Eden's U+FFF00/01 icons; arrows, ★, ✔, ▶, ▌ spoiler bars) → Eden emoji (Eden's existing `mixin/FontSetMixin.java:27-53`) → missing box.
5. **Reinstall and rebuild.** The set is reinstalled after `FontManager.apply` (TAIL) and rebuilt on a GUI-scale change. Both bump `UiFont.epoch()`.

The mixins live in a new `vectorlib.common.mixins.json`, registered by both `fabric.mod.json` and `neoforge.mods.toml`. Common mixins need no refmap.

**API** (`VL text/UiFont.java`):
- `preferred(Mode)`, `active(...)`, `failure()`, `epoch()`.
- `apply(FormattedText)` returns a zero-copy view; `apply(Component)` returns a flattened copy. Only runs whose resolved font is `minecraft:default` change, and the bold flag is kept. Hovers, clicks and insertions carry over. `eden_ld:emoji`, `uniform`, `alt` and other mods' icon fonts are untouched.
- `width`, `widthCeil`, `headByWidth`.
- `VectorCanvas.smoothText(boolean)` (default false) and `TextNode.font(Mode)`. The mode is **opt-in per canvas**, so NPCs_LD, Economy_LD, Arenas_LD and Kits_LD stay pixel until each opts in.
- **Inside VectorLib:** `TextNode`'s cache key adds `epoch()` (`VL canvas/TextNode.java:244-258`). These widgets measure through UiFont when their canvas is smooth:
  - `Label.java:105`
  - `TabBar.java:134,273`
  - `ContextMenu.java:86-87`
  - `settings/TextOption.java:73`
  - `Collapsible.java:151`
  - `TextField.java:64-74,125-127`
  - `TextArea.java:67,87,117,126-129,284` (no more adding up rounded per-character widths)

  While smooth, text origins snap to the framebuffer pixel grid.
- **Falls back to `minecraft:default` when:**
  - the mode is PIXEL;
  - the font failed to load (logged once; `failure()` gives the reason);
  - the GUI scale is 1;
  - the canvas is in the world or the text is a billboard;
  - Force Unicode is on;
  - Caxton or ModernUI is loaded.
- **The font stays on the client.** The server never sends a `vectorlib:*` id, so signed chat is untouched.

**Where the toggle lives (Eden 1.3.4):**
- **Settings → Visual** (`client/hub/SettingsTab.java:195-205`): HubSettings `ui_font`, with values 0 Theme default, 1 Smooth, 2 Minecraft. Default 0.
- **Settings → Chat** and the in-chat popover: `chat_font`, with values 0 Same as text, 1 Smooth, 2 Minecraft. Default 0.
- Both are validated in `HubSettings.set` (next to `:343-385`) and added to `SettingsData` (`hub/HubPayloads.java:2357-2362`). No migration.
- **theme.json** gets an optional `"font": "pixel" | "smooth"`, read in `HubThemes.read`. Parchment's value goes in `HubTheme.java`.

**Default:** all four built-in themes say `pixel` during the trial, so nothing changes for players. After the owner signs off, switching dark, overgrown and parchment to `smooth` is a data-only change.

**Eden wiring:**
- `ChatEntry.lines` wraps `UiFont.apply(text)` (`ChatEntry.java:88-96`). The view row cache and the entry cache include `epoch()` and `chat_font`. Hit tests already use the same styled rows (`ChatPanel.java:354-360`).
- Tab, menu and toast labels go through `apply`.
- The input overlay measures through UiFont only when the chat font is smooth.
- The hub canvas follows `smoothText(...)`. Check Eden's 35 `font.width` sites in 18 files; each either measures through UiFont or stays pixel deliberately.

**Cost** (estimates; measure them and record the results in API.md):
- About +200 KB jar.
- Zero when off.
- When on: under 1 MB in total, including 1–3 sheets of 64 KiB per weight; a few milliseconds on first use.

**Dev tests (VL 0.8.0 + Eden 1.3.4):**
- A "Text" page in en and uk (Ґ Є І Ї ₴ « »).
- GUI 2, 3 and 4 sharpness; GUI 1 falls back.
- Caret and selection in TextField, TextArea and the chat input.
- Emoji, stickers and spoiler bars inside smooth text; bold names.
- Hover and click on links, `:item:` and spoilers.
- F3+T; a resource pack that overrides `minecraft:default`.
- Delete the TTF from a dev jar: clean fallback, and the setting shows the reason.
- Chat scale 0.5–1; hub `fitScale` below 1.

---

## Chat v2 spec

### Screen (Phase 4)

**Swap.** `MinecraftChatScreenMixin` uses `@ModifyVariable(method="setScreen", at=HEAD, argsOnly=true)`. When `screen.getClass() == ChatScreen.class && ClientChatChannel.current() != null` (`client/chat/ClientChatChannel.java:72-74`) and the "New chat look" setting is on, it substitutes `new EdenChatScreen(initial)`. `initial` comes from a `ChatScreenAccessor` getter, since it is private (`ChatScreen.java:29`).
- This covers vanilla (`Minecraft.java:993`) and mods that open a `ChatScreen` directly.
- `InBedChatScreen` (`:1766`) keeps the slimmed mixin path.

**Subclass, always calling super.** This keeps:
- the `instanceof ChatScreen` checks (`isChatFocused`, the HUD skip, ChatPanel hit tests);
- NCR's `init`, `handleChatInput` and `normalizeChatMessage` hooks;
- owo's `keyPressed`;
- Debugify and Eden's `CommandSuggestionsMixin.java:52-61`;
- narration and `insertText` (Reply, suggest-command clicks);
- ↑/↓ history;
- `handleChatInput` → `connection.sendChat`/`sendCommand`: signed, 256-character cap.

**Silent input (no EditBox is replaced).** Two `@WrapWithCondition`s in `ChatScreenMixin`, active only when `this instanceof EdenChatScreen`:
- one drops the input-row fill (`ChatScreen.java:180`);
- one drops `input.render` (`:181`).

The vanilla anonymous EditBox stays the model for editing, focus, narration and suggestions. `InputOverlay` paints:
- the value, wrapped to up to 4 rows;
- the caret (1×10, blinking every 500 ms) and the selection (cursor and highlight positions through an accessor);
- the prefix glyph in the channel color, or "›";
- the placeholder;
- Brigadier coloring, by reusing the formatter that CommandSuggestions installs.

Mouse clicks on the overlay map to an index, then `moveCursorTo` and `setHighlightPos`.

**Suggestions.** Wrap `commandSuggestions.render` (`:185`) with a pose translation of `-(rows-1)*12` (and the pane offset from Phase 9). Offset the mouse in `mouseClicked`/`mouseScrolled` before delegating to suggestions.

**History.** `ChatComponentMixin.render` (`mixin/ChatComponentMixin.java:42-81`) skips drawing while `EdenChatScreen` is open. The screen draws `ChatViews.main()` inside its plate under its own pose (for the open animation), using ChatPanel's vanilla text path (`drawString` on cached `FormattedCharSequence` rows). `getComponentStyleAt` → `ChatComponentMixin.java:69` → the view's hit test, against the final (non-animated) rect.

**Look.** Ported from GM `client/gui/ChatChrome.java:42-67,632-643` and `ChatPane.java:474-490`:
- **Stack:** tab strip, 2-unit gap, history plate, 2-unit gap, input plate (`rows×12+10` tall), 2 units above the bottom edge.
- **History plate:** `chat.plate` at alpha 224×opacity, 1-unit `chat.border`, radius 3.
- **Input plate:** `chat.plate_input` at alpha 255×max(0.6, opacity); `chat.focus_border` while it has the keyboard.
- **Opacity** comes from vanilla's Text Background Opacity.
- **Fades** multiply into vertex colors on the CPU. `graphics.setColor` is not used for this, because it flushes (`GuiGraphics.java:183-186`) and would split batches.
- **Draw order per frame:**
  1. all plates through `RoundedBox.draw(g, …, false)`;
  2. one flush;
  3. text;
  4. item icons with one flush;
  5. the `CanvasEmbed` layer (menus, picker, settings) at z+500.

**Motion** (`Motion`/`Easing` from VL 0.7.0/0.7.1):

| What | Timing | Movement |
|---|---|---|
| Open | 200 ms OUT_CUBIC, first `init` only | rise 8 units, fade in |
| Close | 140 ms IN_CUBIC | the HUD lines fade |
| Popups | 150 ms | rise 5 units |
| Hover row and icon glow | spring τ 55 ms | |
| "↓ New: N" / "↓ To newest" pill | Presence 160/120 | rise 4 units |

- The pill is centered, `historyBottom-16`, height 12, foot line `warn` when N > 0. It is driven by `ChatViewport.newCount`.
- The "↓ Mentions" pill is restyled the same way.

**Menus.** `ChannelMenu` and `MessageMenu` (`client/chat/ChannelMenu.java`, `MessageMenu.java`) become builders for VectorLib `ContextMenu` with Mute ▸ and Notifications ▸ submenus, hosted in `CanvasEmbed`. GifViewer clicks keep working (`mixin/ScreenMixin.java:21-32` checks `ChatScreen`).

**Picker.** `HubEmojiPicker`, `ChatText`, `ChatMentions` and `StickerNode` move to `client/chat/ui` (public). They replace the roughly 460-line hand-drawn picker (`ChatScreenMixin.java:560-665`); EmojiTabs and GifViewer stay.

**Mixin shrink.** `ChatScreenMixin` (947 lines) keeps only what bed chat and directly opened ChatScreens also need:
- emoji as you type, `onEdited` (`:873-928`);
- the `handleChatInput` ModifyArg/ModifyVariable for `!x` and stickers (`:931-946`);
- Ctrl+Tab;
- the two WrapWithConditions.

Target: under 300 lines.

**Theme tokens.** `HubTheme` gains an optional `chat` block. `ChatTheme` resolves it once per theme change into prebuilt `BoxStyle` presets: `CHAT_PLATE`, `CHAT_INPUT`, `CHAT_INPUT_FOCUS`, `TAB`, `TAB_LIT` (radii 3,3,0,0), `NEW_PILL`, `HUD_ROW`, `TOAST_CARD`, `TOAST_PILL`, `CHIP_*`, `MENU`.

| Token | Default derivation (GM role → Eden palette) |
|---|---|
| `plate` / `plate_input` | `panel` / `panel_2` |
| `border` / `focus_border` | `hairline` α102 / `accent` |
| `ink` / `ink_dim` | `ink` / `ink_dim` |
| `hover` | `row_bg_alt` α128 |
| `unread` / `badge` | `warn` / HubNotifications color |
| `chip_item` / `chip_recipe` / `chip_quest` | #2F3A4A / #3A2F5A / #2F4A3A, plus text #B3D4FF / #C7B3FF / #9BE8A8 |

- **Light themes:** when `panel` luminance is above 0.5 (parchment, stone_tablet, light third-party themes), the chat uses a **dark-glass variant**.
  - parchment: plate from `ink` #2B1B0E (leather);
  - stone_tablet: plate from `ink` #1C1C1F (slate);
  - ink from the palette's `panel`;
  - accent lightened for contrast.

  This keeps server and rank colors readable: white text, yellow Local, aqua Guild, #00c8c9 names, LuckPerms prefixes.
- **Escape hatch:** HubSettings `chat_new_look`, default on. Off skips the swap. Remove it after two releases.

### Tab strip (Phase 5)

**`ChatTabStrip`** is Eden-owned, drawn in immediate mode, and ported from GM `ChatChrome.java:649-716` and `ChatPane.java:1700-1805`.
- **Chips:** 18 tall, gap 2, padding 6. Rows wrap (row gap 1); there is no overflow menu and no drag reordering.
- **Lit plate:** one `TAB_LIT` plate following the active chip on a spring (τ 45 ms, snaps on the first frame), with a 1.2-unit foot line in the channel color.
- **Hover:** spring τ 60 ms.
- **Unread dot:** 3.6 units, `warn`, pops in with `outBack(1.4)` over 260 ms.
- **@N badge:** kept from Eden.
- **Marks:** strike line when muted; underline when set to mentions only.
- **Tooltip:** shown on hover.

**Tabs:** Global, Local, Party (while in a party), Guild (while in a guild), System, and All (optional, default on). A red read-only Spy tab appears while spy is on.

**Default semantics (decision 1):**
- The viewed channel tab is the typing channel; it sends the existing `ChatChannelSelect`.
- All and System keep the last channel tab as the typing channel, shown by the prefix glyph. Clicking the glyph cycles it.
- `!x` one-shots stay.
- Unmarked lines from the server and other mods show in All and System. In channel tabs they follow `chat_system_in_tabs`, default on, as today.
- Discord stays inside Global with its own hide switch.
- `CHAT_FOCUS` is removed. The `CHAT_HIDE_*` keys (`hub/HubSettings.java:28-34`) become "hidden tabs" and still filter All.

**Input:** right click opens a ContextMenu: Notifications ▸, Mute ▸, Mark read, Hide tab, Chat settings. Ctrl+Tab and Shift+Tab cycle.

**Channel switch:** the history slides ±14 units from the side of the new tab and fades 0.15 → 1 over 220 ms OUT_CUBIC.

### Closed-chat HUD and toasts (Phases 4 and 6)

**HUD (Phase 4):**
- Each line is its own `HUD_ROW` strip: alpha 150×opacity, radius 2.4, gap 1.2, padding 0.6 vertical and 4.8 horizontal, all in one batch.
- Vanilla's fade timing and opacity are kept.
- An optional `chat_hud_style` (Classic/Strips, default Strips) can go back to today's look.

**Toasts (`ChatToasts`, Fabric `HudRenderCallback`):**
- Return immediately when the `ToastQueue` is empty.
- Hidden under F1 and while any screen is open.
- **Items:** one per channel that is unread, not set to Nothing and not the active tab.
  - When muted, only mentions and word alerts produce a toast.
  - With notifications set to Mentions, only mentions and alerts do.
- **Card** (GM `ChatToastOverlay.java:374-412`): radius 8, hairline border, `elevation(2)`. Contents: a 3-unit channel stripe, the channel name, PlayerFace and sender, and the message ellipsized to `floor((h-45)/14)` lines.
- **Pill:** after `hold` (default 4 s, range 1–20; each new line restarts it) the card folds over 200 ms into a pill. The pill is a dot with glow pulsing α 0.45 + 0.55·|sin| over 1.4 s, plus the name and @N. It stays until the channel is read.
- **Stack:** default anchor (0.99, 0.02), scale 75%, card 230×73. It grows away from the nearest corner, gap 4, at most 4 items. Entry is a fade plus a 16-unit slide over 200 ms.
- **Display only.** An unbound KeyMapping "Open chat on newest toast" is the way in.

**Editor (`ChatToastEditorScreen`, a `CanvasScreen`):**
- A preview with 2 cards and 1 pill. Drag to move; ↔ and ↕ handles; wheel scales ×1.04 per notch.
- Bar: On/Off, hold −/+, Reset, Done.
- Writes the client file once, on release.

### Windows: move, resize, extra chats (Phase 9; optional Phase 12)

**One chat:**
- Move button, resize grip (double-click resets), lock (Ctrl+click), and zoom 50–200%.
- Layout stored as an anchor corner plus screen fractions, with the size in GUI units at 100%. It is clamped again on window or GUI-scale changes.
- Saved in the client file, never on the server: it depends on the machine's resolution.

**Extra chats (optional):** GM's model (`client/chat/ChatLayouts.java`, MAX lowered to 4).
- Dragging a tab more than 6 px out creates a chat. Each chat is a `ChatWindow` with its own `ChatView` and HUD.
- One shared vanilla EditBox moves to the focused pane, chosen by a click.
- Command suggestions follow the focused pane.

### Search (Phase 7)

- **Ctrl+F** stashes the draft and caret, empties the input, and calls `commandSuggestions.setAllowSuggestions(false)`.
- **The query** filters the active tab's `ChatStore` entries by `entry.plain()` or the sender name (case-insensitive, `Locale.ROOT`). It is part of the row-cache key, and a new query resets the scroll.
- **Badge:** "found: N", in `danger` when N is 0.
- **Leaving:** Enter, Esc or Ctrl+F restore the draft. Enter never reaches `handleChatInput` while searching.
- **Esc order:** search, then popups, then the screen.

### Chips (Phase 11)

**Tokens** (in the signed text, which keeps the 256-character cap):

| Token | Meaning |
|---|---|
| `:item:` | the held item; exists today (`chat/ChatStyle.java:161-190`) |
| `:item#<id>:` / `:recipe#<id>:` | a stash reference |
| `:item=<registry id>:` | a default stack only, for EMI-hovered stacks with no slot |
| `:quest=<hex16>:` | optional, needs FTB Quests |

**Stash (the fix for "chest items lose data"):**
- Ctrl+T or Ctrl+R over a slot, in any `AbstractContainerScreen` (Fabric `ScreenKeyboardEvents`, Eden's `AbstractContainerScreenAccessor`), sends `ChipStashPayload(slotIndex)` *before* chat opens.
- Packets are handled in order, so the server reads the slot from the still-open `containerMenu`. It stores a **server-side copy** in a per-player stash (at most 8, expiring after 5 minutes) and returns an id, which the client appends as `:item#<id>:`.
- The server never accepts an ItemStack from the client.

**Server resolution (`ChatStyle.shortcut`):**
- Builds `[Name ×count]`, rarity-colored, with `HoverEvent.SHOW_ITEM` from the stashed stack and an Eden click marker (insertion `eden_ld:chip/<kind>:<id>`).
- At most 4 chips per message.
- Unknown or expired ids stay literal text.
- Discord and hub text get `[Name ×N]`.

**Client:**
- The chip is a RoundedBox plate behind the span, with the icon drawn by `renderItem` at 0.6 scale before one flush. It stays inline, so copy, reply and search see `[Name]`.
- Hover shows the native colored tooltip plus "Click: recipes/uses".
- Click opens EMI `displayRecipes`/`displayUses` (compileOnly, soft dependency).
- Quest chips are checked for existence and visibility on the server, and a click opens the quest book.

### Settings

**HubSettings** (server-validated, follow the player, no migration). New keys:
- `chat_new_look` (on)
- `chat_close_after_send` (on, the vanilla habit)
- `chat_system_in_tabs` (on)
- `chat_toasts` (on)
- `chat_toast_hold` (4)
- `chat_hud_style` (Strips)
- `chat_alerts_on` (on)
- `chat_alerts`: ListTag of `{p, s}`, at most 24 rules, each 1–64 characters, no `§` or control characters, sound 0–6
- `ui_font` (0)
- `chat_font` (0)
- `reduce_motion` (off): sets `Animation.enabled(false)` and snaps the springs

**Reused keys:** `CHAT_NOTIFY` (GM's All/Mentions/Nothing mode, persisted, unlike GM's), `CHAT_MUTE`, `CHAT_HIDE_*`, `CHAT_DEATHS`/`ADVANCEMENTS`, `MENTION_VOLUME`, and vanilla's chat scale, width, height, opacity and line spacing.

**Client `config/eden_ld/chat_client.json`** (per machine): layout, toast placement and size, channel hotkeys, `help_seen`. It is written only on drag release or screen close.

**In-chat popover (`ChatSettingsPanel` in CanvasEmbed):**
- Hangs from the gear button: Presence 190/130, scale 0.92 → 1, clicks ignored until 85% in.
- Pages: General, Channels, Word alerts, Keys.
- Built by the shared `ChatSettings.build`, which the hub uses too.

**Hotkeys:** screen-local only (Ctrl+1..9, Alt+letter), captured with GM's rules (`client/chat/ChatKeys.java:242-290`):
- Ctrl, Alt or an F-key is required.
- Esc, Enter, F1, F2, F3, F5, F11 and the chat's own Ctrl shortcuts are refused.

There are no KeyMappings, so nothing collides in the 254-mod pack.

### Word alerts (Phase 7)

- **Matcher:** GM syntax (`common/.../chat/ChatAlerts.java:211-257`): `a|b`, `word(s)`, and a trailing `*`. Whole words, case- and Unicode-insensitive. It is a hand-written token matcher, never a user regex.
- **Never matched:** inside chip tokens, URLs, `:sticker:`, the sender part, or your own lines.
- **Effect:**
  - Matched words are recolored at wrap time by splitting styled runs, so hovers and clicks are kept.
  - The line gets the mention stripe in `good` and counts as a mention for @N, toasts and Mentions mode.
  - The rule's sound plays (pling, bell, chime, harp, amethyst, xp, none), at most once per 300 ms, at `MENTION_VOLUME`.
- **No auto-reply.**

### Server extras (Phase 10)

**Hook:** `ChatChannels.deliver` (`chat/ChatChannels.java:258-360`), which sees every channel line with its resolved recipients. Discord inbound arrives in `onGameMessage` (`:378-436`).

**Archive (`chat/ChatArchive`):**
- One daemon single-thread executor. `deliver` only enqueues a record: `{time, channel, uuid, name, text, dim, x, y, z}`.
- The writer keeps one `BufferedWriter` open on `logs/eden_chat/<yyyy-MM-dd>.jsonl`. It flushes every 1 s or 64 lines and rotates at midnight.
- It drains on `SERVER_STOPPING` with a 2 s timeout, and deletes files older than `archive_days` (default 30, range 1–365) on the executor.
- No I/O happens on the server thread.

**Slow mode (`chat/SlowMode`):**
- `slowmode_seconds` per channel, all 0 by default (0–600).
- Checked in `onChat` (`:176-204`) and in `send`. The timer starts only on accepted lines, and the refusal states the seconds left.
- Bypass: `eden_ld.chat.slowmode.bypass` through `compat/LuckPermsCompat.java:43-50`, falling back to op level 2.

**Spy (`chat/ChatSpy`):**
- `/ch spy [on|off]`, permission `eden_ld.chat.spy`. The state lives in memory, is rechecked on every line and is logged.
- Spies receive out-of-range Local lines and other groups' Party and Guild lines as system copies with a `~spy` marker flag (`chat/ChatMarker.java`).
- Eden clients file them under the Spy tab without counting them as unread.
- PMs are excluded (decision 4).

**Config:** server `chat.json` (`config/EdenConfig.java` `Chat`, about `:190`) gains `archive`, `archive_days`, `slowmode_seconds` and `rate_limit`, all clamped on load.

### Reused from GM, kept from Eden

**Adapted from GM** (each file gets the header "Adapted from GardenMyth (ProstoMaslo), used with permission"):
- the layout constants;
- the motion catalogue and the `UiMotion` behavior, rewritten as VL `Motion`;
- the tab chip and lit-plate drawing;
- the "↓ New" pill;
- toast and pill logic and the editor;
- the settings popover structure;
- the key-capture rules;
- the alert matcher syntax;
- search behavior;
- the @ picker;
- the archive record shape;
- slow-mode and spy semantics.

**Kept from Eden:**
- vanilla `ChatComponent` as the source of truth, plus `ChatStore`/`ChatMarker`;
- signed delivery with styled text laid over it;
- MessageActions; mentions plus `MentionSound`; ×N;
- EmojiFont, stickers and GIFs;
- the Discord bridge;
- `HubSettings` validation;
- `ChatHistory` replay;
- the translations.

---

## Risks and mitigations

| Risk | Mitigation |
|---|---|
| Signed chat or other mods' ChatScreen hooks break | `EdenChatScreen` is a subclass that calls super everywhere. The EditBox and CommandSuggestions are never replaced; they are only hidden through `@WrapWithCondition`. No send payload. Test in the Prism Client with NCR, owo and Debugify (chat report screen, NCR button). |
| Command suggestions are anchored to `screen.height-12` | The input stays at the bottom until Phase 9. Suggestion render is translated and the mouse offset in both directions. Test at GUI 1–4 and with window resizes. |
| VectorLib changes spread pack-wide (highest jar-in-jar wins) | RoundedBox, UiFont and blur are opt-in. Skin.Flat and the Window shadow switch only after an approved demo. Before and after screenshots of NPCs_LD, Economy_LD and Arenas_LD every release. |
| SDF renders wrongly on one driver, or registration fails a reload | GLSL 150; snorm decode exact under both rules; try/catch in both loader hooks; `GL_LINK_STATUS` check; `-Dvectorlib.noSdf`; a broken-shader resource-pack test; in-world fallback while Iris is on. |
| Smooth font: measured width differs from drawn width; bleed and blur with linear filtering | Opt-in per canvas; every listed widget measures through UiFont; the epoch is in every cache key; 1-texel glyph border; pixel snapping; trial default `pixel`; GUI 1 falls back. |
| Fading smooth text thins out (the intensity shader discards alpha < 0.1) | Accept for the trial. A custom text render type is a later option. |
| Light themes become unreadable | Dark-glass chat variant derived from the palette. Server and rank colors are never remapped. |
| Per-frame cost creeps up | Counters cached by `ChatStore.version()`; presets prebuilt; springs are fields; one plate batch per frame; toasts return early when empty. Compare the profiler against the 1.2.6 baseline every phase. |
| Chip stash abuse | At most 8 per player; 5-minute expiry; the stash payload is rate-limited; the slot index is checked against the menu; the server copies the stack. |
| Archive holds personal data; a hard crash loses about 1 s | Retention is set by `archive_days`; the files are under `logs/` (server staff only); PMs are excluded; flush every 1 s. |
| The ChatScreenMixin and EdenChatScreen code paths drift | The mixin keeps only bed-chat essentials (under 300 lines). Bed chat gets the same `onEdited` and `handleChatInput` behavior through the shared mixin. |
| A hub-chat rate limit that is too tight blocks real chat | Limits in config (6 per 5 s); two-client test. |

---

## Owner's answers (2026-10-17)

1 yes · 2 yes (dark plates on light themes) · 3 yes (VectorLib 0.7.1 and 0.8.0 APIs approved) · **4: PMs wanted now,
like GardenMyth's** — the PM tab (GM's pin model, sent as signed `/msg` underneath, ~2.5 d) moves from Phase 12 into
the core, right after the tab strip (Phase 5b); spy and archive still leave PMs out · 5 yes (pack-wide restyle after the
"Boxes" demo) · 6 yes (Noto Sans, trial off; dark/overgrown/parchment smooth after sign-off; stone_tablet pixel) ·
7 yes (core stops at one movable chat + toasts + PMs; detached chats, quest chips, region blur stay optional).

## Decisions asked (2026-10-17, answered above)

1. **Tab means typing channel.** The viewed channel tab is where you type. All and System keep the last channel; `!x` stays; `CHAT_FOCUS` is removed; unmarked lines still show in channel tabs. **Default: yes.** This changes Phase 5.
2. **Chat plates on light themes.** **Default: dark glass derived from the theme** (parchment leather, stone_tablet slate). The alternative is light plates with remapped server and rank colors. This changes Phase 4.
3. **VectorLib APIs beyond the approved SDF:**
   - 0.7.0: `Easing.IN_CUBIC`, opt-in `blurBackground`.
   - 0.7.1: `Motion`, ContextMenu submenus and animation, `Skin.Box`, `CanvasEmbed`.
   - 0.8.0: `UiFont` with two Noto Sans files of about 100 KB each and common font mixins.

   **Default: approve all.** If 0.7.1 is declined, Eden carries private copies (about 2 more days). If 0.8.0 is declined, the font becomes Eden-only (`eden_ld:smooth`, chat and hub labels only).
4. **PMs.** **Default:** no PM tab in the core; spy and archive exclude `/msg`. Phase 12 PM tab (pin model over signed `/msg`) only on request.
5. **Pack-wide restyle.** Switch `Skin.Flat` to anti-aliased boxes and Window to a real `elevation(2)` shadow for every VectorLib mod. **Default:** opt-in now; default-on in a later 0.7.x after you approve the "Boxes" demo.
6. **Smooth font rollout.** **Default:** Noto Sans Medium/Bold, off during the trial (themes say `pixel`), then smooth for dark, overgrown and parchment, with stone_tablet staying pixel. Other mods stay pixel until each opts in.
7. **Scope cut-off.** **Default:** the core stops at one movable, resizable chat plus toasts. Detached chats, flip mode, quest chips, SVG stroked icons and region blur are optional Phase 12 items.

---

## Review amendments (2026-10-17) — these override the plan above

An adversarial review re-read the code and found the following. Each item is a required change to the plan.

**Before Phase 0 / Phase 1**
1. **One host for every chat screen, bed chat included.** `InBedChatScreen` is created by vanilla (`Minecraft.java:1766`)
   and can't be swapped, and shrinking `ChatScreenMixin` would strip bed chat (and `chat_new_look=off`) of the channel
   view, menus, Reply/Copy clicks, GIF viewer and picker. Build the new UI as a host-independent `EdenChatUi` driven
   from `ChatScreenMixin` for every `ChatScreen`; `EdenChatScreen` (if kept) is only a thin subclass shell. Keep the full
   1.2.x path behind `chat_new_look=off` until the hatch is removed. ChatView ownership in Phase 1 follows this.
2. **Counters need a seen epoch.** `seen` changes without a `ChatStore.version()` bump (`ChatEntry.markSeen`,
   `ChatPanel.render`, `ChatActivity.markRead/clearUnseen`) and mutes expire by the clock. Key `ChatCounters` on
   `version + seenEpoch + next-mute-expiry`.
3. **Reconfiguration: snapshot, don't rebuild.** `restoreState` only refills vanilla's 100 lines, and replaying through
   `ChatActivity.added` would replay sounds/unseen/toasts. Snapshot `ChatStore` at `storeState` RETURN and swap it back
   at `restoreState` TAIL; never replay through `added`.
4. **Phase 0 mute check must not query SQL on the server thread.** BanHammer's `getPunishments/isPunished` hits its
   database synchronously. Cache mute state per online player (load async at join, invalidate through
   `BanHammerCompat.onPunishment` and on expiry); also list `/p`, `/g`, `/l` in BanHammer's muted-commands config.

**Before Phases 2–4, 7, 8, 11**
5. **Smooth font sheets:** `NativeImage` upload resets the filter on every glyph (`setFilter(blur, …)`), so "switch the
   sheet to GL_LINEAR" doesn't stick — the tuned provider builds its own bordered image and uploads with `blur=true`.
   1.21.1 TTF loading is FreeType (CFF works; the reason given was wrong). VectorLib must close its own provider on every
   rebuild (FontManager doesn't), or each F3+T / GUI-scale change leaks an FT_Face.
6. **Silent input overlay** also needs: the grey inline suggestion (`EditBox.suggestion`), `displayPos`, the formatter's
   `(substring, startIndex)` call, x-translation of the suggestion list/usage text (`overlayX(start) − getScreenX(start)`)
   with matching mouse offsets, plate clicks mapped before super, and measuring with `fontFilterFishy`.
7. **Enter with `chat_close_after_send=off`:** let super handle Enter (owo/NCR hook there) and suppress the following
   `setScreen(null)` with a one-shot flag; only search mode may consume Enter before super.
8. **No binary break in VectorLib 0.7.1:** `ContextMenu.Item` is a public record called directly by Eden (WaystonesTab,
   PlayerCard) and NPCs_LD (GraphEditorScreen, GraphView). Keep its components; add submenus/check rows as separate
   entry types (or keep explicit constructors). Add a binary-compat check (japicmp) to VectorLib releases.
9. **Chip ids must not ride on the visible span's insertion** (shift-click inserts it): put them on an empty sibling like
   `ChatMarker`, or a ClickEvent sentinel caught in `ScreenMixin`.
10. **Stashing from an open container:** opening chat from a container desyncs the menu. Ctrl+T/Ctrl+R stash the item and
    queue `:item#id:` into a pending draft inserted next time chat opens; the payload carries `containerId` + `stateId`,
    checked by the server against `player.containerMenu`.
11. **Shadows must not write depth:** give OUTER/GLOW shadows their own render type (color write only, still LEQUAL);
    batch all shadows before all boxes.
12. **GuiGraphics facts corrected:** screens and the HUD are unmanaged and `fill`/`drawString` already flush; the real rule
    is "flush pending boxes before any `setColor` or immediate blit" (`ChatPanel.drawHead` changes `setColor`) — draw
    heads with vertex alpha.
13. **Shader attributes:** UV1/UV2 are SHORT via `glVertexAttribIPointer` → GLSL `ivec2`, mask `& 0xFFFF`; shader JSON
    entries use namespaced names (`vectorlib:rounded_box`). Covered by `RoundedBoxCodecTest`.
14. **Word alerts:** `*` is a prefix match (`арен*` matches "Арени", `арена*` doesn't); alerts need their own C2S payload,
    handler and `SettingsData` field (HubSettings is int-only) — +1 day in Phase 7.
15. **GM references:** `UiMotion` is `GM neoforge/.../client/javaui/UiMotion.java`; `ChatLayouts` is
    `GM common/.../chat/ChatLayouts.java`; key-capture rules are `ChatKeys.java:85-99`. ChatChrome, ChatToastOverlay,
    ChatToastEditorScreen, ChatSettingsPanel and ChatHistoryView are javaui code (rewrites, not ports); only ChatPane and
    ChatHudOverlay are plain GuiGraphics — +2–3 days across Phases 6–7. GM's plates use backdrop blur; the core look here
    has none (owner keeps Eden's styles; region blur stays optional).
16. **Emoji picker is a refactor, not a move:** it takes `HubScreen` + `Supplier<TextField>` and hardcodes EdenParchment
    colors; refactor to an `insert(String)` callback with ChatTheme colors, keep the `eden_ld.emoji_picker` gate — +1 day.
17. **VectorLib approval scope:** Phase 2 also ships `Easing.IN_CUBIC`, opt-in `blurBackground`, per-corner
    `Path2D.roundRect`, `Flex.backgroundBox` and the GuiGraphics overload — covered by the owner's approval of the
    rounded-box work (2026-10-17); 0.7.1's `Motion`/`ContextMenu`/`Skin.Box`/`CanvasEmbed` and 0.8.0's `UiFont` still
    need a yes (Decision 3).
18. **Re-baselined effort:** split Phase 4 into **4a** (host, overlay, plates, motion; old menus kept) and **4b** (VectorLib
    menus, picker refactor, HUD strips), ~9 days together. Core total **≈ 58–62 working days** (not 50); first GM look
    (Phases 0–4a) ≈ 20 days.
19. **Testable model:** pull `Minecraft.getInstance()`/`HubState` out of the model as injected self-UUID, clock and
    settings (or use fabric-loader-junit) so Phase 1's JUnit tests can run.
20. **UiFont audit list by grep:** add `TwoColumnText` and `BillboardText`; Eden has ~56 `font.width`/`Font` sites, not 35.
21. Minor: `themes/parchment/` exists (textures, no theme.json); `ChatComponentMixin` uses `@Inject`s, not redirects.

**Verdict of the review:** Phase 0 can start once amendment 4 is applied; Phase 1 with amendments 1–3. Amendments 5–17
before their phases; 8 before VectorLib 0.7.1 ships; 9–10 before chips.
