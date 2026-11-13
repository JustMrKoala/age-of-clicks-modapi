#!/usr/bin/env bash
# Builds app/aoc-loader.jar (mod loader + API) and mods/*.jar from source.
# Needs a JDK 17+ on PATH (the game runs on Java 17, so classes target 17) and your own copy
# of the game: the game jar is only compiled against, never copied into this repository.
#   AOC_GAME            game folder (default: two folders up, else Steam's default folder)
#   AOC_CENTRAL_SERVER  lobby server the loader suggests (default: central-server.txt, else none)
set -euo pipefail
cd "$(dirname "$0")"
if [[ -n "${AOC_GAME:-}" ]]; then GAME="$AOC_GAME"
elif ls ../../app/desktop-*.jar >/dev/null 2>&1; then GAME="$(cd ../.. && pwd)"
else GAME="/c/Program Files (x86)/Steam/steamapps/common/Age of Clicks"; fi
GAME_JAR="$(ls "$GAME"/app/desktop-*.jar 2>/dev/null | head -1 || true)"
[[ -n "$GAME_JAR" ]] || { echo "No Age of Clicks in $GAME; set AOC_GAME to the folder with 'Age of Clicks.exe'"; exit 1; }
OUT=build

# ASM 9.6 (BSD licence) is bundled into the loader; the game's own shaded ASM is too old.
ASM=lib/asm-9.6.jar
if [[ ! -f "$ASM" ]]; then
  echo "== downloading $ASM"
  mkdir -p lib && curl -fsSL -o "$ASM" https://repo1.maven.org/maven2/org/ow2/asm/asm/9.6/asm-9.6.jar
