#!/usr/bin/env bash
# Stops the lobby server and every running match.
pkill -f "[a]oc.server.LobbyService"; pkill -f "[a]oc.server.DedicatedServer"; echo "stopped"
