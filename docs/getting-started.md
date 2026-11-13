# Getting started (modders)

## What you need

- Age of Clicks with the mod loader installed (see [Installing](installing.md)). Your mod
  compiles against the game's `app/desktop-*.jar` and the loader's `app/aoc-loader.jar`.
- A **JDK 17 or newer** (for example Eclipse Temurin). Mods are compiled for Java 17, the
  version the game runs on.
- Any editor. In an IDE, add both jars above to the project's libraries.

## The template

The SDK zip contains `template/`, a complete mod:

```
template/
  src/com/example/hello/HelloMod.java       the mod
  resources/META-INF/services/aoc.api.AocMod  one line: com.example.hello.HelloMod
  build.bat                                  Windows: build, or "build.bat install"
  build.sh                                   Git Bash / Linux: ./build.sh, ./build.sh --install
```

Both scripts look for the game in Steam's default folder. If yours is elsewhere, set
`AOC_GAME` first:

```bat
set AOC_GAME=D:\SteamLibrary\steamapps\common\Age of Clicks
build.bat install
```

`install` copies `build/hello.jar` into `<game>/mods/`. Restart the game; *Hello Mod* now shows in
main menu > MODS > CODE MODS, and in a game there is a **Hello** button at the top left (or press F9).

## Make it yours

1. Rename the package (`com.example.hello`) and the class.
2. Change `id()`: short, unique, lowercase. It names the mod's data folder `mods/<id>/`.
3. Update `META-INF/services/aoc.api.AocMod` to the new class name.
4. Change `NAME` at the top of `build.bat` / `build.sh` (the jar's file name).
5. Fill in `name()`, `version()`, `author()`, `description()`: the CODE MODS screen shows them.

One jar may contain several mods: list one class per line in the services file.

## Where to go next

- [Concepts](concepts.md): when your code runs and on which thread. Read this before anything else.
- [Actions](actions.md): make countries do things.
- [UI toolkit](ui.md): windows and buttons.
- The AI Diplomacy mod's source (`mods/diplomacy/` in the source tree) is a larger example: an
  LLM client, a chat window, deals made of several actions, settings with validation.

## Testing

- Watch `<game>/mods/aoc-loader.log`: loading, your `ctx.log(...)` lines, every action with its
  result, and stack traces of anything your mod throws.
- CODE MODS > *Age of Clicks Mod Loader* > *Developer tools* adds an **Action Tester** window in
  games: pick any catalog action, fill in parameters, run it, see the result.
- Start the game through Steam (or `Age of Clicks.exe`); the loader only runs through the launch
  settings the installer changed.

## Sharing your mod

Send the jar. Players put it in `<game>/mods/` (they need the mod loader, see
[Installing](installing.md)). Don't include the game's jar or classes in your mod.
