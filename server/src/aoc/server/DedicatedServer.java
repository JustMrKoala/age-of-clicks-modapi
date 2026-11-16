package aoc.server;

import ru.ius.david.cgs.MPHeadlessEnv;
import ru.ius.david.cgs.game.GameManager;
import ru.ius.david.cgs.game.actions.ChangeSpeedAction;
import ru.ius.david.cgs.multiplayer.GameServer;
import ru.ius.david.cgs.multiplayer.MultiplayerManager;
import ru.ius.david.cgs.multiplayer.NetworkProtocol;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Dedicated, windowless host for the game's own (non-Steam) multiplayer. Runs on Windows or
 * Linux next to the player's own copy of the game jar; the game itself is not bundled.
 *
 * <p>It follows the game developer's headless host test ({@code ru.ius.david.cgs.MPHostTest}):
 * host on the game's ports (TCP 54555, UDP 54777), start when the lobby is ready, load the world
 * headless, then advance the server in 50 ms steps.
 *
 * <pre>
 * java -cp "aoc-server.jar:desktop-0.63.jar" aoc.server.DedicatedServer [server.properties]
 * </pre>
 */
public final class DedicatedServer {
    static final String VERSION = "0.1.0";
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final String name;
    private final String scenario;
    /** Players needed for the match to start by itself; 0 = only when the creator starts it. The creator can change it. */
    private volatile int minPlayers;
    /** Name of the player who created the lobby; the lobby shows them as host. Null = the first to join. */
    private final String hostPlayer;
    /** The server's own lobby entry (it hosts as a player); -1 until connected. */
    private volatile int serverId = -1;
    private final boolean requireReady;
    private final boolean autoStart;
    private final int startSpeed;
    /** Seconds a lobby may stay empty before the server exits (0 = never); used by the lobby service. */
    private final int idleTimeout;
    /** File the lobby service reads to list this match (null = none). */
    private final File statusFile;

    private final ConcurrentLinkedQueue<String> commands = new ConcurrentLinkedQueue<>();
    private MultiplayerManager mp;
    private volatile NetworkProtocol.LobbyState lobby;
    private volatile boolean starting;
    private volatile boolean worldLoaded;
    /** After loading, players pick countries in the game's own menu; the clock waits for them. */
    private volatile boolean picking;
    private volatile boolean forceStart;
    /**
     * Holds the game's clock from the start until everyone has picked. GameServer ticks on its own
     * timer (tickAccumulator vs. gameSpeed) and never looks at GameManager.isGamePaused.
     */
    private volatile boolean holdTicks;
    private java.lang.reflect.Field tickAccumulator;
    private volatile boolean running = true;

    /** The server's own lobby entry pretends to have picked this "country", so clients don't wait on it. */
    private static final int SERVER_COLOR_KEY = -2;

    private DedicatedServer(Properties p) {
        // -Daoc.<key>=... overrides server.properties; the lobby service starts matches that way.
        for (String key : new String[]{"name", "scenario", "host_player", "min_players", "require_ready", "auto_start", "speed", "idle_timeout"}) {
            String v = System.getProperty("aoc." + key);
            if (v != null) p.setProperty(key, v);
        }
        name =p.getProperty("name", "Age of Clicks server").trim();
        String s = p.getProperty("scenario", "scenarios/1936/").trim().replace('\\', '/');
        scenario = s.endsWith("/") ? s : s + "/";
        minPlayers = Math.max(0, Integer.parseInt(p.getProperty("min_players", "2").trim()));
        String hp = p.getProperty("host_player", "").trim();
        hostPlayer = hp.isEmpty() ? null : hp;
        requireReady = Boolean.parseBoolean(p.getProperty("require_ready", "true").trim());
        autoStart = Boolean.parseBoolean(p.getProperty("auto_start", "true").trim());
        startSpeed = Integer.parseInt(p.getProperty("speed", "1").trim());
        idleTimeout = Integer.parseInt(p.getProperty("idle_timeout", "0").trim());
        String status = System.getProperty("aoc.status");
        statusFile = status == null ? null : new File(status);
    }

    public static void main(String[] args) throws Exception {
        File file = new File(args.length > 0 ? args[0] : "server.properties");
        Properties p = new Properties();
        if (file.exists()) {
            try (Reader r = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
                p.load(r);
            }
        } else {
            log("No " + file + " found, using defaults (see server.properties.example)");
        }
        System.exit(new DedicatedServer(p).run());
    }

