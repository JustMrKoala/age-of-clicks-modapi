#!/usr/bin/env bash
# Builds this mod into <id>.jar against your installed game and mod loader.
#   ./build.sh            build hello.jar
#   ./build.sh --install  build and copy it into <game>/mods/
# Set AOC_GAME if the game is not in Steam's default folder. Needs a JDK 17 or newer.
set -euo pipefail
cd "$(dirname "$0")"
NAME=hello
GAME="${AOC_GAME:-/c/Program Files (x86)/Steam/steamapps/common/Age of Clicks}"
GAME_JAR="$(ls "$GAME"/app/desktop-*.jar 2>/dev/null | head -1 || true)"
API_JAR="$GAME/app/aoc-loader.jar"
[[ -n "$GAME_JAR" ]] || { echo "No game in $GAME; set AOC_GAME to the folder with 'Age of Clicks.exe'"; exit 1; }
[[ -f "$API_JAR" ]] || { echo "No mod loader in $GAME; run the mod loader setup first"; exit 1; }
SEP=":"
if command -v cygpath >/dev/null; then GAME_JAR="$(cygpath -w "$GAME_JAR")"; API_JAR="$(cygpath -w "$API_JAR")"; SEP=";"; fi
rm -rf build && mkdir -p build/classes
javac --release 17 -encoding UTF-8 -cp "$GAME_JAR$SEP$API_JAR" -d build/classes $(find src -name '*.java')
cp -r resources/. build/classes/
jar --create --file "build/$NAME.jar" -C build/classes .
echo "Built build/$NAME.jar"
if [[ "${1:-}" == "--install" ]]; then
  mkdir -p "$GAME/mods" && cp "build/$NAME.jar" "$GAME/mods/"
  echo "Installed into $GAME/mods (restart the game)"
fi
