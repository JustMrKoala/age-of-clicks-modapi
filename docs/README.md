# Age of Clicks Mod API: documentation

An unofficial mod loader and Java API for Age of Clicks. Code mods are jars in
`<game>/mods/`; the loader starts them with the game, gives each one a `ModContext`, and adds a
**CODE MODS** screen to the game's MODS screen (main menu > MODS) where players change each mod's
settings.

| Page | For |
|---|---|
| [Installing](installing.md) | Players: Setup.exe, online multiplayer, updating, uninstalling, problems |
| [Getting started](getting-started.md) | Modders: JDK, the template, build, install, first changes |
| [Concepts](concepts.md) | Lifecycle, threads, `ModContext`, data folder, logs, errors, multiplayer, clean runs |
| [Actions](actions.md) | Doing anything a country can do: `perform`, arguments, results, your own actions |
| [Action catalog](action-catalog.md) | Every built-in action with its parameters (generated from the code) |
| [Game and world](world.md) | `GameApi` (reads, reports) and `World` (cities, land, governments, events) |
| [UI toolkit](ui.md) | Windows and widgets in the game's own style, HUD buttons, toasts, hotkeys |
| [Settings](settings.md) | Settings players edit under MODS > CODE MODS |
| [Javadoc](javadoc/index.html) | Every public class and method (in the SDK zip) |

The dedicated server and the central lobby service have their own guide:
`server/SERVER-README.txt`.

## Thirty-second tour

```java
public final class HelloMod implements AocMod {
    public String id() { return "hello"; }

    public void onGameReady(ModContext ctx) {
        ctx.ui().bindKey(Input.Keys.F9, () ->
            ctx.actions().perform("give_gold", ctx.game().player().name(),
                    Actions.args("target", "Italy", "amount", 100), false)
                .thenAccept(r -> ctx.ui().toast(r.message(), 4)));
    }
}
```

Plus one line in `META-INF/services/aoc.api.AocMod`: `com.example.hello.HelloMod`. Build it into a
jar, drop it in `<game>/mods/`, start the game, press F9 in a game: Italy gets 100 gold.

## Versions

This documentation describes loader **0.7.0**, built against the game's `desktop-0.63.jar`. The
API lives in the `aoc.api` packages; everything in `aoc.api.internal` and `aoc.loader` is the
loader's own machinery and can change without notice.
