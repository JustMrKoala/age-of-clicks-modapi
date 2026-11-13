# Contributing

Thanks for helping. Start with [AGENTS.md](AGENTS.md): build steps, how the loader hooks the game,
and the rules every change keeps.

## Setup

1. Own and install Age of Clicks.
2. Install a JDK 17 or newer and, on Windows, Git Bash.
3. Clone this repository (into `<game>/modding/` or anywhere; set `AOC_GAME` if the game is not in
   Steam's default folder) and run `./build.sh`.
4. `./build.sh --install` puts your build into the game (close the game first).

## Pull requests

- One topic per pull request. Describe what you tested in game and on which game version.
- Never add game files (`desktop-*.jar`, decompiled code, assets) or personal data such as server
  addresses, API keys or save files.
- Keep the public API in `aoc.api` backwards compatible, or bump the version in
  `loader/src/aoc/api/internal/Version.java` and say what breaks.
- New mods go in `mods/<name>/` with `src/` and `resources/META-INF/services/aoc.api.AocMod`;
  `build.sh` picks them up.
- Code mods must stay off in multiplayer, and nothing may use Steam networking.
- Docs and comments: plain English, no em or en dashes.

## Bugs

Open an issue with the game version, what you did, and `<game>/mods/aoc-loader.log`.
