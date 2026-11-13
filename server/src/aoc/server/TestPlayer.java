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
                        // Like a real player in the game's pick menu: choose a country, then Ready.
                        List<String> names = new java.util.ArrayList<>();
                        for (ru.ius.david.cgs.game.CountryState s : gm.getWorldCountries().values()) names.add(s.getName());
                        java.util.Collections.sort(names);
                        String pick = names.get(Math.floorMod(name.hashCode(), names.size()));
                        int key = gm.getWarManager().getColorKeyByName(pick);
                        mp.selectCountry(key);
                        mp.setReady(true);
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
                    DedicatedServer.log("day " + day + ", speed x" + GameManager.getInstance().getPlaySpeed());
                    nextReport = now + 10_000;
                }
                if (now >= playUntil) break;
            }
        }
        boolean pass = !hostLost && loaded && lastDay > firstDay;
        DedicatedServer.log("RESULT: " + (pass ? "PASS" : "FAIL") + " - connected, game started, days " + firstDay + " -> " + lastDay
                + (hostLost ? ", host lost" : ""));
        mp.shutdown();
        System.exit(pass ? 0 : 1);
    }
}
