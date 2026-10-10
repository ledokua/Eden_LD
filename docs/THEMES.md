# Making hub themes and profile backgrounds

Two kinds of looks can be added without code, from a resource pack (shipped with the modpack; F3+T
reloads) or from Eden_LD's own assets:

| | What it changes | Chosen by | Files |
| --- | --- | --- | --- |
| **Hub theme** | the whole hub: panels, buttons, text colors | each player, Settings → Visual | folder `assets/<ns>/textures/gui/themes/<name>/`: `theme.json` + 4 textures |
| **Profile background** | the player's profile page, Home card and Friends card (seen by everyone) | each player, Profile → Background | folder `assets/<ns>/textures/gui/profile_backgrounds/<name>/`: `background.json` + 1–3 pictures |

Each look is one folder — its JSON sits next to its images — and the folder's name is the look's
name.

Both are ids `<ns>:<name>`. Both are locked until the server frees them (config) or unlocks them
per player (`/hub theme unlock …`, `/hub background unlock …`; OPs, or the LuckPerms nodes `eden_ld.admin.themes` / `eden_ld.admin.backgrounds`). Every player needs the files;
the server only stores which id each player picked.

## Sizes at a glance

The hub is a 440 × 252 canvas (GUI units) scaled to the window. Pictures are **scaled to cover**
their spot and centered — whatever doesn't fit the spot's shape is cut off equally on both sides
(or top and bottom). So the shape matters more than the size: keep important detail in the middle.

| Part | Spot on screen (GUI units) | Spot's shape | Image to make (power of two) | What gets cut |
| --- | --- | --- | --- | --- |
| Hub panel (`panel.png`) | stretched nine-slice | any | **48 × 48**, 8 px border (fixed) | nothing — corners stay, edges stretch |
| Hub buttons (`button_*.png`) | stretched nine-slice | any | **32 × 16**, 4 px border | nothing |
| Profile page (`texture`) | ~416 × 212 | **~2:1** | **1024 × 512** or 512 × 256 (2:1) | ~1% |
| Home card (`home_card`) | ~416 × 38 | **~11:1** | **1024 × 128** (8:1) | top and bottom ~13% each |
| Friends card (`card`) | ~110–165 × 36–50 | **~2.5:1 – 3.4:1** | **512 × 128** (4:1) | sides ~7–19% each |
| Background picker thumbnail | 96 × 46 | ~2:1 | uses `texture` | — |

- **Power of two**: every size above except the panel is a power of two. The panel is 48 × 48
  because the hub reads it that way (Minecraft doesn't mipmap GUI textures, so it's safe).
- **Resolution**: at GUI scale 3 the profile page is ~1250 screen pixels wide, so 1024 × 512 looks
  sharp; 512 × 256 is fine for soft art (the built-ins are 512 × 256). The Home strip at 1024 ×
  128 covers ~1250 × 114 screen pixels — about 1:1.
- Friends cards change width with the Friends tab's layout (1, 2 or 3 columns; "On this server"
  cards are narrower), so a `card` picture should survive having its sides trimmed.

## Hub themes

Files of a theme `mypack:frost`:

```
assets/mypack/textures/gui/themes/frost/
    theme.json           the definition (may be just {}: these textures, parchment's colors)
    panel.png            48 × 48, nine-slice with an 8 px border
    button_normal.png    32 × 16, nine-slice with a 4 px border
    button_hover.png
    button_active.png    optional: the open sub-tab or filter (else button_hover); the built-in
                         themes use button_normal tinted ~30% toward the accent
    button_disabled.png
```

`theme.json` — every key optional:

```json
{
  "name": { "translate": "hub_theme.mypack.frost" },
  "textures": "mypack:textures/somewhere/else/",
  "text_shadow": true,
  "palette": {
    "panel": "#1E2A33", "panel_2": "#24323D", "hairline": "#4A6272", "hairline_hi": "#0A1116",
    "row_bg": "#2A3A46", "row_bg_alt": "#24323D",
    "ink": "#E6F0F5", "ink_mid": "#C2D2DB", "ink_dim": "#7E929E",
    "accent": "#7FC8E8", "good": "#7FD08F", "warn": "#E8C070", "danger": "#E87A6A",
    "bar_track": "#121A20", "bar_fill": "#4A9AB8",
    "button_text": "#E6F0F5", "button_text_hover": "#FFFFFF", "button_text_disabled": "#5E7280",
    "panel_tint": "#FFFFFF", "notification": "#E0402F"
  }
}
```

