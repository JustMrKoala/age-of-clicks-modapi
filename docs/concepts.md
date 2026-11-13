# Concepts

## A mod

A class implementing `aoc.api.AocMod`, in a jar in `<game>/mods/`, named in the jar's
`META-INF/services/aoc.api.AocMod`. The loader reads the jars in alphabetical order when the game
starts; each mod gets its own class loader and its own `ModContext`.

```java
public interface AocMod {
    String id();                                   // required: unique, names mods/<id>/
    default String name()        { return id(); }  // shown in CODE MODS
    default String version()     { return ""; }
    default String author()      { return ""; }
    default String description() { return ""; }

    default void onLoad(ModContext ctx) {}                 // once, at startup, no window yet
    default void onGameReady(ModContext ctx) {}            // once, first frame: GL, fonts, input ready
    default void render(ModContext ctx, float delta) {}    // every frame, after the game drew
    default InputProcessor input() { return null; }        // raw input before the game sees it
}
```

## Lifecycle

| Callback | When | Do here |
|---|---|---|
| `onLoad` | Once, while the game starts, before any window exists | Declare [settings](settings.md); register [actions](actions.md#publishing-your-own-actions). No libGDX, no fonts, no game state. |
| `onGameReady` | Once, on the first rendered frame (main menu) | Build windows, add HUD widgets, bind keys. |
| `render` | Every frame after the game has drawn | Per-frame logic; raw drawing through `ctx.overlay()`. Windows from `ctx.ui()` draw themselves. |
| `input()` | Asked once after `onGameReady` | Return a libGDX `InputProcessor` for raw input, or `null`. Your `ctx.ui()` windows already get input before it. |

`onGameReady` runs in the main menu, not in a game. Check `ctx.game().inGame()` before reading
the world; a HUD widget takes a `when` condition for the same reason.

## Threads

The game is single-threaded: everything that touches the game state runs on the **render
thread**. All callbacks run there (except `onLoad`, where there is no game state yet), and so do
UI click handlers and the `Supplier`s of live labels and lists.

Off the render thread (an HTTP call, your own `Thread`, a `CompletableFuture` stage):

- `ctx.game().onGameThread(() -> ...)` runs a read on the render thread and returns a
  `CompletableFuture` with the result.
- `ctx.actions().perform(...)` is safe from any thread; it runs on the next frame.
- `ctx.ui().toast(...)` and `MessageList.add(...)` are safe from any thread.

Never block the render thread (no network calls, no `future.get()`): the game freezes until you
return.

## `ModContext`

| Method | Gives you |
|---|---|
| `game()` | [`GameApi`](world.md#gameapi): player, countries, date, the game's JSON reports |
| `world()` | [`World`](world.md#world): cities, territory, governments, events, direct changes |
| `actions()` | [`Actions`](actions.md): the action catalog, shared by all mods |
| `ui()` | [`Ui`](ui.md): this mod's windows, HUD widgets, toasts, key bindings |
| `settings()` | [`ModSettings`](settings.md): settings edited under MODS > CODE MODS |
| `camera()` | `MapCamera`: the map's zoom-out limit and how far the view may scroll past the edges |
| `overlay()` | `Overlay`: low-level drawing (y-up pixels), from `onGameReady` on |
| `dataDir()` | `<game>/mods/<id>/`, created on first use, for your own files |
| `gameDir()` | the game's install folder |
| `log(msg)`, `error(msg, t)` | lines in `<game>/mods/aoc-loader.log`, prefixed with your id |

## Errors

The loader catches whatever a callback or click handler throws, logs it (the first 3 times with a
stack trace) and carries on. A mod that fails **20 times** is switched off until the game
restarts. An exception in `onLoad` skips that mod. Failed actions don't throw: they return an
`ActionResult` with `ok() == false`.

## Multiplayer

Code mods are paused while a multiplayer game or lobby is active: no `render`, no input, no
windows, and `perform` returns *Code mods are switched off in multiplayer games*. The player sees
a notice. This keeps online games fair, since the other players can't see or check what a mod
does. When the player is back in single player, mods carry on.

## Clean runs and achievements

The game marks a run as *externally modified* (`cleanrun_r_external`) when its own external tools
change the world; that can switch off achievements for that game. The loader does the same for:

- every action in the `world` category (they do things the game normally only does through peace
  deals and scenario events),
- any action performed with `approved = true`,
- `GameApi`'s write methods (`declareWar`, `addGold`, ...).

Ordinary catalog actions without approval are the same moves a player makes by clicking and leave
the run clean.

## Coordinates

- UI toolkit (`aoc.api.ui`): **y-down** window pixels like the game's own UI; sizes are *design
  pixels*, scaled by the game's UI scale (`Theme.s(px)`).
- `Overlay`: **y-up** window pixels like plain libGDX; `overlay.flipY(screenY)` converts input
  coordinates.
- Map territory: pixels packed into a `long` as `(x << 32) | y` (`World.pack`, `unpackX`,
  `unpackY`).