    private int run() throws InterruptedException {
        log("Age of Clicks dedicated server " + VERSION + " - '" + name + "', scenario " + scenario);
        log("Players join with the game's multiplayer on port " + PortAgent.tcp() + " (TCP) / " + PortAgent.udp() + " (UDP).");
        MPHeadlessEnv.install();
        mp = new MultiplayerManager();
        mp.setUiListener(new Listener());
        mp.hostGame(scenario, name);
        startConsole();
        log("Ready. Type 'help' for commands.");

        boolean hadPlayersInGame = false;
        long emptySince = System.currentTimeMillis();
        long nextStatus = 0;
        while (running) {
            String cmd;
            while ((cmd = commands.poll()) != null) handle(cmd.trim());
            if (holdTicks) holdClock();
            relabelHost();
            mp.update(0.05f);
            Thread.sleep(50);
            long now = System.currentTimeMillis();
            if (statusFile != null && now >= nextStatus) {
                writeStatus();
                nextStatus = now + 2000;
            }
            if (!starting && idleTimeout > 0) {
                if (!guests().isEmpty()) {
                    emptySince = now;
                } else if (now - emptySince > idleTimeout * 1000L) {
                    log("Nobody in the lobby for " + idleTimeout + " s; closing it.");
                    running = false;
                }
            }
            if (picking) waitForPicks();
            if (worldLoaded) {
                int guests = guests().size();
                if (guests > 0) hadPlayersInGame = true;
                if (hadPlayersInGame && guests == 0) {
                    log("Every player has left the game; stopping. (Use a restart loop to host again.)");
                    running = false;
                }
            }
        }
        mp.shutdown();
        if (statusFile != null) statusFile.delete();
        log("Server stopped.");
        return 0;
    }

    /** One line of JSON for the lobby service: who is in, how many are needed, and the state. */
    private void writeStatus() {
        int day = worldLoaded ? GameManager.getInstance().getGameDays() : 0;
        StringBuilder names = new StringBuilder();
        for (NetworkProtocol.PlayerInfo p : guests()) {
            if (names.length() > 0) names.append(',');
            names.append(quote(p.playerName == null ? "?" : p.playerName));
        }
        String json = "{\"players\":" + guests().size() + ",\"names\":[" + names + "],\"needed\":" + minPlayers
                + ",\"started\":" + (starting || worldLoaded) + ",\"day\":" + day + "}";
        try {
            File tmp = new File(statusFile.getPath() + ".tmp");
            java.nio.file.Files.writeString(tmp.toPath(), json, StandardCharsets.UTF_8);
            java.nio.file.Files.move(tmp.toPath(), statusFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            log("Could not write status: " + e.getMessage());
        }
    }

    /** JSON string literal; control characters become spaces. */
    static String quote(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            if (c == '"' || c == '\\') b.append('\\').append(c);
            else if (c < 0x20) b.append(' ');
            else b.append(c);
        }
        return b.append('"').toString();
    }

    /** Players other than the server itself. */
    private List<NetworkProtocol.PlayerInfo> guests() {
        List<NetworkProtocol.PlayerInfo> out = new ArrayList<>();
        NetworkProtocol.LobbyState l = lobby;
        if (l == null || l.players == null) return out;
        for (NetworkProtocol.PlayerInfo p : l.players) if (p.playerId != serverId) out.add(p);
        return out;
    }

    private void maybeStart() {
        NetworkProtocol.LobbyState l = lobby;
        if (!autoStart || minPlayers <= 0 || starting || l == null || l.gameStarted) return;
        List<NetworkProtocol.PlayerInfo> g = guests();
        if (g.size() < minPlayers) return;
        if (requireReady && !g.stream().allMatch(p -> p.ready)) return;
        startGame();
    }

    /**
     * Country selection. Each client leaves the game's pick menu once every lobby entry is ready
     * with a country, the server's own entry included - so that entry is marked as such. The world
     * stays paused here until every player has picked and pressed Ready (or "start" forces it).
     */
    private void waitForPicks() {
        GameServer gs = mp.getServer();
        if (gs == null) return;
        boolean changed = false;
        for (NetworkProtocol.PlayerInfo p : gs.getPlayers().values()) {
            if (p.playerId == serverId && (!p.ready || p.selectedColorKey != SERVER_COLOR_KEY)) {
                p.ready = true;
                p.selectedColorKey = SERVER_COLOR_KEY;
                p.selectedCountryName = "Server";
                changed = true;
            }
        }
        if (changed) broadcastLobby(gs);
        List<NetworkProtocol.PlayerInfo> g = new ArrayList<>();
        try {
            for (NetworkProtocol.PlayerInfo p : gs.getPlayers().values()) if (p.playerId != serverId) g.add(p);
        } catch (java.util.ConcurrentModificationException e) {
            return; // the network thread changed the list; look again next step
        }
        boolean allPicked = !g.isEmpty() && g.stream().allMatch(p -> p.ready && p.selectedColorKey != -1);
        if (!allPicked && !forceStart) return;
        if (!allPicked) {
            // Clients stay in the pick menu until every entry is ready with a country, so players
            // who have not picked get a random free one (like the menu's dice button).
            assignRandomCountries(gs, g);
            broadcastLobby(gs);
        }
        picking = false;
        holdTicks = false;
        GameManager.getInstance().setGamePaused(false);
        log(allPicked ? "Every player picked a country and is ready; the game is running."
                : "Started without waiting for every player to pick.");
        if (startSpeed != 1) commands.add("speed " + startSpeed);
    }