- `textures`: only when the four textures live somewhere else than next to `theme.json`
  (e.g. `"eden_ld:textures/gui/themes/parchment/"` to reuse parchment's with your own palette).
- `palette`: `panel`/`panel_2` panel fills, `hairline`/`hairline_hi` lines and frames, `row_bg`/
  `row_bg_alt` list rows and cards, `ink`/`ink_mid`/`ink_dim` text (strong → faint), `accent`,
  `good`, `warn`, `danger`, `bar_track`/`bar_fill` progress bars, `button_text*` button labels,
  `panel_tint` multiplies the panel texture, `notification` colors tab badges and glows for players
  who keep the theme's color. Colors are `#RRGGBB` or `#AARRGGBB`.
- `text_shadow` suits dark themes.
- Everything is optional: a theme can be only a palette over the parchment textures, or only
  textures. `name` defaults to the lang key `hub_theme.<ns>.<name>`.
- Who may use it: Moderation → Customization → Hub themes (open, groups, hidden, price — like
  backgrounds); per player: `/hub theme unlock <players> <id>`.
- Built in: `eden_ld:parchment` (default), `eden_ld:dark`, `eden_ld:stone_tablet`, and
  `eden_ld:overgrown` (Overgrown Ruins — the example of everything below).

### Patterns, row textures and decorations

Three more optional keys turn a theme from "colors and frames" into a scene. Texture names without a
namespace are files next to `theme.json`; sizes are read from the files.

```json
{
  "background": { "texture": "pattern.png", "mode": "tile", "tile": 32, "opacity": 0.3 },
  "rows": { "texture": "row.png", "alt": "row_alt.png", "border": 3 },
  "decorations": [
    { "texture": "vines_left.png", "anchor": "top_left", "x": -12, "y": -6, "width": 24, "height": 176 },
    { "texture": "crest.png", "anchor": "top", "y": -49, "width": 112, "height": 24 },
    { "texture": "flowers.png", "anchor": "bottom_right", "x": 10, "y": 8, "width": 40, "height": 24,
      "layer": "front" }
  ]
}
```

- **`background`** — a picture over the inside of the panel. `mode` `tile` repeats it every
  `tile` hub units (`tile_height` for non-square tiles; default: the image's own size), cut at the far
  edges — for patterns like bricks or leaves; `stretch` covers the panel with it once. `opacity`
  0–1 lets the panel color show through (patterns read best faint: 0.2–0.4 behind text).
- **`rows`** — a nine-slice texture for list rows and cards (everything that uses `row_bg` /
  `row_bg_alt`) instead of the flat colors. `border` is its edge in pixels (kept, the middle is
  tiled). `alt` is the alternate rows' texture; without it they're the same texture a shade darker.
- **`decorations`** — pictures pinned to the panel (the area under the tabs). `anchor` is a point of
  the panel — `top_left`, `top`, `top_right`, `left`, `center`, `right`, `bottom_left`, `bottom`,
  `bottom_right` — and the same point of the picture goes there; `x`/`y` move it (in hub units,
  negative = left/up). They may reach outside the panel and the window: vines hanging down the
  side, an ornament over the tab bar (`top` with `y` about −49 sits above the tabs), something
  peeking out of a corner. `width`/`height` in hub units (default: the image size; give one and the
  other keeps the picture's shape). `layer` `front` (default) draws over the hub, `behind` under
  it; decorations are never clickable. They never change the hub's size: on a small window they may run off the edge.
  The player can resize the hub, so the panel isn't always 440 × 238: pin decorations to a corner
  or side rather than counting on a fixed width (a centered one stays centered as it grows).
- Pixel art at 1 pixel per hub unit stays crisp (the hub is drawn at the GUI scale).
- **Icons** — a theme may bring its own: `icons/<name>.png` in the theme's folder (names as in
  `docs/ICONS.md`, e.g. `icons/tab_home.png`) replaces that icon while the theme is on; icons it
  doesn't include stay standard. Players can turn theme icons off (Settings → Visual → Theme icons).
- **`tile_edges`** (`true`/`false`, default false): the panel's and the rows' edges **repeat**
  instead of stretching — needed when the edge art has detail (moss, carvings, studs), or it smears
  into long streaks. Their middles always repeat.
- **Buttons and tabs always stretch** their edges and middle. Keep the art there plain — even bevel
  lines and a flat middle — and put details (moss tufts, rivets) inside the 4-pixel **corners**,
  which are never stretched. Overgrown Ruins' buttons are the example.

## Profile backgrounds

Files of a background `mypack:sakura`:

```
assets/mypack/textures/gui/profile_backgrounds/sakura/
    background.json   the definition (may be just {})
    background.png    the picture, 2:1 (1024 × 512 or 512 × 256) — required
    card.png          Friends cards, 4:1 (512 × 128) — optional
    home.png          the Home card, 8:1 (1024 × 128) — optional
```

The images are found in that folder by themselves. The JSON only needs what differs from the
defaults — all of it is optional:

```json
{
  "name": { "translate": "profile_background.mypack.sakura" },
  "texture": "mypack:textures/somewhere/else.png",
  "card": "mypack:textures/somewhere/else_card.png",
  "home_card": "mypack:textures/somewhere/else_home.png",
  "text_shadow": true,
  "page_darken": 0.22,
  "card_darken": 0.4,
  "palette": { "text": "#FFFFFF", "text_dim": "#C4BFD4", "text_faint": "#8E889E",
               "good": "#6EE07A", "accent": "#F0C070" }
}
```

| Key | Needed | What it is |
| --- | --- | --- |
| `texture` | no | The picture: the profile page and the picker's thumbnail, 2:1. Default: `<name>/background.png` (must exist). |
| `card` | no | Friends cards, 4:1. Default: `<name>/card.png`; without one, the middle of the picture. |
| `home_card` | no | The Home card, 8:1. Default: `<name>/home.png`; without one, the card image, else the picture's middle. |
| `page_darken` | no | 0–1, darkens the picture under the profile page (default 0.22). |
| `card_darken` | no | 0–1, darkens the pictures under the cards (default 0.4). |
| `palette` | no | Text drawn on the picture: `text`, `text_dim`, `text_faint`, `good` (on server, reached level rewards), `accent` (online elsewhere). Default: light text. |
| `text_shadow` | no | Shadow under that text (default true). |
| `name` | no | Default: the lang key `profile_background.<ns>.<name>`. |

**Dark or light pictures.** Text is drawn straight on the picture (Home and Friends cards, the page
header), so it must stay readable:

- A **dark picture** keeps the default light text; raise `page_darken` / `card_darken` if it's busy.
- A **light picture** needs dark text: set `palette.text` (and `text_dim`, `text_faint`, `good`,
  `accent`) to dark colors and `"text_shadow": false`. The profile page's cards then turn light too.
  `eden_ld:blossom` is the example to copy:

  ```json
  {
    "text_shadow": false,
    "page_darken": 0,
    "card_darken": 0,
    "palette": { "text": "#2A1E36", "text_dim": "#5A4A6A", "text_faint": "#7E6E90",
                 "good": "#2E7D3A", "accent": "#A0522D" }
  }
  ```

**Where the text sits** (keep busy detail away from these on the separate card images):

- Home card: the level ring and head at the left, the name next to them, balance / dailies /
  playtime at the right edge — the middle third stays mostly clear.
- Friends card: the head and name at the top left, a close button at the top right, the Profile
  button at the bottom left.
- Profile page: the header (head, name, badges) over the top ~25%; below it the content sits on
  its own cards, so only the gaps between them show the picture.

**Unlocking.** Set in the Moderation window's **Customization** section, Backgrounds tab (`eden_ld.admin.backgrounds`):
open to everyone, or locked and then open to chosen LuckPerms groups, hidden or shown locked, and for
sale for LC. Per player: `/hub background unlock <players> <id>` (e.g. a shop item). "No background"
is always available and is the default.

**Built in**: `eden_ld:twilight`, `parchment`, `aurora`, `ember`, `forest`, `ocean`, `starfall`
(dark) and `blossom` (light) — each with all three images in its folder (`background.png`
512 × 256, `card.png` 512 × 128, `home.png` 1024 × 128), each drawn for its own shape.
`tools/backgrounds/make_backgrounds.py` regenerates their pictures.

## Checklist

1. Pictures at the sizes above (PNG): for a background, in `assets/<ns>/textures/gui/profile_backgrounds/<name>/`.
2. The JSON in the same folder (`theme.json` or `background.json`), with `name` or a lang line in `en_us.json`
   and `uk_ua.json`.
3. Load the pack, F3+T, and look at every spot: hub tabs for a theme; profile page, Home card and a
   Friends card for a background (try the parchment and dark hub themes around it too).
4. Free it in the config or unlock it for players.
