# Age of Clicks Mod API (unofficial)

A Java agent mod loader for Age of Clicks (Steam)
with three APIs: **actions** (anything a country can do), **world** (cities, territory,
governments, events) and **UI** (windows and widgets drawn with the game's own panels, buttons,
fonts and sounds). It ships with these mods:

| Mod | What it does |
|---|---|
| `mods/diplomacy` | Talk to any leader in plain language; an AI plays the leader and can agree to any catalog action |
| `mods/replay` | Films every single player game with the game's own graphics and a cinematic camera; plays films in game |
| `mods/bulkcities` | Found many cities in a row, named after the real town at that spot |
| `mods/zoomout` | Lets the map camera zoom further out |

Plus an online multiplayer layer on top of the game's own unfinished multiplayer: a dedicated
server, a central lobby service and a MULTIPLAYER window in the main menu. Nothing goes through
Steam networking, and all code mods are off in multiplayer.

> Unofficial fan project, not affiliated with the developer of Age of Clicks. You need your own
> copy of the game: this repository contains no game code or assets, and the build only compiles
> against the `app/desktop-*.jar` of your installed game. Never commit or ship that jar.

**Documentation:** [docs/README.md](docs/README.md): installing (players), getting started,
concepts, actions plus the generated [action catalog](docs/action-catalog.md), game and world, UI
toolkit, settings. The SDK zip adds Javadoc and the [template](template/) mod.
**Contributing:** [CONTRIBUTING.md](CONTRIBUTING.md). **Agents and maintainers:** [AGENTS.md](AGENTS.md)
(how the loader hooks the game, facts learned the hard way, how to test).

## Repository layout

| Path | Contents |
|---|---|
| `loader/src/aoc/loader` | Java agent and ASM hooks (`HookTransformer`) |
| `loader/src/aoc/api` | Public mod API (`AocMod`, `ModContext`, actions, world, ui, settings) |
| `loader/src/aoc/api/internal` | Loader machinery: mod manager, Code Mods screen, multiplayer window, film pass. Not API |
| `mods/<name>/` | Bundled mods: `src/` plus `resources/META-INF/services/aoc.api.AocMod` |
| `server/` | Dedicated server and central lobby service (`server/dist`: scripts and examples) |
| `installer/` | Setup window (WinForms, PowerShell), install scripts, IExpress packer |
| `template/` | Example mod for modders, also built as a compile check |
| `tools/` | `CatalogDump`, generates `docs/action-catalog.md` |
| `docs/` | Player and modder documentation |

## Build and install

Needs a JDK 17 or newer on PATH, bash (Git Bash on Windows) and an installed copy of the game.
`build.sh` finds the game two folders up (when this repository sits in `<game>/modding/`), else in
Steam's default folder; set `AOC_GAME` to point elsewhere. It downloads ASM 9.6 into `lib/` on the
first run. The central lobby server address that builds suggest comes from `AOC_CENTRAL_SERVER` or
an untracked `central-server.txt` (one line, `host:port`); without it players fill it in themselves.

```bash
./build.sh --install     # builds, copies app/aoc-loader.jar and mods/*.jar, adds the agent to the launch config
./build.sh --uninstall   # restores app/Age of Clicks.cfg from app/Age of Clicks.cfg.vanilla
./build.sh --dist        # dist/: player zip, Setup .exe (IExpress), SDK zip (docs, javadoc, template)
```

Players use `dist/AgeOfClicks-ModAPI-Setup-<ver>.exe`: a window that finds the game through
Steam (or Browse...), with options for AI Diplomacy, online multiplayer and the central server
address. The same scripts run without the window: `installer/install.ps1 -GameDir ... [-NoDiplomacy]
[-NoMultiplayer] [-CentralServer host:port] [-Quiet]`. See [docs/installing.md](docs/installing.md).

The loader starts through one line in `app/Age of Clicks.cfg`:
`java-options=-javaagent:$APPDIR/aoc-loader.jar`. A Steam update can overwrite that file;
run `--install` again afterwards. Close the game before installing. Log: `mods/aoc-loader.log`.

## Diplomacy mod

1. Get an API key (Groq is free: https://console.groq.com/keys).
2. Main menu > **MODS** > **Code Mods** > **AI Diplomacy**: paste the key, pick the service, model and
   hotkey, Save. (Or set the `AOC_LLM_API_KEY` environment variable.)
3. In a game press **F8**, or click "Diplomacy" at the left edge. Pick a country and write.

A leader can propose any catalog action as part of a deal: peace, alliances, puppets, unions
(annex + rename), land cessions and sales, leases, trade contracts, loans, lend-lease, joint
wars, government changes... Nothing happens until you click **Accept**. On their own they may
only do things that don't take from you without consent (attitude changes, threats, war if
`allow_war_on_player=true`, internal decisions).

## Writing a mod

A mod is a jar in `mods/` with `META-INF/services/aoc.api.AocMod` naming a class that
implements `aoc.api.AocMod`.

```java
public final class HelloMod implements AocMod {
    public String id() { return "hello"; }

    public void onGameReady(ModContext ctx) {
        Window w = new Window("Hello", 420, 240);
        w.content().add(
            new Label(() -> "You lead " + ctx.game().player().displayName()),
            new Button("Gift Italy 100 gold").primary().onClick(() ->
                ctx.actions().perform("give_gold", ctx.game().player().name(),
                        Actions.args("target", "Italy", "amount", 100), false)
                    .thenAccept(r -> ctx.ui().toast(r.message(), 4))));
        ctx.ui().addHud(new Button("Hello").onClick(() -> ctx.ui().toggle(w)),
                Ui.Anchor.TOP_LEFT, 10, 120, ctx.game()::inGame);
    }
}
```

