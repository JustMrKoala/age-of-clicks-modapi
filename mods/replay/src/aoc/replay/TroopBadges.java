package aoc.replay;

import ru.ius.david.cgs.WarManager;
import ru.ius.david.cgs.game.CountryState;
import ru.ius.david.cgs.game.GameManager;
import ru.ius.david.cgs.units.FrontlineDecor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Troop counts for the film. In classic combat the game draws army flags along the fronts but no
 * numbers, so the flags of each country that stand close together on screen get one badge: the
 * country's army split over its fronts by how many of its flags stand there.
 */
final class TroopBadges {
    record Badge(float x, float y, int owner, long troops, int flags) {}

    /** Last frame's badges: numbers ease from them, so they do not flicker as flags regroup. */
    private static List<Badge> previous = new ArrayList<>();

    /** Self-test: how many markers passed each check in the last call. */
    static String debug = "";

    private TroopBadges() {}

    /**
     * Badges for the film camera: centre {@code camX, camY} and {@code visibleH} in map units (y down),
     * screen {@code W x H} pixels; only inside {@code top..bottom} (the bars cover the rest).
     */
    static List<Badge> collect(float camX, float camY, float visibleH, int W, int H, float top, float bottom) {
        List<Badge> out = new ArrayList<>();
        GameManager gm = GameManager.getInstance();
        WarManager wm = gm == null ? null : gm.getWarManager();
        FrontlineDecor decor = wm == null ? null : wm.getFrontlineDecor();
        if (decor == null || decor.isEmpty() || !gm.isClassicCombat()) return out;
        float scale = H / visibleH, radius = H * 0.08f;
        Map<Integer, Integer> totalFlags = new HashMap<>();
        List<float[]> clusters = new ArrayList<>(); // owner, sum x, sum y, count
        int all = 0, alive = 0, war = 0, shown = 0, onScreen = 0;
        float sampleX = 0, sampleY = 0;
        for (FrontlineDecor.Marker m : decor.getMarkers()) {
            all++;
            if (!m.alive || m.alpha < 0.3f) continue;
            alive++;
            if (m.enemyColorKey == m.ownerColorKey || !wm.areAtWar(m.ownerColorKey, m.enemyColorKey)) continue;
            war++;
            if (!gm.showsUnitFlagsOf(m.ownerColorKey)) continue;
            shown++;
            totalFlags.merge(m.ownerColorKey, 1, Integer::sum);
            // Where the film draws the flag (the loader puts film flags on the marker itself).
            float mx = m.x, my = m.y;
            float sx = W / 2f + (mx - camX) * scale, sy = H / 2f + (my - camY) * scale;
            sampleX = sx;
            sampleY = sy;
            if (sx < 0 || sx > W || sy < top || sy > bottom) continue;
            onScreen++;
            float[] best = null;
            for (float[] c : clusters) {
                if (c[0] != m.ownerColorKey) continue;
                float dx = c[1] / c[3] - sx, dy = c[2] / c[3] - sy;
                if (dx * dx + dy * dy < radius * radius) {
                    best = c;
                    break;
                }
            }
            if (best == null) clusters.add(new float[]{m.ownerColorKey, sx, sy, 1});
            else {
                best[1] += sx;
                best[2] += sy;
                best[3]++;
            }
        }
        debug = "markers " + all + ", alive " + alive + ", at war " + war + ", shown " + shown + ", on screen " + onScreen
                + ", last at " + Math.round(sampleX) + "," + Math.round(sampleY) + ", clusters " + clusters.size();
        clusters.sort((a, b) -> Float.compare(b[3], a[3]));
        for (float[] c : clusters) {
            int owner = (int) c[0];
            CountryState s = wm.stateOf(owner);
            if (s == null) continue;
            long army = Math.max(0, s.getInfantry()) + Math.max(0, s.getArtillery()) + Math.max(0, s.getTanks());
            int ownerFlags = Math.max(1, totalFlags.getOrDefault(owner, 1));
            long troops = Math.round(army * (c[3] / ownerFlags));
            if (troops <= 0) continue;
            float x = c[1] / c[3], y = c[2] / c[3];
            Badge before = null;
            for (Badge b : previous) {
                float dx = b.x() - x, dy = b.y() - y;
                if (b.owner() == owner && dx * dx + dy * dy < radius * radius * 2.25f) {
                    before = b;
                    break;
                }
            }
            if (before != null) {
                troops = Math.round(before.troops() * 0.9 + troops * 0.1);
            }
            out.add(new Badge(x, y, owner, troops, (int) c[3]));
        }
        previous = out;
        return out;
    }

    /** 850, 8.5k, 120k, 1.2M. */
    static String format(long n) {
        if (n >= 1_000_000) return String.format(java.util.Locale.ROOT, "%.1fM", n / 1e6);
        if (n >= 10_000) return Math.round(n / 1e3) + "k";
        if (n >= 1_000) return String.format(java.util.Locale.ROOT, "%.1fk", n / 1e3);
        return Long.toString(n);
    }
}
