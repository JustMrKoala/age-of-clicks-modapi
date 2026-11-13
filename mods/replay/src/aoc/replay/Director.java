package aoc.replay;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * The replay's camera operator. It rests on the focus country's homeland; when the focus country
 * is at war it stays on the fighting like a battle camera: one steady zoom close enough for the
 * army flags, drifting with the land that changes hands rather than hopping after it, and only
 * re-framing when the fighting clearly outgrows or shrinks from the view. Changes of place are cuts,
 * not flights (pulling out and back in made the flags fade in and out and the battle hard to follow).
 * At peace it looks in on big wars elsewhere now and then. Coordinates are grid cells;
 * {@link #height} is how many cells fit on screen vertically.
 */
final class Director {
    enum Shot { HOME, WAR, MEANWHILE, INTERLUDE }

    private static final float MIN_SHOT = 5f, MEANWHILE_EVERY = 40f, MEANWHILE_LENGTH = 9f;
    private static final float INTERLUDE_LENGTH = 6f, HOT_MEMORY = 6f;
    /** A different war of our own gets the camera only after this long on the current one. */
    private static final float WAR_SWITCH = 20f;
    /** Battle views: from this share of the map's height (close enough for flags) to this one. */
    private static final float BATTLE_MIN = 0.05f, BATTLE_MAX = 0.16f;

    private final int mapW, mapH;
    float x, y, height;
    private float tx, ty, th;
    Shot shot = Shot.HOME;
    private float shotTime, sinceMeanwhile, clock;
    /** The war being shown (WAR or MEANWHILE), or -1. */
    int warId = -1;
    /** Caption for the current cut-away, shown by the player; null when none. */
    String cutCaption;
    /** Where each war last saw land change hands: x, y, size (cells), age (s). */
    private final Map<Integer, float[]> hotspots = new HashMap<>();

    /** The battle being followed: its war, the drifting centre and the held view height. */
    private int battleWar = -1;
    private float bx, by, bh, outgrown, shrunk, sinceJump;
    /** Jump to the target next update instead of moving there. */
    private boolean snapNext;
    /** Cuts made so far (the self-test counts them). */
    int cuts;

    Director(int mapW, int mapH) {
        this.mapW = mapW;
        this.mapH = mapH;
        x = mapW / 2f;
        y = mapH / 2f;
        height = mapH;
    }

    /** Cuts to the focus country's homeland for a moment (e.g. it just became a new nation). */
    void showHome() { cut(Shot.INTERLUDE, -1, null); }

    /** Jumps straight to the target of the current state (start of a replay or after a seek). */
    void snap(ReplayState st, int focus, float aspect) {
        hotspots.clear();
        battleWar = -1;
        remember(st, 0f);
        choose(st, focus, aspect, true, 0f);
        x = tx;
        y = ty;
        height = th;
        snapNext = false;
    }

    /** {@code urgent}: a new war involving the focus country, cut to it now. */
    void update(float dt, ReplayState st, int focus, float aspect, boolean urgent) {
        clock += dt;
        shotTime += dt;
        sinceMeanwhile += dt;
        sinceJump += dt;
        remember(st, dt);
        choose(st, focus, aspect, urgent, dt);

        // A new place or a very different framing is a cut; small changes are a slow, steady move.
        float dist = (float) Math.hypot(tx - x, ty - y);
        if (snapNext || dist > height * 0.9f || th > height * 2.2f || th < height / 2.2f) {
            x = tx;
            y = ty;
            height = th;
            snapNext = false;
            cuts++;
        } else {
            float k = 1f - (float) Math.exp(-dt / 1.8f);
            float kz = 1f - (float) Math.exp(-dt / 3.5f);
            x += (tx - x) * k;
            y += (ty - y) * k;
            height = (float) Math.exp(Math.log(height) + (Math.log(th) - Math.log(height)) * kz);
        }
        // Never show past the map's edges.
        height = Math.min(height, Math.min(mapH, mapW / aspect));
        float halfW = height * aspect / 2f, halfH = height / 2f;
        x = clamp(x, halfW, mapW - halfW);
        y = clamp(y, halfH, mapH - halfH);
    }

    private void remember(ReplayState st, float dt) {
        for (float[] h : hotspots.values()) h[3] += dt;
        for (ReplayState.Front f : st.fronts) {
            if (f.hot < 3) continue;
            float size = Math.max(f.hotMaxX - f.hotMinX, f.hotMaxY - f.hotMinY) + 1;
            hotspots.put(f.war.id(), new float[]{(float) (f.hotX / f.hot), (float) (f.hotY / f.hot), size, 0f});
        }
        hotspots.values().removeIf(h -> h[3] > 60f);
    }

    private void choose(ReplayState st, int focus, float aspect, boolean urgent, float dt) {
        Set<Integer> side = st.allianceOf(focus);
        ReplayState.Front own = null, other = null;
        int ownRank = -1;
        for (ReplayState.Front f : st.fronts) {
            if (f.cells.isEmpty()) continue;
            boolean mine = f.war.attacker() == focus || f.war.defender() == focus;
            boolean ally = !mine && (side.contains(f.war.attacker()) || side.contains(f.war.defender()));
            if (mine || ally) {
                // Our own wars before allies'; the one on screen before others; then the liveliest.
                float[] hs = hotspots.get(f.war.id());
                int rank = (mine ? 4 : 0) + (f.war.id() == warId ? 2 : 0) + (hs != null && hs[3] < HOT_MEMORY ? 1 : 0);
                if (rank > ownRank || (rank == ownRank && f.cells.size() > own.cells.size())) {
                    own = f;
                    ownRank = rank;
                }
            } else if (other == null || f.hot > other.hot || (f.hot == other.hot && f.cells.size() > other.cells.size())) {
                other = f;
            }
        }

        if (urgent && own != null) {
            cut(Shot.WAR, own.war.id(), null);
            frameWar(st, own, aspect, dt);
            return;
        }
        if (shot == Shot.INTERLUDE && shotTime < INTERLUDE_LENGTH) {
            frameHome(st, focus, aspect);
            return;
        }
        if (own != null) {
            // Stay with the battle on screen; another war of ours only after a good while on this one.
            ReplayState.Front current = shot == Shot.WAR ? find(st, warId) : null;
            if (current != null && !current.cells.isEmpty() && current != own && shotTime < WAR_SWITCH) own = current;
            if (shot != Shot.WAR || warId != own.war.id()) cut(Shot.WAR, own.war.id(), null);
            frameWar(st, own, aspect, dt);
            return;
        }
        // At peace: a look at big wars elsewhere now and then.
        if (shot == Shot.MEANWHILE && shotTime < MEANWHILE_LENGTH) {
            ReplayState.Front f = find(st, warId);
            if (f != null && !f.cells.isEmpty()) {
                frameWar(st, f, aspect, dt);
                return;
            }
        }
        if (other != null && other.cells.size() > 12 && sinceMeanwhile > MEANWHILE_EVERY && shotTime >= MIN_SHOT) {
            cut(Shot.MEANWHILE, other.war.id(), "Meanwhile: " + st.replay.countryName(other.war.attacker())
                    + " against " + st.replay.countryName(other.war.defender()));
            sinceMeanwhile = 0;
            frameWar(st, other, aspect, dt);
            return;
        }
        if (shot != Shot.HOME) cut(Shot.HOME, -1, null);
        frameHome(st, focus, aspect);
    }

    /**
     * Follows a war's fighting at one steady zoom: the view centre drifts towards where land changes
     * hands (else the middle of the front), the zoom only changes when the fighting clearly outgrows
     * the view (for a few seconds) or shrinks well inside it (for longer), and a battle that flares up
     * far away from the view gets a cut.
     */
    private void frameWar(ReplayState st, ReplayState.Front f, float aspect, float dt) {
        float[] hs = hotspots.get(f.war.id());
        boolean hot = hs != null && hs[3] < HOT_MEMORY;
        float cx, cy, extent;
        if (hot) {
            cx = hs[0];
            cy = hs[1];
            extent = hs[2] * 3f;
        } else {
            float span = Math.max(f.maxX - f.minX, f.maxY - f.minY) + 1;
            cx = f.cx();
            cy = f.cy();
            extent = span * 0.6f;
        }
        float wanted = clamp(extent, mapH * BATTLE_MIN, mapH * BATTLE_MAX);
        if (battleWar != f.war.id()) {
            battleWar = f.war.id();
            bx = cx;
            by = cy;
            bh = wanted;
            outgrown = shrunk = 0;
            sinceJump = 0;
            snapNext = true;
        } else {
            boolean fresh = hot && hs[3] < 1f;
            if (fresh && Math.hypot(cx - bx, cy - by) > bh * 1.1f && sinceJump > 10f) {
                // The fighting flared up out of view: cut there.
                bx = cx;
                by = cy;
                sinceJump = 0;
                snapNext = true;
            } else {
                float k = 1f - (float) Math.exp(-dt / 5f);
                bx += (cx - bx) * k;
                by += (cy - by) * k;
            }
            outgrown = wanted > bh * 1.5f ? outgrown + dt : 0;
            shrunk = wanted < bh / 1.8f ? shrunk + dt : 0;
            if (outgrown > 3f || shrunk > 12f) {
                bh = wanted;
                outgrown = shrunk = 0;
            }
        }
        frameAround(bx, by, bh, aspect);
    }

    private void frameHome(ReplayState st, int focus, float aspect) {
        battleWar = -1;
        ReplayState.Area home = st.areas.get(focus);
        if (home == null) home = largest(st);
        if (home == null) return;
        frame(home.mainMinX, home.mainMinY, home.mainMaxX, home.mainMaxY, home.labelX, home.labelY, aspect, 3f);
    }

    private void cut(Shot s, int war, String caption) {
        shot = s;
        warId = war;
        shotTime = 0;
        cutCaption = caption;
    }

    /** Targets a box (with padding), centred between its middle and its weight centre. */
    private void frame(int minX, int minY, int maxX, int maxY, float cx, float cy, float aspect, float pad) {
        float bw = Math.max(1, maxX - minX + 1), bh = Math.max(1, maxY - minY + 1);
        frameAround(((minX + maxX) / 2f + cx) / 2f, ((minY + maxY) / 2f + cy) / 2f, Math.max(bh, bw / aspect) * pad, aspect);
    }

    private void frameAround(float cx, float cy, float h, float aspect) {
        th = Math.max(mapH * 0.045f, Math.min(mapH * 1.05f, h));
        float halfW = th * aspect / 2f, halfH = th / 2f;
        tx = clamp(cx, Math.min(halfW, mapW / 2f), Math.max(mapW - halfW, mapW / 2f));
        ty = clamp(cy, Math.min(halfH, mapH / 2f), Math.max(mapH - halfH, mapH / 2f));
    }

    private static ReplayState.Front find(ReplayState st, int id) {
        for (ReplayState.Front f : st.fronts) if (f.war.id() == id) return f;
        return null;
    }

    static ReplayState.Area largest(ReplayState st) {
        ReplayState.Area best = null;
        for (ReplayState.Area a : st.areas.values()) if (best == null || a.cells > best.cells) best = a;
        return best;
    }

    private static float clamp(float v, float lo, float hi) { return v < lo ? lo : (v > hi ? hi : v); }
}