    private void assignRandomCountries(GameServer gs, List<NetworkProtocol.PlayerInfo> guests) {
        ru.ius.david.cgs.WarManager wm = GameManager.getInstance().getWarManager();
        if (wm == null || wm.getCountries() == null) return;
        java.util.Set<Integer> taken = new java.util.HashSet<>();
        for (NetworkProtocol.PlayerInfo p : gs.getPlayers().values()) if (p.selectedColorKey != -1) taken.add(p.selectedColorKey);
        List<ru.ius.david.cgs.CountryData> free = new ArrayList<>();
        // Same countries the game's pick menu offers (CGSCountrySelectMenu.collectSelectableCountries).
        for (ru.ius.david.cgs.CountryData c : wm.getCountries().values()) {
            if (c != null && c.originalPixels > 0 && c.name != null && !c.name.isEmpty() && !taken.contains(c.colorKey)) free.add(c);
        }
        java.util.Collections.shuffle(free);
        for (NetworkProtocol.PlayerInfo p : guests) {
            if (p.ready && p.selectedColorKey != -1) continue;
            if (p.selectedColorKey == -1) {
                if (free.isEmpty()) {
                    log("No free country left for " + p.playerName + ".");
                    continue;
                }
                ru.ius.david.cgs.CountryData c = free.remove(free.size() - 1);
                p.selectedColorKey = c.colorKey;
                p.selectedCountryName = c.name;
            }
            p.ready = true;
            log(p.playerName + " did not pick in time and plays " + p.selectedCountryName + ".");
        }
    }

    /**
     * The game's lobby shows "Host" next to the entry with {@code isHost}, which is the server itself
     * (it hosts as a player named after the lobby). Players expect the one who made the lobby there,
     * so the label moves to the creator (by name, else the first player to join) and the server's
     * own entry is called "Server". The flag only labels entries; hosting is the server's connection.
     */
    private void relabelHost() {
        GameServer gs = mp.getServer();
        if (gs == null || serverId < 0) return;
        try {
            NetworkProtocol.PlayerInfo byName = null, first = null;
            for (NetworkProtocol.PlayerInfo p : gs.getPlayers().values()) {
                if (p.playerId == serverId) continue;
                if (first == null || p.playerId < first.playerId) first = p;
                if (hostPlayer != null && hostPlayer.equalsIgnoreCase(p.playerName) && (byName == null || p.playerId < byName.playerId)) byName = p;
            }
            NetworkProtocol.PlayerInfo creator = byName != null ? byName : first;
            boolean changed = false;
            for (NetworkProtocol.PlayerInfo p : gs.getPlayers().values()) {
                boolean host = p == creator;
                if (p.playerId == serverId && !"Server".equals(p.playerName)) {
                    p.playerName = "Server";
                    changed = true;
                }
                if (p.isHost != host) {
                    p.isHost = host;
                    changed = true;
                }
            }
            if (changed) broadcastLobby(gs);
        } catch (java.util.ConcurrentModificationException e) {
            // the network thread changed the list; look again next step
        }
    }

    /** Keeps GameServer from ticking during the next update (see {@link #holdTicks}). */
    private void holdClock() {
        GameServer gs = mp.getServer();
        if (gs == null) return;
        try {
            if (tickAccumulator == null) {
                tickAccumulator = GameServer.class.getDeclaredField("tickAccumulator");
                tickAccumulator.setAccessible(true);
            }
            tickAccumulator.setFloat(gs, 0f);
        } catch (ReflectiveOperationException e) {
            log("Cannot hold the game clock: " + e);
            holdTicks = false;
        }
    }

    private static void broadcastLobby(GameServer gs) {
        try {
            Method m = GameServer.class.getDeclaredMethod("broadcastLobbyState");
            m.setAccessible(true);
            m.invoke(gs);
        } catch (ReflectiveOperationException e) {
            log("Could not update the lobby: " + e);
        }
    }

    private void startGame() {
        if (picking) {
            forceStart = true;
            return;
        }
        if (starting) {
            log("The game is already starting or running.");
            return;
        }
        if (guests().isEmpty()) {
            log("Nobody has joined yet.");
            return;
        }
        starting = true;
        log("Starting the game with " + guests().size() + " player(s)...");
        mp.startGame();
    }

