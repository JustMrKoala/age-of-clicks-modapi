package aoc.server;

import com.badlogic.gdx.utils.JsonReader;
import com.badlogic.gdx.utils.JsonValue;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import ru.ius.david.cgs.ScenarioData;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

/**
 * Central lobby server: one public HTTP API on a fixed port, and one {@link DedicatedServer}
 * process per lobby on its own port from a small pool. Players list, create and join lobbies
 * from the game's MULTIPLAYER screen; nothing to configure on their side.
 *
 * <pre>
 * GET  /api/info                 server name, scenarios, free slots
 * GET  /api/lobbies              open lobbies and matches
 * POST /api/lobbies              {"name","scenario","needed","host"} -> {"id","port","token"}
 *                                 needed 0 = starts only when the creator starts it; host = creator's player name
 * POST /api/lobbies/{id}/start   header X-Lobby-Token: start now (creator only)
 * POST /api/lobbies/{id}/players header X-Lobby-Token, {"needed"}: change when it starts by itself (creator only)
 * </pre>
 */
public final class LobbyService {
    private static final int MAX_BODY = 4096;

    private record Scenario(String path, String title, int year) {}

    private static final class Lobby {
        final String id, name, token, creator, host;
        final Scenario scenario;
        final int port;
        volatile int needed;
        final Process process;
        final File status;

        Lobby(String id, String name, String host, Scenario scenario, int needed, int port, String token, String creator,
              Process process, File status) {
            this.host = host;
            this.id = id;
            this.name = name;
            this.scenario = scenario;
            this.needed = needed;
            this.port = port;
            this.token = token;
            this.creator = creator;
            this.process = process;
            this.status = status;
        }
    }

    private final String serverName;
    private final int internalPort;
    private final int apiPort, poolFirst, poolSize, maxPlayers, cooldown, idleTimeout;
    private final String memory, java, gameJar;
    private final List<Scenario> scenarios = new ArrayList<>();
    private final Map<String, Lobby> lobbies = new ConcurrentHashMap<>();
    private final Map<String, Long> lastCreate = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();
    private final File lobbyDir = new File("lobbies"), logDir = new File("logs");

    private LobbyService(Properties p) {
        serverName = p.getProperty("server_name", "Age of Clicks central server").trim();
        apiPort = Integer.parseInt(p.getProperty("port", "54600").trim());
        internalPort = Integer.parseInt(p.getProperty("internal_port", String.valueOf(apiPort - 1)).trim());
        poolFirst = Integer.parseInt(p.getProperty("pool_first_port", "54601").trim());
        poolSize = Integer.parseInt(p.getProperty("pool_size", "2").trim());
        maxPlayers = Integer.parseInt(p.getProperty("max_players", "8").trim());
        cooldown = Integer.parseInt(p.getProperty("create_cooldown_seconds", "30").trim());
        idleTimeout = Integer.parseInt(p.getProperty("idle_timeout_seconds", "300").trim());
        memory = p.getProperty("memory_per_match", "800m").trim();
        java = new File("jre/bin/java").canExecute() ? "jre/bin/java" : "java";
        gameJar = findGameJar();
        List<String> paths = new ArrayList<>();
        for (String s : p.getProperty("scenarios", "all").split(",")) {
            String path = s.trim().replace('\\', '/');
            if (path.isEmpty()) continue;
            if (path.equalsIgnoreCase("all")) {
                // Every scenario that ships inside the game jar, so every player has its files.
                for (String id : ScenarioData.KNOWN_SCENARIO_IDS) paths.add("scenarios/" + id + "/");
                continue;
            }
            paths.add(path.endsWith("/") ? path : path + "/");
        }
        List<Scenario> found = new ArrayList<>();
        for (String path : paths) {
            if (found.stream().anyMatch(x -> x.path().equals(path))) continue;
            Scenario sc = scenario(path);
            if (sc != null) found.add(sc);
            else DedicatedServer.log("Scenario " + path + " not found; left out");
        }
        found.sort(Comparator.comparingInt(Scenario::year).thenComparing(Scenario::title));
        scenarios.addAll(found);
        if (scenarios.isEmpty()) throw new IllegalStateException("No scenarios found; check 'scenarios' in lobby-service.properties");
    }

