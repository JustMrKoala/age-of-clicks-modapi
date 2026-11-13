Age of Clicks dedicated server (unofficial)
===========================================

A windowless host for the game's own multiplayer (not Steam). Players connect with
MULTIPLAYER (TEST) > Join by address, using this machine's IP address.

What you need
  - Java 17 or newer.        Linux: sudo apt install openjdk-17-jre-headless
  - desktop-*.jar from YOUR copy of the game (Steam: Age of Clicks > app folder). It is not
    included here; copy it next to aoc-server.jar.
  - For a scenario of your own: copy its folder from the game's scenarios/ folder into a
    scenarios/ folder next to the server, and point server.properties at it.

Central lobby server (recommended)
  Players open MULTIPLAYER in the game and see, create and join lobbies here; each lobby runs as
  its own match on its own port. Set the address once in Code Mods > Age of Clicks Mod Loader >
  Central server (default in the loader build).
    cp lobby-service.properties.example lobby-service.properties   (edit name, ports, scenarios)
    ./start-lobby-service.sh       start in the background (opens router ports via UPnP)
    ./stop-lobby-service.sh        stop it and every running match
  Logs: lobby-service.log, logs/lobby-<id>.log. API: http://<server>:54600/api/lobbies

Single match server
  1. Copy server.properties.example to server.properties and edit it.
  2. Linux:   chmod +x run-server.sh && ./run-server.sh      (hosts again after each match)
     Windows: run-server.bat
  3. Console commands: status, players, start, speed 1|2|4|8|16, stop

Ports
  TCP 54555 and UDP 54777 must be reachable (fixed by the game).
  FRITZ!Box and other UPnP routers: python3 upnp-ports.py open   (and: show, close). Needs the
  router to let this device open ports itself (FRITZ!Box: "Zelfstandig portvrijgaven").
  start-background.sh does this on every start. Players outside then use your public address.
  Linux firewall:  sudo ufw allow 54555/tcp && sudo ufw allow 54777/udp
  Home server:     forward both ports on your router to this machine.
  Virtual LAN (ZeroTier, Tailscale, Radmin VPN) needs no port forwarding.

Run it in the background (Linux, no admin rights needed)
  ./start-background.sh        start; keeps running after you log out (log: server.log)
  ./stop-server.sh             stop
  echo status >> commands.txt  send a console command (status, players, start, speed 2, stop)
  Start at boot: crontab -e  and add the line   @reboot /home/<you>/aoc-server/start-background.sh
  No Java installed and no admin rights? Unpack a Java 17+ JRE (e.g. Eclipse Temurin) into a
  folder named jre next to the server; run-server.sh uses jre/bin/java first.

Run it as a Linux service (optional)
  /etc/systemd/system/aoc-server.service:
    [Unit]
    Description=Age of Clicks server
    After=network-online.target
    [Service]
    WorkingDirectory=/opt/aoc-server
    ExecStart=/usr/bin/java -Xmx2g -cp aoc-server.jar:desktop-0.63.jar aoc.server.DedicatedServer server.properties
    Restart=always
    User=aoc
    [Install]
    WantedBy=multi-user.target
  then: sudo systemctl enable --now aoc-server   and   journalctl -u aoc-server -f   for the log.

Notes
  - This is the game developer's unreleased multiplayer; expect bugs.
  - Code mods are switched off in multiplayer games, for every player.
  - test-player.bat [address] is a pretend player for testing without a friend: it joins the host
    or server at that address, marks itself ready, stays up to 30 minutes and prints PASS or FAIL.
    Linux: java -cp "aoc-server.jar:desktop-0.63.jar" aoc.server.TestPlayer <address>
