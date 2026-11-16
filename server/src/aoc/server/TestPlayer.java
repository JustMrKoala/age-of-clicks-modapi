package aoc.server;

import ru.ius.david.cgs.MPHeadlessEnv;
import ru.ius.david.cgs.game.GameManager;
import ru.ius.david.cgs.multiplayer.MultiplayerManager;
import ru.ius.david.cgs.multiplayer.NetworkProtocol;

import java.util.List;

/**
 * Windowless pretend player for testing a host or dedicated server at any address. Joins,
 * marks itself ready, follows the game and reports whether the world advances. Based on the
 * game developer's {@code MPGuestTest}, which can only join 127.0.0.1.
 *
 * <pre>
 * java -cp "aoc-server.jar;desktop-0.63.jar" aoc.server.TestPlayer &lt;host&gt; [name] [seconds]
 * </pre>
 */
public final class TestPlayer {
    private static volatile boolean connected;
    private static volatile boolean started;
    private static volatile boolean loaded;
    private static volatile boolean hostLost;
    /** -Daoc.pick=false: stay unpicked, like a player who is away (tests "Start without waiting"). */
    private static final boolean PICKS = !"false".equals(System.getProperty("aoc.pick"));
    private static volatile String lobbyCountry;
    private static volatile String lobbyNames;
    /** Snapshots seen, and country entries in them this client could not match by name (desync). */
    private static int snapshots, unknownCountries;
    private static java.lang.reflect.Field pendingSnapshots;

