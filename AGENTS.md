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
| `./build.sh --catalog` | Also refresh `catalog/` (index.json + mod jars) for the Mod Browser; commit and push to publish |
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

### Mod UI next to the game's HUD
- `GameHud` (internal) measures the game's interface once per frame: top bar (`CFG.topBarBottom()`),
  dock (`CGSInGame.getDockTopY()`, private `dockX`/`dockW`), every visible `MenuElement` of the HUD
  (`getMenuElements()`, notices via `getTopLayerElements()`), and every visible `SliderMenu` field
  of `MapScreen` (panels; `isModalOverlay()` or more than 55% of the screen counts as modal, as do
  visible `*Dialog` fields). `Ui` places HUD widgets, notices and windows around those blocks.
  Measured on 1920x1081: top bar 131 px, dock from y 930 (x 641..1279), the left tool strip at
  x 18..76 from y 346, game notices from y 150; economy panel x 19..523, diplomacy and army on the right.
- HUD widgets slide down (bottom anchors: up) past blocks; when a panel fills the edge they step
  beside it (`GameHud.sideStep`); modal or `CFG.uiHidden` hides them. Placed widgets are claimed
  as blocks, so mods stack. Rect-supplier HUD widgets (`ModsScreenButton`) are never moved.
- Dev self-test: `mods/aoc-loader/uitest.properties` with `save=<folder in saves/>` opens the Mod
  Browser, loads the save, opens economy / diplomacy / army and a mod window, and writes
  screenshots plus the measured blocks (`UITEST` lines in the log). Run it on a copy of a save.

### Game Improvements (built into the loader)
- `Improvements` is a second built-in entry next to `LoaderMod` (id `improvements`, settings in
  `mods/improvements/config.properties`), one toggle per improvement. Add new ones there.
