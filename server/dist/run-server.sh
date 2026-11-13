#!/usr/bin/env bash
# Runs the Age of Clicks dedicated server and hosts again after each match.
# Needs Java 17+ (a portable one in ./jre is used first) and desktop-*.jar copied from your own
# game's app/ folder into this folder. Memory: MEM=2g ./run-server.sh (default 1g).
# In the background (no terminal), console commands are read from commands.txt:
#   echo status >> commands.txt      (output goes to server.log)
cd "$(dirname "$0")"
GAME_JAR="$(ls desktop-*.jar 2>/dev/null | head -1)"
if [[ -z "$GAME_JAR" ]]; then
  echo "Copy desktop-*.jar from your Age of Clicks app/ folder into $(pwd) first."; exit 1
fi
JAVA=java
[[ -x jre/bin/java ]] && JAVA=jre/bin/java
run() { "$JAVA" -Xmx"${MEM:-1g}" -cp "aoc-server.jar:$GAME_JAR" aoc.server.DedicatedServer server.properties; }
while true; do
  if [[ -t 0 ]]; then
    run
  else
    touch commands.txt
    tail -n 0 -F commands.txt 2>/dev/null | run
  fi
  echo "Restarting in 5 seconds (Ctrl+C to quit)..."; sleep 5
done
