#!/usr/bin/env bash
# Starts the central lobby server in the background. Log: lobby-service.log, per lobby: logs/
cd "$(dirname "$0")"
pgrep -f "[a]oc.server.LobbyService" >/dev/null && { echo "already running"; exit 0; }
[[ -f lobby-service.properties ]] || cp lobby-service.properties.example lobby-service.properties
[[ -f upnp-ports.py ]] && python3 upnp-ports.py open >> lobby-service.log 2>&1
JAVA=java; [[ -x jre/bin/java ]] && JAVA=jre/bin/java
GAME_JAR="$(ls desktop-*.jar | head -1)"
setsid nohup "$JAVA" -Xmx128m -cp "aoc-server.jar:$GAME_JAR" aoc.server.LobbyService lobby-service.properties \
  >> lobby-service.log 2>&1 < /dev/null &
echo "started"
