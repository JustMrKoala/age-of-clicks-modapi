package aoc.replay;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The map at one frame of a {@link Replay}, rebuilt from the stored changes, plus what the
 * camera and captions need: territory per country, front lines of each war, and the events
 * (wars, peace, alliances, cities) that happened since the frame before.
 */
final class ReplayState {
    /** Keep a full copy every this many frames, so seeking back stays quick. */
    private static final int KEYFRAME_EVERY = 64;

    /** Territory of one country in grid cells. */
    static final class Area {
        int cells;
        double sumX, sumY;
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, maxX = -1, maxY = -1;
        /** Where the name goes: middle of the largest connected piece, and that piece's size in cells. */
        float labelX, labelY;
        int mainCells;
        /** Bounds of that largest piece (the homeland), for framing. */
        int mainMinX, mainMinY, mainMaxX, mainMaxY;

        float cx() { return (float) (sumX / Math.max(1, cells)); }

        float cy() { return (float) (sumY / Math.max(1, cells)); }
    }

    /** Cells along the border between the two sides of a war. */
    static final class Front {
        final Replay.War war;
        final List<Integer> cells = new ArrayList<>();
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, maxX = -1, maxY = -1;
        double sumX, sumY;

        /** Land that changed hands between the two sides in this frame: where the fighting is. */
        int hot;
        double hotX, hotY;
        int hotMinX = Integer.MAX_VALUE, hotMinY = Integer.MAX_VALUE, hotMaxX = -1, hotMaxY = -1;

        Front(Replay.War war) { this.war = war; }

        float cx() { return (float) (sumX / Math.max(1, cells.size())); }

        float cy() { return (float) (sumY / Math.max(1, cells.size())); }

        void addHot(int x, int y) {
            hot++;
            hotX += x;
            hotY += y;
            if (x < hotMinX) hotMinX = x;
            if (y < hotMinY) hotMinY = y;
            if (x > hotMaxX) hotMaxX = x;
            if (y > hotMaxY) hotMaxY = y;
        }
    }

    enum EventType { WAR, PEACE, ALLIANCE, CITY, NATION }

    /** {@code a}/{@code b}: the two countries (for CITY: owner and city id); x/y: grid cells or -1. */
    record Event(EventType type, String text, int a, int b, float x, float y) {}

    final Replay replay;
    final int w, h;
    final int[] grid;
    private int frame = -1;
    private final Map<Integer, int[]> keyframes = new HashMap<>();

    final Map<Integer, Area> areas = new HashMap<>();
    final List<Front> fronts = new ArrayList<>();
    final List<Event> events = new ArrayList<>();
    final Map<Integer, Replay.Stats> stats = new HashMap<>();
    /** Cities known up to this frame (id to city). */
    final Map<Integer, Replay.City> cities = new HashMap<>();
    /** The player's country at the current frame. */
    int player = -1;

    ReplayState(Replay replay) {
        this.replay = replay;
        w = replay.gridW();
        h = replay.gridH();
        grid = new int[w * h];
        java.util.Arrays.fill(grid, -1);
    }

    int frame() { return frame; }

    int ownerAt(int gx, int gy) { return gx < 0 || gy < 0 || gx >= w || gy >= h ? -1 : grid[gy * w + gx]; }

    /** Moves the grid to {@code target} (forwards by applying changes, backwards from a keyframe). */
    void seek(int target) {
        target = Math.max(0, Math.min(replay.frames.size() - 1, target));
        if (target == frame) return;
        if (target < frame) {
            int key = (target / KEYFRAME_EVERY) * KEYFRAME_EVERY;
            while (key > 0 && !keyframes.containsKey(key)) key -= KEYFRAME_EVERY;
            if (key > 0) {
                System.arraycopy(keyframes.get(key), 0, grid, 0, grid.length);
                frame = key;
            } else {
                java.util.Arrays.fill(grid, -1);
                frame = -1;
            }
            cities.clear();
            for (int f = 0; f <= frame; f++) for (Replay.City c : replay.frames.get(f).newCities()) cities.put(c.id(), c);
        }
        while (frame < target) {
            frame++;
            Replay.Frame f = replay.frames.get(frame);
            int[] runs = f.runs();
            int pos = 0;
            for (int i = 0; i < runs.length; i += 3) {
                pos += runs[i];
                java.util.Arrays.fill(grid, pos, Math.min(grid.length, pos + runs[i + 1]), runs[i + 2]);
                pos += runs[i + 1];
            }
            for (Replay.City c : f.newCities()) cities.put(c.id(), c);
            if (frame % KEYFRAME_EVERY == 0 && frame > 0 && !keyframes.containsKey(frame)) keyframes.put(frame, grid.clone());
        }
        analyse();
    }

