# Working on the Age of Clicks Mod API

Guide for maintainers and coding agents. Read it before changing behaviour: most of the facts
below cost hours to find. The player and modder docs are in `docs/`.

Age of Clicks is a Java 17 / libGDX game (code in `<game>/app/desktop-<ver>.jar`, packages
`ru.ius.david.cgs.*`). This repository is an unofficial Java agent loader, a mod API on top of
it, bundled mods, an installer and a multiplayer server. The game jar is never modified on disk.

## Rules

- **Never commit or ship game files.** No `desktop-*.jar`, no decompiled game code, no assets.
  The build compiles against the player's own jar; the dedicated server needs each host's own jar.
- **No Steam networking.** Multiplayer goes over LAN, a direct address or the central lobby
  server. The loader reroutes the game's Steam lobby checks (`allow_steam_networking`, default off).
- **All code mods are off in multiplayer** (lobby or match): `ModManager.multiplayerActive()`;
  the action API refuses everything there. Keep it that way.
- **Keep `CleanRunState.breach("cleanrun_r_external")`** on world actions and pre-approved deals,
  like the game's own MCP tools (it can switch off achievements for that save).
- **Never touch a player's real saves in tests.** Use copies; back up and restore `saves/autosave_*`
  around a test (the game autosaves).
- **Don't launch the game while someone may be playing it** on that machine.
- **No server address in the source.** Builds take it from `AOC_CENTRAL_SERVER` or the untracked
  `central-server.txt` (written into the jar as `aoc-build.properties`, read by `Version.CENTRAL_SERVER`).
- Test the installer on a fake game folder, never on a real install.
- Writing style for docs, comments and commits: no em dash or en dash; rephrase with a comma,
  colon, parentheses or two sentences.

## Build

Git Bash, JDK 17+ on PATH, classes target Java 17 (`--release 17`; a jar built for a newer class
version fails to load in the game).

| Command | Does |
|---|---|
| `./build.sh` | Compile loader, server, every `mods/<name>/` with a `src/`, and the template into `build/` |
| `./build.sh --install` | Also copy into the game and add the agent line (close the game first) |
| `./build.sh --dist` | `dist/`: player zip, `AgeOfClicks-ModAPI-Setup-<ver>.exe`, SDK zip |
| `./build.sh --uninstall` | Restore the vanilla launch config |