### Code Mods screen and settings

The loader adds a **CODE MODS** button to the game's own **MODS** screen (main menu > MODS, the
index of scenario mods), next to its Back button. It lists every installed code mod with its name, version, author and
description, and builds a settings form from what each mod declares:

```java
public String name() { return "My Mod"; }
public String version() { return "1.0.0"; }
public String description() { return "What it does, in a sentence or two."; }

public void onLoad(ModContext ctx) {
    Setting<String> key = ctx.settings().secret("api_key", "API key", "Shown masked");
    Setting<Boolean> fast = ctx.settings().toggle("fast", "Fast mode", false, "Help text under the field");
    Setting<Integer> n = ctx.settings().integer("count", "Count", 5, 1, 20, "Between 1 and 20");
    Setting<String> pick = ctx.settings().choice("size", "Size", "M", List.of("S", "M", "L"), "");
    ctx.settings().onChange(() -> ctx.log("saved: " + n.get()));
}
```

Types: `text`, `secret`, `toggle`, `integer`, `decimal`, `choice`. Values live in
`mods/<id>/config.properties`; read them with `setting.get()` whenever you need them, so
saved changes apply without a restart.

### What `ModContext` gives you

| | |
|---|---|
| `game()` | `GameApi`: player, countries, date, the game's JSON reports (country details, wars, neighbours), thread helpers |
| `actions()` | `Actions`: the catalog. `perform(id, actor, args, approved)`, `describe(filter)` (text for docs/AI), `register(spec)` to publish your own, `performGameAction(className, actor, fields, approved)` for any of the game's 95 action classes |
| `world()` | `World`: cities, territory selection (`territoryAround`, `territoryFacing`, `territoryOf`), `transferTerritory`, `annex`, `setGovernment`, `rename`, `applyEffect` (any scenario-event effect), `fireEvent`, `makePeace`, AI attitudes |
| `ui()` | `Ui`: windows, HUD widgets, toasts, key bindings |
| `settings()` | `ModSettings`: declared settings, edited in main menu > MODS > Code Mods |
| `overlay()` | low-level drawing (y-up), if you need it |

### Action catalog

`ctx.actions().describe(s -> true)` prints it. Categories: diplomacy (alliances, pacts, puppets,
vassals, tribute, ultimatums, campaigns), war (declare, justify, call allies, joint wars, peace
requests, lend-lease, uprisings, regime change), trade (gold, resources, gifts, contracts,
loans, market access), territory (cede, buy, lease), economy and politics, and world actions
(make_peace, annex, transfer_land, take_land, set_government, rename, change_attitude, effect,
fire_event).

Parameters take names: countries by name or tag, cities by name, territory as `"all"`, a city,
a list of cities, `{"cities": [...], "radius": 30}` or `{"facing": "Country", "share": 0.1}`.
Game-backed actions follow the game's rules and costs; `approved=true` means the other side
already agreed (the game then skips its own AI acceptance check, not its rules).

### UI toolkit (`aoc.api.ui`)

`Window`, `Panel` (row/column layout with `width`, `height`, `flex`, card/well backgrounds),
`ScrollPanel`, `Label` (live text), `Button` (primary red / parchment / blue / tertiary),
`Toggle`, `TextField` (optionally masked),
`ListView<T>` (selectable, filterable, with coloured notes), `MessageList` (chat bubbles),
`Separator`/`Separator.spacer()`, plus `Ui.toast`, `Ui.addHud` and `Ui.bindKey`.
Everything is drawn by the game's own `CFG` helpers, so it follows the game's theme and UI
scale. Coordinates are y-down window pixels, like the game's UI.

## Built-in multiplayer (experimental)

The game contains the developer's unreleased multiplayer (KryoNet, TCP 54555 / UDP 54777). In Code
Mods > Age of Clicks Mod Loader, *Experimental: built-in multiplayer* (on by default through the
installer) adds **MULTIPLAYER** to the main menu: online lobbies on the central server (create,
join, start), the game's LAN lobby, and join by address.
*Allow Steam networking* is off by default: the loader reroutes the lobby's Steam checks and Steam
invite joins, so nothing goes through Steam's network.

## Dedicated server

`server/` (also in the tester zip) holds a windowless host for the game's own multiplayer, for
Windows or Linux: `aoc-server.jar` plus your own copy of the game jar. See `SERVER-README.txt`.
`test-player.bat` starts the game developer's headless test client as a pretend second player.
Code mods are switched off in every multiplayer game.

## How it hooks the game

`aoc.loader.HookTransformer` (ASM 9.6, bundled) patches four methods as they load:
`cgsGame.render()` (mods draw after each frame), `DefaultLwjgl3Input.set/getInputProcessor`
(mods see input first; the game always gets its own processor back) and
`MainMenuScreen.buildMenu()` (adds mods' rows such as REPLAYS, at most two, with the menu's own row
factory), and reroutes
Steam multiplayer calls in `MultiplayerLobbyScreen`, `cgsGame$2` and `SteamManager$2$1`. Nothing in the game jar
is changed on disk.

Clean runs: like the game's own MCP tools, world actions, pre-approved deals (`approved=true`)
and `GameApi` actions mark the run as externally modified (`cleanrun_r_external`), which can
switch off achievements for that game. Ordinary catalog actions without approval are the same
moves a player makes by clicking and leave the run clean.
