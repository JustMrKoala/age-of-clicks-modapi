package aoc.api.internal.bulkcities;

import com.badlogic.gdx.utils.JsonReader;
import com.badlogic.gdx.utils.JsonValue;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Real towns placed on the current map. The game ships a city list for each of its maps, and custom
 * scenarios bring their own: many of the same towns, at different scales and projections. Towns the
 * current map shares with a list (London, Paris, ...) tie that list to this map; each other town of
 * the list is then placed here through its nearest shared towns (a local similarity fit), so a town
 * this map lacks gets its real position on it.
 */
final class Gazetteer {
    /** A town on the current map, in map pixels; {@code sources}: how many city lists name it. */
    record Place(String name, String key, float x, float y, int sources) {}

    /** One city list: names and positions in its own map's units. */
    record Source(String id, List<String> names, float[] xy) {}

    /** A town of the current map: name and map pixel position. */
    record Known(String name, float x, float y) {}

    private static final int NEIGHBOURS = 6;

    final List<Place> places;

    private Gazetteer(List<Place> places) { this.places = places; }

    // ---- city lists ----

    /** Every city list: the game's built-in maps (inside its jar) and the scenarios folder. */
    static List<Source> loadSources(File gameDir) {
        List<Source> out = new ArrayList<>();
        File jar = gameJar();
        if (jar != null) {
            try (ZipFile zip = new ZipFile(jar)) {
                Enumeration<? extends ZipEntry> en = zip.entries();
                while (en.hasMoreElements()) {
                    ZipEntry e = en.nextElement();
                    String n = e.getName();
                    if (!n.startsWith("scenarios/") || !n.endsWith("/cities.json")) continue;
                    try (InputStream in = zip.getInputStream(e)) {
                        Source s = parse("game:" + n, new String(in.readAllBytes(), StandardCharsets.UTF_8));
                        if (s != null) out.add(s);
                    } catch (Exception ignored) {
                        // a broken list is skipped
                    }
                }
            } catch (Exception ignored) {
                // no jar access: the scenarios folder still counts
            }
        }
        File[] dirs = new File(gameDir, "scenarios").listFiles(File::isDirectory);
        if (dirs != null) {
            for (File d : dirs) {
                File f = new File(d, "cities.json");
                if (!f.isFile()) continue;
                try {
                    Source s = parse("folder:" + d.getName(), Files.readString(f.toPath(), StandardCharsets.UTF_8));
                    if (s != null) out.add(s);
                } catch (Exception ignored) {
                    // skipped
                }
            }
        }
        return out;
    }

