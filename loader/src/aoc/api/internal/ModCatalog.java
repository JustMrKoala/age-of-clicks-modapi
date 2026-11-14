package aoc.api.internal;

import com.badlogic.gdx.utils.JsonReader;
import com.badlogic.gdx.utils.JsonValue;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * The mod catalog behind the Mod Browser: a JSON index of code mods ({@code catalog/index.json} in
 * the project's repository, made by {@code build.sh --catalog}) with the mod jars next to it.
 * A copy of the index is built into the loader, so the list also shows without a connection.
 * Downloads are https only and checked against the index's SHA-256 before they reach {@code mods/}.
 */
final class ModCatalog {
    static final String DEFAULT_URL = "https://raw.githubusercontent.com/JustMrKoala/age-of-clicks-modapi/main/catalog/index.json";
    private static final long MAX_BYTES = 64L << 20;

    /** One mod in the catalog; {@code loader} is the oldest loader version it runs on. */
    record Entry(String id, String name, String version, String author, String description,
                 String file, String sha256, long size, String loader) {}

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NORMAL).build();

    /** The index built into this loader. */
    static List<Entry> bundled() {
        try (InputStream in = ModCatalog.class.getResourceAsStream("/aoc-catalog.json")) {
            return in == null ? List.of() : parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (Exception e) {
            Log.error("Built-in mod catalog unreadable", e);
            return List.of();
        }
    }

    static List<Entry> parse(String json) {
        List<Entry> out = new ArrayList<>();
        JsonValue mods = new JsonReader().parse(json).get("mods");
        if (mods == null) return out;
        for (JsonValue m = mods.child; m != null; m = m.next) {
            String id = m.getString("id", ""), file = m.getString("file", "");
            // The file name ends up in mods/: plain names only, never a path.
            if (id.isEmpty() || !file.matches("[A-Za-z0-9._-]+\\.jar")) continue;
            out.add(new Entry(id, m.getString("name", id), m.getString("version", ""), m.getString("author", ""),
                    m.getString("description", ""), file, m.getString("sha256", "").toLowerCase(),
                    m.getLong("size", 0), m.getString("loader", "")));
        }
        return out;
    }

    /** The online index. */
    CompletableFuture<List<Entry>> fetch(String url) {
        return get(url, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)).thenApply(ModCatalog::parse);
    }

    /**
     * Downloads the mod's jar from next to the index, checks size and SHA-256, and moves it to
     * {@code target} (a new jar in mods/, or {@code <jar>.update} for a mod that is loaded now).
     */
    CompletableFuture<Void> download(Entry e, String indexUrl, File target) {
        String url = indexUrl.substring(0, indexUrl.lastIndexOf('/') + 1) + e.file();
        return get(url, HttpResponse.BodyHandlers.ofByteArray()).thenAccept(bytes -> {
            try {
                if (bytes.length > MAX_BYTES) throw new IOException("the download is too large");
                String sum = sha256(bytes);
                if (e.sha256().isEmpty() || !sum.equals(e.sha256())) {
                    throw new IOException("the download does not match the catalog's checksum");
                }
                File part = new File(target.getParentFile(), target.getName() + ".part");
                target.getParentFile().mkdirs();
                Files.write(part.toPath(), bytes);
                Files.move(part.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ex) {
                throw new java.util.concurrent.CompletionException(ex);
            }
        });
    }

    private <T> CompletableFuture<T> get(String url, HttpResponse.BodyHandler<T> handler) {
        if (!url.startsWith("https://")) {
            return CompletableFuture.failedFuture(new IOException("the catalog address must start with https://"));
        }
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(60))
                .header("User-Agent", "aoc-mod-loader").GET().build();
        return http.sendAsync(req, handler).thenApply(r -> {
            if (r.statusCode() != 200) throw new java.util.concurrent.CompletionException(new IOException("the server answered " + r.statusCode()));
            return r.body();
        });
    }

    static String sha256(byte[] bytes) {
        try {
            StringBuilder b = new StringBuilder();
            for (byte x : MessageDigest.getInstance("SHA-256").digest(bytes)) b.append(String.format("%02x", x));
            return b.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- changes that wait for a restart (a loaded jar cannot be replaced or deleted) ----

    static File updateFile(File jar) { return new File(jar.getParentFile(), jar.getName() + ".update"); }

    static File removeMarker(File jar) { return new File(jar.getParentFile(), jar.getName() + ".remove"); }

    /** Start of the game, before any mod is loaded: finishes updates and removals asked for last time. */
    static void applyPending(File modsDir) {
        File[] files = modsDir.listFiles();
        if (files == null) return;
        for (File f : files) {
            String n = f.getName();
            try {
                if (n.endsWith(".jar.remove")) {
                    File jar = new File(modsDir, n.substring(0, n.length() - ".remove".length()));
                    Files.deleteIfExists(jar.toPath());
                    Files.deleteIfExists(updateFile(jar).toPath());
                    Files.delete(f.toPath());
                    Log.info("Mod Browser: removed " + jar.getName());
                } else if (n.endsWith(".jar.update")) {
                    File jar = new File(modsDir, n.substring(0, n.length() - ".update".length()));
                    if (removeMarker(jar).exists()) continue;
                    Files.move(f.toPath(), jar.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    Log.info("Mod Browser: updated " + jar.getName());
                } else if (n.endsWith(".part")) {
                    Files.delete(f.toPath());
                }
            } catch (IOException e) {
                Log.error("Mod Browser: could not finish " + n, e);
            }
        }
    }

    /** Compares dotted versions: negative when {@code a} is older than {@code b}. */
    static int compare(String a, String b) {
        String[] x = a.split("[^0-9]+"), y = b.split("[^0-9]+");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            long p = i < x.length && !x[i].isEmpty() ? Long.parseLong(x[i]) : 0;
            long q = i < y.length && !y[i].isEmpty() ? Long.parseLong(y[i]) : 0;
            if (p != q) return Long.compare(p, q);
        }
        return 0;
    }
}
