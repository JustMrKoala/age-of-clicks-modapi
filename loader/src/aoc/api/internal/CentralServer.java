package aoc.api.internal;

import com.badlogic.gdx.utils.JsonReader;
import com.badlogic.gdx.utils.JsonValue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Client for the central lobby server's HTTP API (see aoc.server.LobbyService). */
final class CentralServer {
    record Scenario(String path, String title) {}

    record Info(String name, List<Scenario> scenarios, int free, int maxPlayers) {}

    /** needed 0: the match starts only when its creator starts it. host: the creator's player name ("" if unknown). */
    record Lobby(String id, String name, String host, String scenario, int port, int needed, int players,
                 List<String> names, boolean started, int day) {}

    record Created(String id, int port, String token) {}

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final String host;
    private final String base;

    /** WebSocket address of the server's relay when matches go through a web tunnel, else null. */
    private final String relay;

    /**
     * {@code address} is "host" or "host:port" (default API port 54600) for a server that is reached
     * directly, or a web address ("https://play.example.org") for one behind a web tunnel: then the
     * matches too go through that address, relayed over a WebSocket (see {@link TunnelRelay}).
     */
    CentralServer(String address) {
        String a = address.trim();
        if (a.startsWith("https://") || a.startsWith("http://")) {
            while (a.endsWith("/")) a = a.substring(0, a.length() - 1);
            host = java.net.URI.create(a).getHost();
            base = a + "/api";
            relay = (a.startsWith("https://") ? "wss://" : "ws://") + a.substring(a.indexOf("://") + 3) + "/ws/";
            return;
        }
        int colon = a.lastIndexOf(':');
        host = colon > 0 ? a.substring(0, colon) : a;
        base = "http://" + (colon > 0 ? a : a + ":54600") + "/api";
        relay = null;
    }

    /** The relay address for a match port, or null when matches are reached directly. */
    String relayFor(int port) { return relay == null ? null : relay + port; }

    String host() { return host; }

    CompletableFuture<Info> info() {
        return get("/info").thenApply(j -> {
            List<Scenario> list = new ArrayList<>();
            for (JsonValue s = j.get("scenarios").child; s != null; s = s.next) {
                list.add(new Scenario(s.getString("path"), s.getString("title")));
            }
            return new Info(j.getString("server", host), list, j.getInt("free", 0), j.getInt("max_players", 8));
        });
    }

    CompletableFuture<List<Lobby>> lobbies() {
        return get("/lobbies").thenApply(j -> {
            List<Lobby> out = new ArrayList<>();
            for (JsonValue l = j.get("lobbies").child; l != null; l = l.next) {
                JsonValue st = l.get("status");
                List<String> names = new ArrayList<>();
                if (st != null && st.get("names") != null) for (JsonValue n = st.get("names").child; n != null; n = n.next) names.add(n.asString());
                out.add(new Lobby(l.getString("id"), l.getString("name"), l.getString("host", ""), l.getString("scenario"), l.getInt("port"),
                        l.getInt("needed"), st == null ? 0 : st.getInt("players", 0), names,
                        st != null && st.getBoolean("started", false), st == null ? 0 : st.getInt("day", 0)));
            }
            return out;
        });
    }

    CompletableFuture<Created> create(String name, String scenario, int needed, String host) {
        String body = "{\"name\":" + json(name) + ",\"scenario\":" + json(scenario) + ",\"needed\":" + needed
                + ",\"host\":" + json(host) + "}";
        return send(HttpRequest.newBuilder(URI.create(base + "/lobbies")).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)))
                .thenApply(j -> new Created(j.getString("id"), j.getInt("port"), j.getString("token")));
    }

    CompletableFuture<Void> start(String id, String token) {
        return send(HttpRequest.newBuilder(URI.create(base + "/lobbies/" + id + "/start")).header("X-Lobby-Token", token)
                .POST(HttpRequest.BodyPublishers.noBody())).thenApply(j -> null);
    }

    /** Creator only: the player count at which the match starts by itself (0 = only on start). */
    CompletableFuture<Void> setNeeded(String id, String token, int needed) {
        return send(HttpRequest.newBuilder(URI.create(base + "/lobbies/" + id + "/players")).header("X-Lobby-Token", token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"needed\":" + needed + "}", StandardCharsets.UTF_8))).thenApply(j -> null);
    }

    private CompletableFuture<JsonValue> get(String path) {
        return send(HttpRequest.newBuilder(URI.create(base + path)).GET());
    }

    private CompletableFuture<JsonValue> send(HttpRequest.Builder req) {
        return http.sendAsync(req.timeout(Duration.ofSeconds(8)).build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenApply(r -> {
                    JsonValue j = new JsonReader().parse(r.body());
                    if (r.statusCode() >= 400) throw new IllegalStateException(j.getString("error", "Server error " + r.statusCode()));
                    return j;
                });
    }

    private static String json(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            if (c == '"' || c == '\\') b.append('\\').append(c);
            else if (c >= 0x20) b.append(c);
        }
        return b.append('"').toString();
    }
}