    /** Territory, fronts and events for the current frame. */
    private void analyse() {
        Replay.Frame f = replay.frames.get(frame);
        stats.clear();
        for (Replay.Stats s : f.stats()) stats.put(s.key(), s);

        areas.clear();
        fronts.clear();
        Map<Long, Front> byPair = new HashMap<>();
        for (Replay.War war : f.wars()) {
            Front front = new Front(war);
            fronts.add(front);
            byPair.put(pair(war.attacker(), war.defender()), front);
        }
        int lastOwner = Integer.MIN_VALUE;
        Area a = null;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                int o = grid[i];
                if (o == -1) continue;
                if (o != lastOwner) { // neighbouring cells mostly share an owner: skip the map lookup
                    a = areas.computeIfAbsent(o, k -> new Area());
                    lastOwner = o;
                }
                a.cells++;
                a.sumX += x;
                a.sumY += y;
                if (x < a.minX) a.minX = x;
                if (y < a.minY) a.minY = y;
                if (x > a.maxX) a.maxX = x;
                if (y > a.maxY) a.maxY = y;
                if (byPair.isEmpty()) continue;
                int right = x + 1 < w ? grid[i + 1] : -1;
                int down = y + 1 < h ? grid[i + w] : -1;
                Front front = null;
                if (right != -1 && right != o) front = byPair.get(pair(o, right));
                if (front == null && down != -1 && down != o) front = byPair.get(pair(o, down));
                if (front != null) {
                    front.cells.add(i);
                    front.sumX += x;
                    front.sumY += y;
                    if (x < front.minX) front.minX = x;
                    if (y < front.minY) front.minY = y;
                    if (x > front.maxX) front.maxX = x;
                    if (y > front.maxY) front.maxY = y;
                }
            }
        }

        // Captured land this frame, credited to the wars of its new owner.
        if (!fronts.isEmpty()) {
            Map<Integer, List<Front>> byOwner = new HashMap<>();
            for (Front fr : fronts) {
                byOwner.computeIfAbsent(fr.war.attacker(), k -> new ArrayList<>()).add(fr);
                byOwner.computeIfAbsent(fr.war.defender(), k -> new ArrayList<>()).add(fr);
            }
            int[] runs = f.runs();
            int pos = 0;
            for (int i = 0; i + 2 < runs.length && frame > 0; i += 3) {
                pos += runs[i];
                List<Front> ws = byOwner.get(runs[i + 2]);
                if (ws != null) {
                    for (int c = pos, end = Math.min(grid.length, pos + runs[i + 1]); c < end; c++) {
                        for (Front fr : ws) fr.addHot(c % w, c / w);
                    }
                }
                pos += runs[i + 1];
            }
        }
        placeLabels();
        player = f.player() != -1 ? f.player() : replay.playerKey;
        events.clear();
        if (frame == 0) return;
        Replay.Frame before = replay.frames.get(frame - 1);
        if (before.player() != -1 && f.player() != -1 && before.player() != f.player()) {
            events.add(new Event(EventType.NATION, replay.countryName(before.player()) + " becomes " + replay.countryName(f.player()),
                    f.player(), before.player(), -1, -1));
        }
        Set<Integer> oldWars = new HashSet<>();
        for (Replay.War war : before.wars()) oldWars.add(war.id());
        Set<Integer> nowWars = new HashSet<>();
        for (Replay.War war : f.wars()) {
            nowWars.add(war.id());
            if (!oldWars.contains(war.id())) {
                Area def = areas.get(war.defender());
                events.add(new Event(EventType.WAR, replay.countryName(war.attacker()) + " declares war on "
                        + replay.countryName(war.defender()), war.attacker(), war.defender(),
                        def == null ? -1 : def.cx(), def == null ? -1 : def.cy()));
            }
        }
        for (Replay.War war : before.wars()) {
            if (nowWars.contains(war.id())) continue;
            boolean defenderGone = !areas.containsKey(war.defender());
            boolean attackerGone = !areas.containsKey(war.attacker());
            String text = defenderGone ? replay.countryName(war.attacker()) + " conquers " + replay.countryName(war.defender())
                    : attackerGone ? replay.countryName(war.defender()) + " defeats " + replay.countryName(war.attacker())
                    : "Peace between " + replay.countryName(war.attacker()) + " and " + replay.countryName(war.defender());
            events.add(new Event(EventType.PEACE, text, war.attacker(), war.defender(), -1, -1));
        }
        Map<Integer, Integer> oldAlliance = new HashMap<>();
        for (Replay.Stats s : before.stats()) oldAlliance.put(s.key(), s.alliance());
        for (Replay.Stats s : f.stats()) {
            Integer was = oldAlliance.get(s.key());
            if (s.alliance() >= 0 && was != null && was != s.alliance()) {
                events.add(new Event(EventType.ALLIANCE, replay.countryName(s.key()) + " joins " + replay.allianceName(s.alliance()),
                        s.key(), -1, -1, -1));
            }
        }
        for (Replay.City c : f.newCities()) {
            events.add(new Event(EventType.CITY, "New city: " + c.name() + " (" + replay.countryName(c.owner()) + ")",
                    c.owner(), c.id(), c.x() / (float) replay.scale, c.y() / (float) replay.scale));
        }
    }

    /**
     * Finds each country's largest connected piece of land on a coarse copy of the grid, so a
     * name sits on the homeland rather than between it and its colonies.
     */
    private void placeLabels() {
        int ds = Math.max(1, Math.max(w, h) / 420);
        int cw = (w + ds - 1) / ds, ch = (h + ds - 1) / ds;
        int[] coarse = new int[cw * ch];
        for (int y = 0; y < ch; y++) for (int x = 0; x < cw; x++) coarse[y * cw + x] = grid[Math.min(h - 1, y * ds) * w + Math.min(w - 1, x * ds)];
        // A capital marks the homeland, even when a colony is bigger.
        Map<Integer, Integer> capitals = new HashMap<>();
        for (Replay.City c : cities.values()) {
            if (!c.capital()) continue;
            int gx = Math.min(w - 1, Math.max(0, c.x() / replay.scale)), gy = Math.min(h - 1, Math.max(0, c.y() / replay.scale));
            int owner = grid[gy * w + gx];
            if (owner != -1) capitals.put(Math.min(ch - 1, gy / ds) * cw + Math.min(cw - 1, gx / ds), owner);
        }
        Map<Integer, Integer> bestScore = new HashMap<>();
        boolean[] seen = new boolean[coarse.length];
        int[] queue = new int[coarse.length];
        for (int start = 0; start < coarse.length; start++) {
            int o = coarse[start];
            if (o == -1 || seen[start]) continue;
            int head = 0, tail = 0;
            queue[tail++] = start;
            seen[start] = true;
            long sx = 0, sy = 0;
            boolean capital = false;
            while (head < tail) {
                int c = queue[head++];
                int x = c % cw, y = c / cw;
                sx += x;
                sy += y;
                Integer cap = capitals.get(c);
                if (cap != null && cap == o) capital = true;
                if (x > 0 && !seen[c - 1] && coarse[c - 1] == o) { seen[c - 1] = true; queue[tail++] = c - 1; }
                if (x + 1 < cw && !seen[c + 1] && coarse[c + 1] == o) { seen[c + 1] = true; queue[tail++] = c + 1; }
                if (y > 0 && !seen[c - cw] && coarse[c - cw] == o) { seen[c - cw] = true; queue[tail++] = c - cw; }
                if (y + 1 < ch && !seen[c + cw] && coarse[c + cw] == o) { seen[c + cw] = true; queue[tail++] = c + cw; }
            }
            Area a = areas.get(o);
            int size = tail * ds * ds;
            int score = capital ? size * 8 : size;
            if (a == null || score <= bestScore.getOrDefault(o, 0)) continue;
            bestScore.put(o, score);
            a.mainCells = size;
            // Middle of the piece; if that falls outside it (a crescent), use its cell nearest the middle.
            float mx = (float) sx / tail, my = (float) sy / tail;
            int best = queue[0];
            float bestD = Float.MAX_VALUE;
            int bx0 = Integer.MAX_VALUE, by0 = Integer.MAX_VALUE, bx1 = -1, by1 = -1;
            for (int i = 0; i < tail; i++) {
                int c = queue[i];
                int cx = c % cw, cy = c / cw;
                bx0 = Math.min(bx0, cx);
                by0 = Math.min(by0, cy);
                bx1 = Math.max(bx1, cx);
                by1 = Math.max(by1, cy);
                float dx = cx - mx, dy = cy - my;
                float d = dx * dx + dy * dy;
                if (d < bestD) {
                    bestD = d;
                    best = c;
                }
            }
            a.labelX = ((best % cw) + 0.5f) * ds;
            a.labelY = ((best / cw) + 0.5f) * ds;
            a.mainMinX = bx0 * ds;
            a.mainMinY = by0 * ds;
            a.mainMaxX = Math.min(w - 1, (bx1 + 1) * ds);
            a.mainMaxY = Math.min(h - 1, (by1 + 1) * ds);
        }
    }

    /** Countries in the same alliance as {@code key} (including itself). */
    Set<Integer> allianceOf(int key) {
        Set<Integer> out = new HashSet<>();
        out.add(key);
        Replay.Stats mine = stats.get(key);
        if (mine == null || mine.alliance() < 0) return out;
        for (Replay.Stats s : stats.values()) if (s.alliance() == mine.alliance()) out.add(s.key());
        return out;
    }

    private static long pair(int a, int b) {
        int lo = Math.min(a, b), hi = Math.max(a, b);
        return ((long) lo << 32) ^ (hi & 0xffffffffL);
    }
}