The game folder is `AOC_GAME`, else two folders up, else Steam's default folder. The loader version
is in `loader/src/aoc/api/internal/Version.java`. `--dist` writes `files/build-info.txt` (version,
game jar name for the installer's mismatch warning, central server) and regenerates
`docs/action-catalog.md` with `tools/CatalogDump.java`. IExpress quirks: the SED file needs
CRLF/ANSI, args `/N /Q /M <sed>`, output paths without spaces or brackets.

Installed layout in the game folder: `app/aoc-loader.jar`; `app/Age of Clicks.cfg` gets one line
`java-options=-javaagent:$APPDIR/aoc-loader.jar` under `[JavaOptions]` (original kept as
`app/Age of Clicks.cfg.vanilla`; a Steam update can reset it); mods in `mods/*.jar`, their settings
in `mods/<id>/config.properties`; log in `mods/aoc-loader.log` (every API action with its result:
the first place to look).

## How the loader works

`aoc.loader.HookTransformer` (bundled ASM 9.6; the game's shaded ASM is v4 and too old) patches
classes as they load:

- `cgsGame.render()`: mods run `beforeFrame` at its start and draw after each frame.
- `DefaultLwjgl3Input.set/getInputProcessor`: mods get input first.
- `MainMenuScreen.buildMenu()`: adds mods' rows (REPLAYS, MULTIPLAYER).
- Steam checks in the multiplayer lobby and Steam invite joins: rerouted through `Hooks`.
- Map camera, Economy menu rows, film pass hooks: see below.

`aoc.api.*` is the public API; `aoc.api.internal` and `aoc.loader` can change without notice.

## Facts about the game

- Game UI is y-down (fonts generated with flip=true). `aoc.api.ui.Theme` draws through the game's
  own `CFG` helpers (`drawIronMenuPanel`, `drawIronButtonBg`, ...).
- Player actions are `ru.ius.david.cgs.game.actions.*` (95 classes) with public fields, run via
  `execute(GameManager)`; source country = `sourceCountryName` (key in `getWorldCountries()`).
- **Many game actions refuse silently** (only a log line). The API captures the game log
  (`GameLog`) and checks game state afterwards: "Done" means verified, "Sent" means unconfirmed.
- AI to human alliance invites need `AllianceInviteAction.preApproved`; NAPs are refused between
  members of one alliance; `TransferGoldReserveAction` only works inside an alliance (so
  `give_gold`/`give_resources` move value directly with `spendGold`/`addGold`).
- Enum fields (`DiplomacyAction.type`, `UpgradeBuildingAction.upgradeType`) must be set; the API
  converts names like `tankFactory` to `TANK_FACTORY`.
- Event days in `events.json` count from scenario start; `declare_war` effect: `target` attacks `textArg`.
- `onLoad` runs before GL exists: creating a SpriteBatch or Texture there kills the mod. Loading
  MapScreen during premain is unsafe too.

### Main menu and MODS screen
- The game's MODS row opens `ModManagerScreen`, an index of scenario mods (not an editor).
  `ModsScreenButton` narrows its full width Back button (`btnBackW`, set in `beforeFrame` before
  the game draws it) to the left half and puts CODE MODS in the right half
  (`Ui.addHud(widget, rectSupplier, when)`).
- Each injected main menu row pushes the game's rows down; with three, EXIT fell off the screen.
  So at most two loader rows (`Hooks.MENU_ROWS`); later mods' buttons go to the top of the Code
  Mods window.

### Map camera
`MapScreen.maxZoom` is the zoom out cap (camera zoom, larger = further out), computed in
`calculateZoomThresholds()`; `updateCameraBounds()` keeps the view in the map with two
`MathUtils.clamp` calls. Hooked as `Hooks.afterZoomThresholds`/`clampCamera` for `aoc.api.MapCamera`.

### Economy menu (`CGSUpgradesMenu`, hand drawn, not scene2d)
`buildUpgradesRows()` makes `UpgradeRow`s: header("upg_block_cities") then
`UpgradeRow.foundCity(...)`; a block is the rows after its header. `rebuildRows()` gives a found
city row a ButtonElement calling `UpgradeCallback.onFoundCity()`; clicking the row goes through
`openUpgradeDetail(row)`. `update(F)` rewrites found city rows every frame. The loader hooks these
three (`EconomyRows`) for `Ui.addEconomyCityRow`. Prefer native game menus over HUD buttons for
in-game mod entries.

### Founding cities (Bulk Cities)
`MapScreen.startFoundCityMode()` then a click: `handleFoundCityClick` validates (canFoundAt,
findFoundBlocker, cost vs gold), ENDS the mode and opens a `CGSTextInputDialog` whose callback
(fields `val$px/val$py` = map pixels) submits `FoundCityAction(x, y, name)`. The mod answers that
dialog in `beforeFrame` and calls `startFoundCityMode()` again. `Gazetteer` aligns every
`scenarios/*/cities.json` (22 lists, about 17k towns) to the current map through shared towns,
dropping homonyms (Rome GA, London ON) that their neighbours misplace; `NameForge` makes names
from the nearest towns when none is known. Never key towns by name alone.

### Replays (film the real game)
- In `beforeFrame`, `MapCamera.renderMapView` sets the MapScreen camera and calls its private
  `renderMapFrame(0, now, false)` to the screen; `FilmOverlay` adds bars and captions; frames are
  read back and piped to ffmpeg; the game's own frame then paints over it. Not an FBO (no nested
  FBOs in this libGDX). World y = worldBounds.height minus map y.
- An earlier version drew its own map and looked "jittery, blurry, pixelated". Don't go back.
- Political colours whatever the player's map mode: `renderCleanMapView` switches to POLITICAL and
  clears selection/hover, then restores. Territory colours fade by frame delta, so `FilmView` saves
  them, snaps with `updateLutColors(1000)` and restores; mesh fills lerp in
  `TerritoryRenderer.renderLayers`, redirected to `Hooks.filmLerp` (factor 1 in a film pass), with
  every `CountryPolygon.currentColor` saved and restored.
- Flags: `drawFrontlineDecorAndFlares(false)`, `drawSiegeRings`, `drawUnitFlagsAndRallyPoint(false)`
  on uiProjection. Their boolean means HIDE. Not the whole interface pass: it has side effects
  (eats city fall events). Flags sit on each marker's UnitMotion, which only moves while the
  player's view draws troops there, so FilmView puts motion x/y on the marker for the pass.
  Flag thinning state is per camera: the film keeps its own copy, or flags flicker.
- During a film pass (`Hooks.filmPass()`) troop sprites, unit motion/fire and display smoothing
  are no-ops (entry return hooks); the rally point is removed and `unitFxClock` restored.
- Camera (`Director`): one zoom per battle, drift towards hotspots, cuts instead of flights, no
  cut-aways during the player's own wars. Players found zooming in and out made battles hard to follow.
- Performance: GPU encoder probed at start (h264_nvenc / amf / qsv, libx264 fallback, ffmpeg at
  BelowNormal priority), `AsyncReadback` (3 PBOs), crop instead of rescale, a time budget (25% of
  game thread time in peace, 40% at war). The game moves the date in steps, so the film clock
  extrapolates over a step and ignores paused time. Never repeat frames.
- Known leak: occupation hatching is baked asynchronously in the player's map mode, so its stripes
  can take mode colours. Fixing it needs a film-own occupation layer.

## Multiplayer

- The game ships unfinished multiplayer: `cgsGame.showMultiplayerLobby()`, KryoNet TCP 54555 /
  UDP 54777, Steam lobbies. `gm.isNetworkMode()`; the API routes game actions through
  `getActiveMultiplayerManager().enqueueAction` and refuses direct world changes there (desync).
- Headless tests in the game jar work without Steam: `ru.ius.david.cgs.MPHostTest` then
  `ru.ius.david.cgs.MPGuestTest` (prints PASS/FAIL).
- `server/src/aoc/server/DedicatedServer.java` mirrors MPHostTest (`MPHeadlessEnv`, `hostGame`,
  `update(0.05f)` every 50 ms). `LobbyService` is the central lobby server: HTTP API on 54600
  (`/api/info`, `/api/lobbies`, POST create, POST start with `X-Lobby-Token`), one
  DedicatedServer per lobby on pool ports, started with `-javaagent:aoc-server.jar -Daoc.port=N`
  (`PortAgent` rewrites the game's compiled-in ports).
- Joining from the loader window: `showMultiplayerLobby()` is a deferred fade and the lobby
  screen's `show()` resets to MENU, so `MultiplayerTestWindow` waits for the lobby screen before
  calling its private `onJoinRoom`. The name goes through `SettingsManager.setPlayerName`.
- The lobby has no Ready button (players get ready in the in-map country pick). Matches start on
  player count, or earlier when the lobby's creator presses "Start the match now": a loader HUD
  panel over the game's lobby screen that also shows "N of M players". The game's own Start button
  belongs to the host, which is the server. `cgsGame.getActiveMultiplayerManager()` stays null until
  a match starts; in the lobby the session is `MultiplayerLobbyScreen.getMultiplayerManager()`
  (private field `mode` = `IN_LOBBY`). Code mods are paused there too.
- The game's clock: `GameServer` ticks on its own timer (`tickAccumulator` against
  `gameSettings.gameSpeed`) and never reads `GameManager.isGamePaused`, so `setGamePaused(true)`
  does not stop it. `DedicatedServer` zeroes `tickAccumulator` before every update from the start
  until every guest is ready with a country (`holdTicks`).
- Clients leave the pick menu only when EVERY lobby entry is ready with a colour key other than -1,
  so the server marks its own entry ready with key -2. The creator's "Start without waiting" (same
  start endpoint) gives each guest who has not picked a random free country (the pick menu's own
  filter: `originalPixels > 0` and a name), marks it ready and rebroadcasts the lobby;
  `GameServer.ensurePlayersRegistered` registers it, and the client's MapScreen takes its colour key
  from the lobby state ("BUG-10 fix" path). Custom maps must exist on every client.