    public static void main(String[] args) throws Exception {
        Properties p = new Properties();
        File f = new File(args.length > 0 ? args[0] : "lobby-service.properties");
        if (f.exists()) {
            try (Reader r = new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8)) {
                p.load(r);
            }
        }
        new LobbyService(p).run();
    }

    private void run() throws Exception {
        lobbyDir.mkdirs();
        logDir.mkdirs();
        for (File old : lobbyDir.listFiles()) old.delete();
        // The API itself listens on this machine only; the gateway on the public port passes requests
        // on to it and relays WebSocket connections (/ws/<match port>) to running matches, so the
        // whole service also works through a web tunnel (see Gateway).
        HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", internalPort), 32);
        new Gateway(apiPort, internalPort, port -> {
            for (Lobby l : lobbies.values()) if (l.port == port) return true;
            return false;
        }).start();
        http.setExecutor(Executors.newFixedThreadPool(4));
        http.createContext("/api/", this::handle);
        http.start();
        DedicatedServer.log("Lobby service '" + serverName + "' on port " + apiPort + "; match ports " + poolFirst
                + "-" + (poolFirst + poolSize - 1) + "; scenarios " + scenarios);
        while (true) {
            Thread.sleep(2000);
            for (Lobby l : new ArrayList<>(lobbies.values())) {
                if (!l.process.isAlive()) {
                    lobbies.remove(l.id);
                    l.status.delete();
                    DedicatedServer.log("Lobby " + l.id + " '" + l.name + "' closed (exit " + l.process.exitValue() + ")");
                }
            }
        }
    }

    // ---- HTTP ----

    private void handle(HttpExchange ex) {
        try (ex) {
            String path = ex.getRequestURI().getPath();
            String method = ex.getRequestMethod();
            if (method.equals("GET") && path.equals("/api/info")) {
                send(ex, 200, info());
            } else if (method.equals("GET") && path.equals("/api/lobbies")) {
                send(ex, 200, list());
            } else if (method.equals("POST") && path.equals("/api/lobbies")) {
                create(ex);
            } else if (method.equals("POST") && path.matches("/api/lobbies/[a-z0-9]+/start")) {
                start(ex, path.split("/")[3]);
            } else if (method.equals("POST") && path.matches("/api/lobbies/[a-z0-9]+/players")) {
                players(ex, path.split("/")[3]);
            } else {
                send(ex, 404, error("Not found"));
            }
        } catch (Exception e) {
            DedicatedServer.log("Request failed: " + e);
        }
    }

    private String info() {
        StringBuilder s = new StringBuilder();
        for (Scenario sc : scenarios) {
            if (s.length() > 0) s.append(',');
            s.append("{\"path\":").append(q(sc.path())).append(",\"title\":").append(q(sc.title())).append('}');
        }
        return "{\"server\":" + q(serverName) + ",\"version\":" + q(DedicatedServer.VERSION) + ",\"scenarios\":[" + s
                + "],\"slots\":" + poolSize + ",\"free\":" + (poolSize - lobbies.size()) + ",\"max_players\":" + maxPlayers + "}";
    }

    private String list() {
        StringBuilder s = new StringBuilder();
        for (Lobby l : lobbies.values()) {
            String status = "{\"players\":0,\"names\":[],\"started\":false,\"day\":0}";
            try {
                if (l.status.exists()) status = Files.readString(l.status.toPath(), StandardCharsets.UTF_8);
            } catch (Exception ignored) {
                // a status file is being replaced; show the default this time
            }
            if (s.length() > 0) s.append(',');
            s.append("{\"id\":").append(q(l.id)).append(",\"name\":").append(q(l.name)).append(",\"host\":").append(q(l.host))
             .append(",\"scenario\":").append(q(l.scenario.title())).append(",\"port\":").append(l.port)
             .append(",\"needed\":").append(l.needed).append(",\"status\":").append(status).append('}');
        }
        return "{\"lobbies\":[" + s + "]}";
    }

    private synchronized void create(HttpExchange ex) throws Exception {
        // The gateway says who really asks (through a tunnel every request comes from this machine).
        String ip = ex.getRequestHeaders().getFirst("X-Aoc-Client");
        if (ip == null) ip = ex.getRemoteAddress().getAddress().getHostAddress();
        Long last = lastCreate.get(ip);
        if (last != null && System.currentTimeMillis() - last < cooldown * 1000L) {
            send(ex, 429, error("Please wait a moment before creating another lobby."));
            return;
        }
        JsonValue body;
        try {
            body = new JsonReader().parse(readBody(ex));
        } catch (Exception e) {
            send(ex, 400, error("Bad request"));
            return;
        }
        String name = clean(body.getString("name", ""), 32);
        String scenarioPath = body.getString("scenario", scenarios.get(0).path());
        Scenario scenario = scenarios.stream().filter(s -> s.path().equals(scenarioPath)).findFirst().orElse(null);
        int needed = body.getInt("needed", 2);
        String host = clean(body.getString("host", ""), 32);
        if (scenario == null) {
            send(ex, 400, error("That scenario is not offered on this server."));
            return;
        }
        if (needed < 0 || needed > maxPlayers) {
            send(ex, 400, error("Players needed must be between 0 and " + maxPlayers + "."));
            return;
        }
        int port = freePort();
        if (port < 0) {
            send(ex, 503, error("The server is full right now. Try again when a match ends."));
            return;
        }
        if (name.isEmpty()) name = scenario.title() + " lobby";
        String id = Long.toString(random.nextLong() & Long.MAX_VALUE, 36).substring(0, 6);
        String token = Long.toString(random.nextLong() & Long.MAX_VALUE, 36) + Long.toString(random.nextLong() & Long.MAX_VALUE, 36);
        File status = new File(lobbyDir, id + ".json");
        List<String> cmd = List.of(java, "-Xmx" + memory, "-javaagent:aoc-server.jar",
                "-Daoc.port=" + port, "-Daoc.name=" + name, "-Daoc.scenario=" + scenario.path(), "-Daoc.host_player=" + host,
                "-Daoc.min_players=" + needed, "-Daoc.require_ready=false", "-Daoc.idle_timeout=" + idleTimeout,
                "-Daoc.status=" + status.getPath(), "-cp", "aoc-server.jar" + File.pathSeparator + gameJar,
                "aoc.server.DedicatedServer", "-");
        Process proc = new ProcessBuilder(cmd).redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(new File(logDir, "lobby-" + id + ".log"))).start();
        lobbies.put(id, new Lobby(id, name, host, scenario, needed, port, token, ip, proc, status));
        lastCreate.put(ip, System.currentTimeMillis());
        DedicatedServer.log("Lobby " + id + " '" + name + "' (" + scenario.title() + ", " + needed + " players) on port "
                + port + " for " + ip);
        send(ex, 201, "{\"id\":" + q(id) + ",\"port\":" + port + ",\"token\":" + q(token) + "}");
    }

    private void start(HttpExchange ex, String id) throws Exception {
        Lobby l = lobbies.get(id);
        if (l == null) {
            send(ex, 404, error("That lobby no longer exists."));
            return;
        }
        if (!l.token.equals(ex.getRequestHeaders().getFirst("X-Lobby-Token"))) {
            send(ex, 403, error("Only the player who created the lobby can start it early."));
            return;
        }
        OutputStream in = l.process.getOutputStream();
        in.write("start\n".getBytes(StandardCharsets.UTF_8));
        in.flush();
        send(ex, 200, "{\"ok\":true}");
    }

    private void players(HttpExchange ex, String id) throws Exception {
        Lobby l = lobbies.get(id);
        if (l == null) {
            send(ex, 404, error("That lobby no longer exists."));
            return;
        }
        if (!l.token.equals(ex.getRequestHeaders().getFirst("X-Lobby-Token"))) {
            send(ex, 403, error("Only the player who created the lobby can change it."));
            return;
        }
        int needed;
        try {
            needed = new JsonReader().parse(readBody(ex)).getInt("needed");
        } catch (Exception e) {
            send(ex, 400, error("Bad request"));
            return;
        }
        if (needed < 0 || needed > maxPlayers) {
            send(ex, 400, error("Players needed must be between 0 and " + maxPlayers + "."));
            return;
        }
        l.needed = needed;
        OutputStream in = l.process.getOutputStream();
        in.write(("players " + needed + "\n").getBytes(StandardCharsets.UTF_8));
        in.flush();
        send(ex, 200, "{\"ok\":true,\"needed\":" + needed + "}");
    }

    // ---- helpers ----

    private int freePort() {
        for (int p = poolFirst; p < poolFirst + poolSize; p++) {
            final int port = p;
            if (lobbies.values().stream().noneMatch(l -> l.port == port)) return port;
        }
        return -1;
    }

    private static String readBody(HttpExchange ex) throws Exception {
        try (InputStream in = ex.getRequestBody()) {
            byte[] data = in.readNBytes(MAX_BODY + 1);
            if (data.length > MAX_BODY) throw new IllegalArgumentException("body too large");
            return new String(data, StandardCharsets.UTF_8);
        }
    }

    private static void send(HttpExchange ex, int code, String json) throws Exception {
        byte[] data = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(code, data.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(data);
        }
    }

    private static String error(String message) { return "{\"error\":" + q(message) + "}"; }

    private static String q(String s) { return DedicatedServer.quote(s); }

    /** Printable text only, trimmed and limited in length. */
    private static String clean(String s, int max) {
        StringBuilder b = new StringBuilder();
        for (char c : s.toCharArray()) if (!Character.isISOControl(c)) b.append(c);
        String out = b.toString().trim();
        return out.length() > max ? out.substring(0, max) : out;
    }

    /** Name and year from the scenario's scenario.txt (inside the game jar or on disk); null if there is none. */
    private static Scenario scenario(String path) {
        Map<String, String> kv = new LinkedHashMap<>();
        try (InputStream in = open(path + "scenario.txt")) {
            if (in == null) return null;
            {
                for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\\R")) {
                    int eq = line.indexOf('=');
                    if (eq > 0) kv.put(line.substring(0, eq).trim().replace("﻿", ""), line.substring(eq + 1).trim());
                }
            }
        } catch (Exception e) {
            return null;
        }
        String folder = path.replaceAll("/$", "");
        folder = folder.substring(folder.lastIndexOf('/') + 1);
        String name = kv.getOrDefault("name", folder);
        String year = kv.get("year");
        int y;
        try {
            y = Integer.parseInt(year == null ? "" : year);
        } catch (NumberFormatException e) {
            y = 9999;
        }
        return new Scenario(path, year == null || name.contains(year) ? name : name + " (" + year + ")", y);
    }

    private static InputStream open(String resource) throws Exception {
        File f = new File(resource);
        if (f.exists()) return new FileInputStream(f);
        return LobbyService.class.getClassLoader().getResourceAsStream(resource);
    }

    private static String findGameJar() {
        File[] jars = new File(".").listFiles((d, n) -> n.startsWith("desktop-") && n.endsWith(".jar"));
        if (jars == null || jars.length == 0) throw new IllegalStateException("Copy desktop-*.jar from your game's app folder here");
        return jars[0].getName();
    }
}
