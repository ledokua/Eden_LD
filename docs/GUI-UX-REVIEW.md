# Eden_LD interface review

| Surface | Review and changes |
| --- | --- |
| Hub shell, Home, profile, Friends, Party, Guild and guild bank/quests, Mail, Dailies, Kits, Wardrobe, Waystones, Settings | Kept the parchment and existing theme variants. Added keyboard access to the icon controls, made the selected bookmark's focus visible, and scroll focused controls into view across tabs, drawers, dialogs and settings. Mail and wardrobe cards also take keyboard input. Settings search has more room. |
| Activities, lobby browser, create/edit panel, hosted lobby, Ranked PvP, wager duels | Differentiated a filtered-out list from an empty list and added a filter reset. Kept lobby text clear of badges and actions, with full text on hover. Activity cards and run modifier rows take keyboard input. Esc returns from creation or editing to the browser after any focused field or popup is dismissed. |
| Player reports and staff report desk | Added feedback for missing complaint recipients or text, Esc back from a thread, visible keyboard focus in the staff queue, and separate states for no search results and no selected report. |
| Chat screen and bed chat hooks, chat feed and notification strips, tab list, channel/message menus, emoji/sticker picker, GIF preview, group chat, party/guild composers | Party and guild mention completion leaves Tab available when there is no completion and Shift+Tab always moves backward. The group form shows the invite count and the cap before submitting, and validates names with the server's existing rule. GIF previews preserve aspect ratio and wrap status text. |
| Poll HUD and screen overlay, staff poll form and history | Answers wrap, including rebound vote key labels. Drawing, mouse input and EMI use the same scaled dimensions. Staff results and voter names scroll while the stop button stays reachable. |
| Moderation window, Mail composer/log, News, Players/Punishments, Activities, Customization, Guilds/Parties, World settings, Reports, Polls, Server | Added a persistent Back or Close action and a scrolling section list. Compact views reserve space for EMI's item list and footer. The mail/news text editor keeps the live two-pane view when it fits, or switches between editing and the full-width player preview. |
| Inventory hub/report/PvP buttons, tutorial help and spotlights, stamina and combat HUD adapters, item and EMI tooltips | The Help window uses a labeled Start tour action and preserves its existing spotlight ID. The other entry points, adapters and tooltip hooks were reviewed in their existing roles. |

## Integration and visual checks

- **VectorLib** supplies canvas layout, popup focus, editor controls and spotlights. Check long strings and custom fonts in player themes and staff forms.
- **Arenas_LD** owns the embedded live lobby and its invite picker, as well as the run HUD. Master added relation grouping with Arenas 4.40.3 and base loot rolls with Arenas 4.39.0 or newer. Check create/edit and hosted-lobby round trips with the pack version, including an older Arenas fallback.
- **NPCs_LD** owns the guided dialog. Verify the Start tour spotlight and return to the intended hub tab with configured tours.
- **EMI** owns its item list, search and drag source. Verify attachments in both the normal layout and narrow layout, with a poll on screen.
- **Other optional integrations** include Duels, Economy, Kits, Waystones, Minestar, BanHammer and external report editors. Check permission-restricted screens, populated multiplayer data and return navigation with the actual pack.
- **Resolution coverage** should include 1280×720, 1600×900 and 1920×1080 at scales 2–4 where Minecraft allows them, in English and Ukrainian. Include long player names, large report/poll history, two clients for shared chat, and mouse plus Tab/Shift+Tab navigation.

## Local verification

- The Gradle build and all 269 existing tests passed. The 11 added language keys are present in both locales.
- With a copied local world and GUI scale 3, checked the Ukrainian lobby browser at 1280×720, 1600×900 and 1920×1080, including the maximized hub, the create panel and Esc back to the browser. The long filter labels fit on two rows in the compact hub and on one row in the maximized hub.
- At 1280×720 checked moderation and EMI footer clearance, the compact news preview, the Help window, an English four-answer poll with a click on the fourth option, and Ukrainian report submission, opening the thread and Esc back to the list.