- Bandwidth: the game sends a FULL world snapshot to every player every second (about 2 MB on
  Modern World) and KryoNet kills the match when a 4 MB buffer fills. `NetGuard` throttles
  snapshots (`aoc.snapshot_seconds`=5), skips backed up connections and closes failing ones;
  buffers are 16 MB on both sides.
- Status: in game (self-test, one real client plus a headless player) the start panel shows, the
  early start works, and after picking a country and pressing Ready the client leaves the pick menu
  and plays. Headless: the clock holds at day 1 while a guest has not picked, and "Start without
  waiting" gives that guest a country. Not yet verified: two real game clients in one match, and a
  real client that did not pick taking over the country it was given.

## Testing

Nothing replaces running the game: install, launch, read `mods/aoc-loader.log`. Before every
install, run the agent in a bare JVM and check that every mod logs "Loaded mod":

```bash
java -javaagent:"<game>/app/aoc-loader.jar" -cp "<game>/app/desktop-0.63.jar" <any main class>
```

Running the agent from the source tree creates `mods/aoc-loader/` here: delete it. Other offline
checks: load hooked classes under `-Xverify:all`; installer on a fake game folder.

In game, MODS > CODE MODS > Age of Clicks Mod Loader > Developer tools adds an Action Tester.

Dev self-tests run **only while their trigger file exists**, and they press buttons, load games
and quit the game. Always delete the trigger file when done, or the next normal game start runs
the test again:

- Replay film: `<game>/mods/replay/selftest.properties` (`SelfTest`): loads `saves/<save>`
  (use a copy), runs at `speed`, can switch map `modes`, `followWar`, `film=false`/`encode=false`
  baselines, logs fps and pacing, dumps PNG frames to `mods/replay/selftest/`, then quits.
- Multiplayer: `<game>/mods/aoc-loader/mptest.properties` (`MultiplayerSelfTest`): creates a
  lobby on the central server (`scenario`, `needed`), waits `waitPlayers` seconds, checks the start
  panel, presses "Start the match now", then either picks a free country and presses Ready
  (`pick=true`) or presses "Start without waiting" unpicked (`pick=false`), plays `seconds`, saves
  screenshots to `mods/aoc-loader/mptest/` and quits. A second player can be a headless
  `aoc.server.TestPlayer` started with `-javaagent:build/aoc-server.jar -Daoc.port=<lobby port>`;
  add `-Daoc.pick=false` for a player who never picks.

## Open work

- A real two-client match through main menu > MULTIPLAYER, and "Start without waiting" with a
  real client that has not picked.
- Click through the Setup window by hand (scripts pass on a fake folder).
- Save AI Diplomacy conversations with the game.
- A film-own occupation layer for Replays.