fi
CENTRAL="${AOC_CENTRAL_SERVER:-}"
[[ -z "$CENTRAL" && -f central-server.txt ]] && CENTRAL="$(tr -d ' \r\n' < central-server.txt)"
VERSION="$(grep -oE 'LOADER = "[^"]+"' loader/src/aoc/api/internal/Version.java | cut -d'"' -f2)"
# Windows JDKs need Windows paths and ';' between classpath entries.
SEP=":"
if command -v cygpath >/dev/null; then GAME_JAR="$(cygpath -w "$GAME_JAR")"; SEP=";"; fi
rm -rf "$OUT" && mkdir -p "$OUT/loader" "$OUT/diplomacy"

echo "== loader"
javac --release 17 -nowarn -encoding UTF-8 -cp "$GAME_JAR${SEP}lib/asm-9.6.jar" -d "$OUT/loader" \
  $(find loader/src -name '*.java')
(cd "$OUT/loader" && unzip -qo ../../lib/asm-9.6.jar -x 'META-INF/*' module-info.class)
printf 'central_server=%s\n' "$CENTRAL" > "$OUT/loader/aoc-build.properties"
printf 'Premain-Class: aoc.loader.Agent\nImplementation-Title: Age of Clicks Mod Loader\nImplementation-Version: %s\n' "$VERSION" > "$OUT/loader.mf"
jar --create --file "$OUT/aoc-loader.jar" --manifest "$OUT/loader.mf" -C "$OUT/loader" .

echo "== dedicated server"
mkdir -p "$OUT/server"
javac --release 17 -nowarn -encoding UTF-8 -cp "$GAME_JAR${SEP}lib/asm-9.6.jar" -d "$OUT/server" $(find server/src -name '*.java')
# PortAgent rewrites the game's fixed ports, so the server jar carries ASM too.
(cd "$OUT/server" && unzip -qo ../../lib/asm-9.6.jar -x 'META-INF/*' module-info.class)
printf 'Main-Class: aoc.server.DedicatedServer\nPremain-Class: aoc.server.PortAgent\nImplementation-Title: Age of Clicks Dedicated Server\n' > "$OUT/server.mf"
jar --create --file "$OUT/aoc-server.jar" --manifest "$OUT/server.mf" -C "$OUT/server" .

# Every folder in mods/ is one mod jar: mods/<name>/src + resources -> build/mods/<name>.jar
mkdir -p "$OUT/mods"
for dir in mods/*/; do
  [[ -d "$dir/src" ]] || continue
  MOD="$(basename "$dir")"
  echo "== $MOD mod"
  mkdir -p "$OUT/$MOD"
  javac --release 17 -nowarn -encoding UTF-8 -cp "$GAME_JAR${SEP}$OUT/loader" -d "$OUT/$MOD" $(find "$dir/src" -name '*.java')
  cp -r "$dir/resources/." "$OUT/$MOD/"
  jar --create --file "$OUT/mods/$MOD.jar" -C "$OUT/$MOD" .
done

echo "== template (compile check)"
mkdir -p "$OUT/template"
javac --release 17 -nowarn -encoding UTF-8 -cp "$GAME_JAR${SEP}$OUT/loader" -d "$OUT/template" $(find template/src -name '*.java')

CFG="$GAME/app/Age of Clicks.cfg"
AGENT_LINE='java-options=-javaagent:$APPDIR/aoc-loader.jar'
if [[ "${1:-}" == "--install" ]]; then
  cp "$OUT/aoc-loader.jar" "$GAME/app/"
  mkdir -p "$GAME/mods"
  cp "$OUT"/mods/*.jar "$GAME/mods/"
  mkdir -p "$GAME/server" && cp "$OUT/aoc-server.jar" server/dist/* "$GAME/server/"
  [[ -f "$GAME/server/server.properties" ]] || cp server/dist/server.properties.example "$GAME/server/server.properties"
  if ! grep -qF "$AGENT_LINE" "$CFG"; then
    [[ -f "$CFG.vanilla" ]] || cp "$CFG" "$CFG.vanilla"
    # The launcher reads options from [JavaOptions]; append our line right after the header.
    AGENT_LINE="$AGENT_LINE" awk '{ print } /^\[JavaOptions\]/ { print ENVIRON["AGENT_LINE"] }' "$CFG" > "$CFG.tmp"
    mv "$CFG.tmp" "$CFG"
  fi
  echo "Installed into $GAME (vanilla launch config kept as app/Age of Clicks.cfg.vanilla)"
elif [[ "${1:-}" == "--uninstall" ]]; then
  [[ -f "$CFG.vanilla" ]] && cp "$CFG.vanilla" "$CFG"
  rm -f "$GAME/app/aoc-loader.jar"
  echo "Loader removed; mods/ left in place"
fi
if [[ "${1:-}" == "--dist" ]]; then
  NAME="AgeOfClicks-ModAPI-$VERSION"
  rm -rf dist && mkdir -p "dist/$NAME/installer" "dist/$NAME/files/app" "dist/$NAME/files/mods"
  cp "$OUT/aoc-loader.jar" "dist/$NAME/files/app/"
  cp "$OUT"/mods/*.jar "dist/$NAME/files/mods/"
  cp installer/*.ps1 "dist/$NAME/installer/"
  cp installer/Setup.bat installer/Install.bat installer/Uninstall.bat installer/README.txt "dist/$NAME/"
  mkdir -p "dist/$NAME/server" && cp "$OUT/aoc-server.jar" server/dist/* "dist/$NAME/server/"
  # What the installer compares against the player's game, and the server it preselects.
  printf 'version=%s\ngame_jar=%s\ncentral_server=%s\n' "$VERSION" "$(basename "$GAME_JAR")" "$CENTRAL" > "dist/$NAME/files/build-info.txt"
  # Windows tools expect CRLF line endings.
  for f in "dist/$NAME"/*.bat "dist/$NAME"/*.txt "dist/$NAME"/installer/*.ps1 "dist/$NAME"/server/*.bat "dist/$NAME"/server/*.txt "dist/$NAME"/server/*.example; do sed -i 's/\r*$/\r/' "$f"; done
  python -I -c "import shutil,sys; shutil.make_archive(sys.argv[1], 'zip', 'dist', sys.argv[2])" "dist/$NAME" "$NAME"
  echo "Packaged dist/$NAME.zip"
  if command -v powershell >/dev/null; then
    powershell -NoProfile -ExecutionPolicy Bypass -File installer/exe/make-exe.ps1 \
      -Zip "$(cygpath -w "dist/$NAME.zip")" -Out "$(cygpath -w "dist/AgeOfClicks-ModAPI-Setup-$VERSION.exe")"
  fi

  echo "== SDK (docs, javadoc, template)"
  mkdir -p "$OUT/tools"
  javac -nowarn -encoding UTF-8 -cp "$GAME_JAR${SEP}$OUT/loader" -d "$OUT/tools" tools/CatalogDump.java
  java -cp "$GAME_JAR${SEP}$OUT/loader${SEP}$OUT/tools" CatalogDump > docs/action-catalog.md
  SDK="dist/AgeOfClicks-ModAPI-SDK-$VERSION"
  mkdir -p "$SDK/template"
  cp -r docs "$SDK/docs"
  javadoc -quiet -Xdoclint:none -encoding UTF-8 -cp "$GAME_JAR${SEP}lib/asm-9.6.jar" -sourcepath loader/src \
    -d "$SDK/docs/javadoc" -doctitle "Age of Clicks Mod API $VERSION" -windowtitle "Age of Clicks Mod API" \
    aoc.api aoc.api.actions aoc.api.settings aoc.api.ui aoc.api.world 2>&1 | grep -v '^Loading\|^Constructing\|^Generating\|^Building\|^Standard' || true
  cp -r template/src template/resources template/build.sh template/build.bat "$SDK/template/"
  cp installer/SDK-README.txt "$SDK/README.txt"
  for f in "$SDK"/README.txt "$SDK"/template/build.bat; do sed -i 's/\r*$/\r/' "$f"; done
  python -I -c "import shutil,sys; shutil.make_archive(sys.argv[1], 'zip', 'dist', sys.argv[2])" "$SDK" "$(basename "$SDK")"
  echo "Packaged $SDK.zip"
fi
echo "done"
