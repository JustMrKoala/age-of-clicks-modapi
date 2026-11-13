#!/usr/bin/env bash
# Starts the server in the background; it keeps running after you log out. Log: server.log
cd "$(dirname "$0")"
pgrep -f "[r]un-server.sh" >/dev/null && { echo "already running"; exit 0; }
# Re-open the router ports (UPnP); harmless when the router does not allow it.
[[ -f upnp-ports.py ]] && python3 upnp-ports.py open >> server.log 2>&1
setsid nohup ./run-server.sh >> server.log 2>&1 < /dev/null &
echo "started"