    /** Looks at the snapshots waiting in the client before update() applies them. */
    @SuppressWarnings("unchecked")
    private static void countSnapshots(MultiplayerManager mp) {
        try {
            if (pendingSnapshots == null) {
                pendingSnapshots = MultiplayerManager.class.getDeclaredField("pendingSnapshots");
                pendingSnapshots.setAccessible(true);
            }
            List<ru.ius.david.cgs.multiplayer.WorldStateSnapshot> list = (List<ru.ius.david.cgs.multiplayer.WorldStateSnapshot>) pendingSnapshots.get(mp);
            if (!loaded) return;
            java.util.Map<String, ?> world = GameManager.getInstance().getWorldCountries();
            synchronized (list) {
                for (ru.ius.david.cgs.multiplayer.WorldStateSnapshot s : list) {
                    snapshots++;
                    if (s.renamedCountries != null && !s.renamedCountries.isEmpty()) DedicatedServer.log("Renames: " + s.renamedCountries);
                    if (s.countries == null) continue;
                    for (ru.ius.david.cgs.multiplayer.WorldStateSnapshot.CountrySnapshot c : s.countries) {
                        String to = s.renamedCountries == null ? null : s.renamedCountries.get(c.name);
                        if (!world.containsKey(c.name) && !(s.renamedCountries != null && s.renamedCountries.containsValue(c.name))
                                && (s.newCountries == null || s.newCountries.stream().noneMatch(n -> c.name.equals(n.name)))) {
                            if (unknownCountries++ < 5) DedicatedServer.log("Snapshot country unknown here: " + c.name);
                        }
                    }
                }
            }
        } catch (ReflectiveOperationException e) {
            // diagnostics only
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.out.println("Usage: TestPlayer <host address> [name] [seconds to play]");
            System.exit(2);
        }
        String host = args[0];
        String name = args.length > 1 ? args[1] : "TestPlayer";
        int seconds = args.length > 2 ? Integer.parseInt(args[2]) : 90;

        MPHeadlessEnv.install();
        MultiplayerManager mp = new MultiplayerManager();
        mp.setUiListener(new MultiplayerManager.MultiplayerListener() {
            @Override
            public void onConnected(int id) {
                connected = true;
                DedicatedServer.log("Connected to " + host + " as player #" + id + "; marking ready");
                mp.setReady(true);
            }

            @Override
            public void onDisconnected() { DedicatedServer.log("Disconnected."); }

            @Override
            public void onLobbyUpdated(NetworkProtocol.LobbyState s) {
                DedicatedServer.log("Lobby: " + (s.players == null ? 0 : s.players.size()) + " in lobby incl. host, scenario "
                        + s.scenarioPath + (s.gameStarted ? ", started" : ""));
                if (s.players == null) return;
                StringBuilder who = new StringBuilder();
                for (NetworkProtocol.PlayerInfo p : s.players) who.append(who.length() == 0 ? "" : ", ").append(p.playerName).append(p.isHost ? " (host)" : "");
                if (!who.toString().equals(lobbyNames)) {
                    lobbyNames = who.toString();
                    DedicatedServer.log("Lobby entries: " + lobbyNames);
                }
                for (NetworkProtocol.PlayerInfo p : s.players) {
                    if (p.playerId != mp.getLocalPlayerId() || p.selectedCountryName == null || p.selectedCountryName.equals(lobbyCountry)) continue;
                    lobbyCountry = p.selectedCountryName;
                    DedicatedServer.log("The lobby lists me as " + lobbyCountry + (p.ready ? ", ready" : ""));
                }
            }

            @Override
            public void onGameStarting(String scenarioPath, long seed, List<NetworkProtocol.PlayerInfo> players) {
                started = true;
                DedicatedServer.log("Game starting, loading " + scenarioPath);
                GameManager.reset();
                GameManager gm = GameManager.getInstance();
                gm.setRandomSeed(seed);
                gm.setNetworkMode(true);
                mp.setGameManager(gm);
                new Thread(() -> {
                    try {
                        MPHeadlessEnv.loadWorld(scenarioPath);
                        mp.setGameManager(gm);
                        loaded = true;
                        DedicatedServer.log("World loaded: " + gm.getWorldCountries().size() + " countries");
                        if (!PICKS) {
                            DedicatedServer.log("Not picking a country (aoc.pick=false)");
                            return;
                        }
                        // Like a real player in the game's pick menu: choose a country, then Ready.
                        List<String> names = new java.util.ArrayList<>();
                        for (ru.ius.david.cgs.game.CountryState s : gm.getWorldCountries().values()) names.add(s.getName());
                        java.util.Collections.sort(names);
                        String pick = names.get(Math.floorMod(name.hashCode(), names.size()));
                        int key = gm.getWarManager().getColorKeyByName(pick);
                        mp.selectCountry(key);
                        mp.setReady(true);
                        // The game's MapScreen does this when the pick menu closes; headless we do it here.
                        gm.setPlayerCountryByName(pick);
                        gm.getWarManager().setPlayerCountry(key);
                        DedicatedServer.log("Picked " + pick + " and pressed Ready");
                    } catch (Throwable t) {
                        DedicatedServer.log("World load FAILED: " + t);
                    }
                }, "world-load").start();
            }

            @Override
            public void onPlayerDisconnected(String n) { DedicatedServer.log("Player left: " + n); }

            @Override
            public void onCountrySelectRejected(int key, String reason) { DedicatedServer.log("Country rejected: " + reason); }

            @Override
            public void onHostDisconnectedDuringGame() {
                hostLost = true;
                DedicatedServer.log("Host disconnected during the game.");
            }
        });

        DedicatedServer.log("Joining " + host + " as " + name + " (port " + PortAgent.tcp() + ")...");
        mp.joinGame(host, name);

        long begin = System.currentTimeMillis();
        long playUntil = Long.MAX_VALUE;
        int firstDay = -1, lastDay = -1;
        long nextReport = 0;
        while (!hostLost) {
            countSnapshots(mp);
            mp.update(0.05f);
            Thread.sleep(50);
            long now = System.currentTimeMillis();
            if (!connected && now - begin > 20_000) {
                DedicatedServer.log("RESULT: FAIL - could not connect to " + host + " within 20 s (address, firewall, ports?)");
                mp.shutdown();
                System.exit(1);
            }
            if (!started && now - begin > 600_000) {
                DedicatedServer.log("RESULT: FAIL - connected, but the host did not start a game within 10 minutes");
                mp.shutdown();
                System.exit(1);
            }
            if (loaded && playUntil == Long.MAX_VALUE) playUntil = now + seconds * 1000L;
            if (loaded) {
                int day = GameManager.getInstance().getGameDays();
                if (firstDay < 0) firstDay = day;
                lastDay = day;
                if (now >= nextReport) {
                    GameManager g = GameManager.getInstance();
                    ru.ius.david.cgs.game.CountryState me = g.getPlayerCountry();
                    DedicatedServer.log("day " + day + ", speed x" + g.getPlaySpeed() + ", snapshots " + snapshots
                            + (me == null ? ", no country" : ", " + me.getName() + " gold " + me.getGold())
                            + ", unmatched countries in snapshots " + unknownCountries);
                    nextReport = now + 10_000;
                }
                if (now >= playUntil) break;
            }
        }
        boolean pass = !hostLost && loaded && lastDay > firstDay && unknownCountries == 0;
        DedicatedServer.log("RESULT: " + (pass ? "PASS" : "FAIL") + " - connected, game started, days " + firstDay + " -> " + lastDay
                + ", " + snapshots + " snapshots, " + unknownCountries + " unmatched countries" + (hostLost ? ", host lost" : ""));
        mp.shutdown();
        System.exit(pass ? 0 : 1);
    }
}