- Zoom Out (owner's request 2026-11-14) is a toggle here since loader 0.7.0, no longer `mods/zoomout`;
  `ModManager.discoverMods` deletes a leftover `mods/zoomout.jar` (both would set the camera limits).
- `FrontNumbers` (third version): everything about the place comes from the map. The visible map
  is sampled on a screen grid (step = screen height / 36) for who holds the land: the owner
  (`getOwnershipGrid()`), or the occupier (`getControlOwner()` != -1 with `getControlGrid()` >= 0.45,
  the game's own threshold). A front is where neighbouring samples belong to two countries with
  `areAtWar`. The touching points around the one nearest the screen centre give the front's place;
  the centres of both lands within 30% of the screen height give the normal; both numbers sit along
  it at least 11% of the screen height from the front, on a sample the side holds and clear of the
  HUD (it tries further back and along the front before giving up).
  History: v1 took direction from both sides' flag centres (same line: numbers turned 90 degrees in
  a bulge). v2 anchored on the flags of `getFrontlineDecor()`, but `followFrontSide` skips every
  post a division holds (`divisionHoldsPost`), and divisions exist only outside classic combat, so
  in division games most numbers were missing (owner's screenshots). Do not anchor on flags.
  Number = infantry + artillery + tanks (`CountryState`) x share, share = (flags facing that enemy +
  divisions with `targetEnemyColor` = that enemy) / all of the country's; nothing counted = 1/(1+all).
  Drawn after the frame, scissored to the band between top bar and dock.
  `frontZoom=<zoom>` in the UI self-test puts the camera on the player's front.
- Map edges: see "Map camera". The UI self-test logs `UITEST edge` (camera pushed past the top left
  corner must rest beyond the game's own limit).
- Performance, measured 2026-11-14 with Flight Recorder (Modern World save, x16): the game's heavy
  work (WarManager peace/naval/encirclement, SDF borders, AI) already runs on background threads;
  on the render thread 46% went to OUR replay `Recorder.writeFrame` (a full grid scan every 3 game
  days = ~20 a second at x16). Now at most one sample per 0.3 s real time and no boxing per cell:
  65-71 fps became 85-107. No safe win found inside the game itself yet, so there is no
  performance switch. Profiling how-to: the launcher restarts itself for the heap
  (`DesktopHeapChoice`, env `CGS_HEAP_RELAUNCHED`), so start the exe with `CGS_HEAP_RELAUNCHED=1`,
  add `java-options=-XX:StartFlightRecording=settings=profile` to the cfg for the run (restore it),
  and let the UI self-test write the recording on exit (the game's exit skips dumponexit).
  The loader and every mod's `onLoad` also run in that outer launcher process.
- UI self-test extras: `war=` (comma list of countries to attack, may be empty) plays at `speed`,
  answers pop-ups, saves `war_N.png`, logs the front numbers and fps / game days per second;
  `focus=<country>` centres the camera. It films too: delete the films and recordings it leaves.

### Mod Browser and catalog
- `catalog/index.json` + the mod jars are tracked in git on purpose: the browser downloads them
  from raw.githubusercontent.com (loader setting `mod_catalog`, https only), checks SHA-256 and
  size, and writes into `mods/`. `tools/CatalogIndex.java` builds the index from the jars
  (`build.sh --catalog`). Every loader build carries `catalog/index.json` as `/aoc-catalog.json`,
  so the list shows offline and never offers a jar that is not published.
- A loaded jar is locked on Windows: updates are saved as `<jar>.update`, removals as an empty
  `<jar>.remove`, and `ModCatalog.applyPending` finishes both at the next start, before mods load.
- Publishing a mod version: bump its `version()`, `./build.sh --catalog`, commit `catalog/`, push.
  `loader` in the index is the loader version the jars were built for; older loaders refuse them.

### Main menu and MODS screen
- The game's MODS row opens `ModManagerScreen`, an index of scenario mods (not an editor).
  `ModsScreenButton` narrows its full width Back button (`btnBackW`, set in `beforeFrame` before
  the game draws it) to the left half and puts CODE MODS in the right half
  (`Ui.addHud(widget, rectSupplier, when)`).
- Each injected main menu row pushes the game's rows down; with three, EXIT fell off the screen.
  So at most two loader rows (`Hooks.MENU_ROWS`); later mods' buttons go to the top of the Code
  Mods window.

### Map camera
The game stops the camera in `MapScreen.constrainCamera()`: it sets `camera.position` itself and
pins it to the middle when the view is wider than the map. `updateCameraBounds()` only clamps
`targetCameraPosition`. The loader used to hook only the latter, so the edge margin never worked;
`Hooks.constrainCamera` now runs at the start of `constrainCamera` and returns true (skipping the
game's code) when a margin is set, except on maps with `infiniteScrollX`.
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
- A live capture cannot slow a war the player fast-forwards (owner's complaint 2026-11-14), so
  `LiveFilm.limitWarSpeed` holds `GameManager.setPlaySpeed` at the setting `war_speed_limit`
  (default x2) while the focus country itself is attacker or defender, and restores the player's
  speed when the war ends. Verified: x16 asked, game stayed at x2 (9.7 days/s).
- `Recorder` samples at most every 0.3 s of real time (it was the biggest cost on the render thread).
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
- Web tunnel (Cloudflare Tunnel): a tunnel carries web traffic only, the game speaks plain TCP
  and UDP. So the lobby service's public port is `Gateway` (raw sockets): ordinary requests are
  piped to the API, which now listens on loopback (`internal_port`, default port - 1, with the
  caller's address in `X-Aoc-Client`, from `CF-Connecting-IP` when the request came from this
  machine); a WebSocket request for `/ws/<match port>` (running matches only) becomes a relay with
  its own TCP connection and UDP socket to that match. The loader's `TunnelRelay` is the other
  end: the game connects to 127.0.0.1 on one local port (TCP and UDP), each binary message is
  `'T'` + stream bytes or `'U'` + one datagram. `CentralServer` takes "host[:port]" (direct) or
  "https://name" (API and matches through that address). `inCentralLobby` compares the game's
  port with the relay's local port then (`joinedVia`). Verified 2026-11-14: two headless
  TestPlayers through stand-alone relays PASS (days 1 -> 206), and the real client's
  `MultiplayerSelfTest` PASS (days 1 -> 81). Stand-alone relay for tests:
  `java -cp aoc-loader.jar;desktop-0.63.jar aoc.api.internal.TunnelRelay wss://name/ws/<port>`,
  then `TestPlayer 127.0.0.1` with `-javaagent:aoc-server.jar -Daoc.port=<local port>`.
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