    // ---- console ----

    private void startConsole() {
        Thread t = new Thread(() -> {
            try (BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null) commands.add(line);
            } catch (Exception ignored) {
                // no console (e.g. running as a service): commands are simply unavailable
            }
        }, "console");
        t.setDaemon(true);
        t.start();
    }

    private void handle(String cmd) {
        switch (cmd.toLowerCase()) {
            case "": break;
            case "help":
                log("Commands: status, players, players <n> (start by itself at n players, 0 = only on start), start (start now), speed <1|2|4|8|16>, stop (shut down)");
                break;
            case "status": {
                String state = worldLoaded ? "in game, day " + GameManager.getInstance().getGameDays()
                        : starting ? "loading the world" : "in lobby";
                log("'" + name + "' " + state + "; players: " + guests().size()
                        + (autoStart ? "; auto-start at " + minPlayers + (requireReady ? " ready" : "") + " player(s)" : ""));
                break;
            }
            case "players":
                if (guests().isEmpty()) log("No players.");
                for (NetworkProtocol.PlayerInfo p : guests()) {
                    log("  #" + p.playerId + " " + p.playerName + (p.ready ? " (ready)" : "")
                            + (p.selectedCountryName != null ? " - " + p.selectedCountryName : ""));
                }
                break;
            case "start":
                startGame();
                break;
            case "stop":
            case "quit":
            case "exit":
                running = false;
                break;
            default:
                if (cmd.toLowerCase().startsWith("players ")) {
                    try {
                        int n = Integer.parseInt(cmd.substring(8).trim());
                        if (n < 0) throw new NumberFormatException();
                        minPlayers = n;
                        log(n == 0 ? "The match starts when the creator starts it." : "The match starts by itself at " + n + " player(s).");
                        maybeStart();
                    } catch (NumberFormatException e) {
                        log("Usage: players <number, 0 = only on start>");
                    }
                    break;
                }
                if (cmd.toLowerCase().startsWith("speed ")) {
                    try {
                        setSpeed(Integer.parseInt(cmd.substring(6).trim()));
                    } catch (NumberFormatException e) {
                        log("Usage: speed 1|2|4|8|16");
                    }
                    break;
                }
                log("Unknown command '" + cmd + "'. Type 'help'.");
        }
    }

    /** Game speed for everyone, sent as the game's own action from the host (player #0). */
    private void setSpeed(int speed) {
        if (!worldLoaded) {
            log("Speed can be changed once the game is running.");
            return;
        }
        mp.enqueueAction(new ChangeSpeedAction(0, speed));
        log("Speed set to x" + speed);
    }

    static void log(String msg) {
        System.out.println("[server " + LocalTime.now().format(TIME) + "] " + msg);
    }

    // ---- multiplayer events ----

    private final class Listener implements MultiplayerManager.MultiplayerListener {
        private int lastCount = -1;

        @Override
        public void onConnected(int playerId) {
            serverId = playerId;
            log("Hosting as player #" + playerId);
        }

        @Override
        public void onDisconnected() { log("Server connection closed."); }

        @Override
        public void onLobbyUpdated(NetworkProtocol.LobbyState state) {
            lobby = state;
            int count = guests().size();
            if (count != lastCount) {
                log("Lobby: " + count + " player(s)" + (state.gameStarted ? " (game running)" : ""));
                lastCount = count;
            }
            maybeStart();
        }

        @Override
        public void onGameStarting(String scenarioPath, long seed, List<NetworkProtocol.PlayerInfo> players) {
            log("Game starting, seed " + seed + ", loading " + scenarioPath);
            holdTicks = true;
            GameManager.reset();
            GameManager gm = GameManager.getInstance();
            gm.setRandomSeed(seed);
            gm.setNetworkMode(true);
            mp.setGameManager(gm);
            Thread load = new Thread(() -> {
                try {
                    MPHeadlessEnv.loadWorld(scenarioPath);
                    mp.setGameManager(gm);
                    gm.setGamePaused(true);
                    picking = true;
                    worldLoaded = true;
                    log("World loaded: " + gm.getWorldCountries().size() + " countries. Waiting for players to pick countries.");
                } catch (Throwable t) {
                    log("World load FAILED: " + t);
                    t.printStackTrace();
                    running = false;
                }
            }, "world-load");
            load.start();
        }

        @Override
        public void onPlayerDisconnected(String playerName) { log("Player left: " + playerName); }

        @Override
        public void onCountrySelectRejected(int colorKey, String reason) { log("Country choice rejected: " + reason); }

        @Override
        public void onHostDisconnectedDuringGame() { log("Host connection lost."); }
    }
}