    private static File gameJar() {
        try {
            return new File(ru.ius.david.cgs.game.GameManager.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (Exception e) {
            return null;
        }
    }

    static Source parse(String id, String json) {
        JsonValue root = new JsonReader().parse(json);
        JsonValue list = root.isArray() ? root : root.get("cities");
        if (list == null) return null;
        List<String> names = new ArrayList<>();
        List<Float> xy = new ArrayList<>();
        for (JsonValue c = list.child; c != null; c = c.next) {
            String name = c.getString("name", null);
            if (name == null || name.isBlank() || !c.has("x") || !c.has("y")) continue;
            names.add(name.trim());
            xy.add(c.getFloat("x"));
            xy.add(c.getFloat("y"));
        }
        if (names.size() < 4) return null;
        float[] a = new float[xy.size()];
        for (int i = 0; i < a.length; i++) a[i] = xy.get(i);
        return new Source(id, names, a);
    }

    /** Lower case without accents, spaces or punctuation: "Saint-Étienne" and "Saint Etienne" match. */
    static String key(String name) {
        String n = Normalizer.normalize(name, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return n.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", "");
    }

    // ---- placing the lists on this map ----

    /**
     * Places every town of the lists on the current map. {@code known}: the map's own towns (their
     * names tie the lists to it); {@code mapW}: map width in pixels (sets the tolerances).
     */
    static Gazetteer build(List<Source> sources, List<Known> known, int mapW) {
        Map<String, Known> byKey = new HashMap<>();
        for (Known k : known) byKey.putIfAbsent(key(k.name()), k);
        float maxResidual = mapW * 0.012f, maxReach = mapW * 0.12f;

        Map<String, float[]> best = new HashMap<>(); // key -> x, y, residual, sources
        Map<String, String> display = new HashMap<>();
        for (Source s : sources) {
            int n = s.names().size();
            // Shared towns: position in the list and on this map.
            List<float[]> anchors = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (int i = 0; i < n; i++) {
                String k = key(s.names().get(i));
                Known c = byKey.get(k);
                if (c != null && seen.add(k)) anchors.add(new float[]{s.xy()[i * 2], s.xy()[i * 2 + 1], c.x(), c.y()});
            }
            // Same name, different town (Rome in Georgia, Paris in Texas): a shared town that its own
            // neighbours put far from where this map has it is not the same place.
            for (int pass = 0; pass < 2 && anchors.size() > NEIGHBOURS; pass++) {
                anchors = consistent(anchors, maxResidual * 2f);
            }
            if (anchors.size() < NEIGHBOURS) continue;
            float[] dist = new float[anchors.size()];
            int[] order = new int[NEIGHBOURS];
            for (int i = 0; i < n; i++) {
                String name = s.names().get(i), k = key(name);
                if (k.length() < 2 || byKey.containsKey(k)) continue;
                float sx = s.xy()[i * 2], sy = s.xy()[i * 2 + 1];
                for (int a = 0; a < anchors.size(); a++) {
                    float dx = anchors.get(a)[0] - sx, dy = anchors.get(a)[1] - sy;
                    dist[a] = dx * dx + dy * dy;
                }
                nearest(dist, order);
                float[] fit = fit(anchors, order, sx, sy);
                if (fit == null) continue;
                float reach = (float) Math.sqrt(dist[order[0]]) * fit[3];
                if (fit[2] > maxResidual || reach > maxReach) continue;
                float[] b = best.get(k);
                if (b == null) {
                    best.put(k, new float[]{fit[0], fit[1], fit[2], 1});
                    display.put(k, name);
                } else {
                    b[3]++;
                    if (fit[2] < b[2]) {
                        b[0] = fit[0];
                        b[1] = fit[1];
                        b[2] = fit[2];
                    }
                }
            }
        }

        // The same town under two spellings lands on one spot: keep the better known one.
        List<Place> all = new ArrayList<>();
        for (Map.Entry<String, float[]> e : best.entrySet()) {
            float[] b = e.getValue();
            all.add(new Place(display.get(e.getKey()), e.getKey(), b[0], b[1], (int) b[3]));
        }
        all.sort((p, q) -> Integer.compare(q.sources(), p.sources()));
        float same = mapW * 0.0015f;
        Map<Long, List<Place>> grid = new HashMap<>();
        List<Place> kept = new ArrayList<>();
        for (Place p : all) {
            long cx = (long) Math.floor(p.x() / same), cy = (long) Math.floor(p.y() / same);
            boolean dup = false;
            for (long gx = cx - 1; gx <= cx + 1 && !dup; gx++) {
                for (long gy = cy - 1; gy <= cy + 1 && !dup; gy++) {
                    List<Place> cell = grid.get(gx * 1_000_003L + gy);
                    if (cell == null) continue;
                    for (Place q : cell) {
                        if (Math.hypot(q.x() - p.x(), q.y() - p.y()) < same) {
                            dup = true;
                            break;
                        }
                    }
                }
            }
            if (dup) continue;
            kept.add(p);
            grid.computeIfAbsent(cx * 1_000_003L + cy, k -> new ArrayList<>()).add(p);
        }
        return new Gazetteer(kept);
    }

    /** The shared towns that their nearest other shared towns place within {@code tolerance} pixels. */
    private static List<float[]> consistent(List<float[]> anchors, float tolerance) {
        List<float[]> kept = new ArrayList<>();
        float[] dist = new float[anchors.size()];
        int[] order = new int[NEIGHBOURS];
        for (int i = 0; i < anchors.size(); i++) {
            float[] a = anchors.get(i);
            for (int j = 0; j < anchors.size(); j++) {
                float dx = anchors.get(j)[0] - a[0], dy = anchors.get(j)[1] - a[1];
                dist[j] = j == i ? Float.MAX_VALUE : dx * dx + dy * dy;
            }
            nearest(dist, order);
            float[] fit = fit(anchors, order, a[0], a[1]);
            if (fit != null && Math.hypot(fit[0] - a[2], fit[1] - a[3]) <= tolerance) kept.add(a);
        }
        return kept;
    }

    /** Indices of the {@code order.length} smallest values. */
    private static void nearest(float[] dist, int[] order) {
        int k = order.length;
        for (int j = 0; j < k; j++) order[j] = -1;
        for (int a = 0; a < dist.length; a++) {
            for (int j = 0; j < k; j++) {
                if (order[j] == -1 || dist[a] < dist[order[j]]) {
                    System.arraycopy(order, j, order, j + 1, k - j - 1);
                    order[j] = a;
                    break;
                }
            }
        }
    }

    /**
     * Least-squares similarity (scale, rotation, shift) from list units to map pixels through the
     * chosen shared towns, applied to (sx, sy). Returns x, y, RMS residual (pixels) and the scale,
     * or null when the towns are degenerate.
     */
    private static float[] fit(List<float[]> anchors, int[] order, float sx, float sy) {
        double msx = 0, msy = 0, mcx = 0, mcy = 0;
        for (int i : order) {
            float[] a = anchors.get(i);
            msx += a[0];
            msy += a[1];
            mcx += a[2];
            mcy += a[3];
        }
        int n = order.length;
        msx /= n;
        msy /= n;
        mcx /= n;
        mcy /= n;
        double re = 0, im = 0, den = 0;
        for (int i : order) {
            float[] a = anchors.get(i);
            double px = a[0] - msx, py = a[1] - msy, qx = a[2] - mcx, qy = a[3] - mcy;
            // (q) * conj(p)
            re += qx * px + qy * py;
            im += qy * px - qx * py;
            den += px * px + py * py;
        }
        if (den < 1e-6) return null;
        re /= den;
        im /= den;
        double res = 0;
        for (int i : order) {
            float[] a = anchors.get(i);
            double px = a[0] - msx, py = a[1] - msy;
            double x = mcx + re * px - im * py, y = mcy + im * px + re * py;
            res += (x - a[2]) * (x - a[2]) + (y - a[3]) * (y - a[3]);
        }
        double px = sx - msx, py = sy - msy;
        return new float[]{(float) (mcx + re * px - im * py), (float) (mcy + im * px + re * py),
                (float) Math.sqrt(res / n), (float) Math.hypot(re, im)};
    }

    // ---- questions ----

    /**
     * The real town nearest to (x, y) within {@code radius} pixels whose name is free and that is
     * not one of this map's towns under another spelling (no town of the map within {@code taken}).
     */
    Place nearestFree(float x, float y, float radius, Set<String> usedKeys, List<Known> towns, float taken) {
        Place best = null;
        double bestD = radius;
        for (Place p : places) {
            double d = Math.hypot(p.x() - x, p.y() - y);
            if (d >= bestD || usedKeys.contains(p.key()) || occupied(p, towns, taken)) continue;
            best = p;
            bestD = d;
        }
        return best;
    }

    static boolean occupied(Place p, List<Known> towns, float taken) {
        for (Known k : towns) if (Math.abs(k.x() - p.x()) < taken && Math.abs(k.y() - p.y()) < taken
                && Math.hypot(k.x() - p.x(), k.y() - p.y()) < taken) return true;
        return false;
    }

    /** The names of the {@code count} towns (real or on the map) closest to (x, y): a region's naming style. */
    List<String> namesAround(float x, float y, int count, List<Known> towns) {
        List<Object[]> all = new ArrayList<>();
        for (Place p : places) all.add(new Object[]{p.name(), Math.hypot(p.x() - x, p.y() - y)});
        for (Known k : towns) all.add(new Object[]{k.name(), Math.hypot(k.x() - x, k.y() - y)});
        all.sort((a, b) -> Double.compare((double) a[1], (double) b[1]));
        List<String> out = new ArrayList<>();
        for (int i = 0; i < Math.min(count, all.size()); i++) out.add((String) all.get(i)[0]);
        return out;
    }
}
