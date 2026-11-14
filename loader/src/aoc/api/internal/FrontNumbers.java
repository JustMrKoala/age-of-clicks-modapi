package aoc.api.internal;

import aoc.api.ui.Theme;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.utils.FloatArray;
import ru.ius.david.cgs.NativeFloatGrid;
import ru.ius.david.cgs.NativeIntGrid;
import ru.ius.david.cgs.WarManager;
import ru.ius.david.cgs.game.CountryState;
import ru.ius.david.cgs.game.GameManager;
import ru.ius.david.cgs.units.FrontlineDecor;
import ru.ius.david.cgs.units.MilitaryUnit;
import ru.ius.david.cgs.units.UnitManager;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Game Improvements: army numbers along the fronts. For every pair of countries at war with a
 * front on screen, each side's army on that front is written on its own land next to the front,
 * turned along it.
 *
 * <p>Everything about the place comes from the map itself: the visible part of the map is sampled
 * for who holds the land (the occupier where the game tracks one, else the owner), a front is where
 * land of two countries at war touches, and each side's land around it says which way the front
 * runs and where its number belongs. That works in both combat modes: the game's frontline flags
 * (classic combat) are left out wherever divisions hold the line, so they cannot be relied on.
 *
 * <p>The number is the country's army (infantry + artillery + tanks) times this front's share of
 * its forces: its frontline flags facing that enemy plus its divisions sent against that enemy.
 */
final class FrontNumbers {
    /** The visible front between two countries (a before b by colour key). */
    private static final class Front {
        final int a, b;
        /** Samples where the two lands touch, as screen x, y pairs. */
        final FloatArray points = new FloatArray();

        Front(int a, int b) {
            this.a = a;
            this.b = b;
        }
    }

    /** A number as it was drawn last frame; positions, angles and numbers ease from it. */
    private static final class Shown {
        float x, y, angle, troops, alpha;
        /** Map position, so the number stays on its land while the camera moves. */
        float mapX, mapY;
        boolean seen;
    }

    private final Map<Long, Shown> shown = new HashMap<>();
    private final GlyphLayout layout = new GlyphLayout();
    private final Matrix4 projection = new Matrix4(), transform = new Matrix4(), identity = new Matrix4();
    private final Color ink = new Color();
    private final float[] point = new float[2], other = new float[2];
    private final Map<Long, Boolean> atWar = new HashMap<>();
    /** Who holds each sample of the visible map this frame (-1 sea or outside). */
    private int[] holders = new int[0];
    private int cols, rows;
    private float step, top, originX, originY, pxPerMap;
    private SpriteBatch batch;
    /** Self-test: the numbers of the last frame, as text. */
    static String debug = "";

    void render(float delta) {
        GameHud.Layout hud = GameHud.layout();
        GameManager gm = GameManager.getInstance();
        WarManager wm = gm == null ? null : gm.getWarManager();
        NativeIntGrid owners = wm == null ? null : wm.getOwnershipGrid();
        if (!hud.inGame() || owners == null || wm.getActiveWars() == null || wm.getActiveWars().isEmpty()) {
            shown.clear();
            if (UiSelfTest.active) debug = "no map or no wars";
            return;
        }
        // A pop-up or hidden interface: nothing is drawn, but the numbers keep their place for afterwards.
        if (hud.hidden() || hud.modal()) {
            if (UiSelfTest.active) debug = hud.modal() ? "a dialog or full-screen menu is open" : "interface hidden";
            return;
        }
        float sw = Gdx.graphics.getWidth(), sh = Gdx.graphics.getHeight();
        // Screen pixels per map pixel under the player's camera (no rotation, same scale both ways).
        if (!Hooks.mapToScreen(0, 0, point) || !Hooks.mapToScreen(1000, 0, other)) return;
        pxPerMap = Math.abs(other[0] - point[0]) / 1000f;
        if (pxPerMap <= 0) return;
        originX = point[0];
        originY = point[1];
        top = hud.top();

        sample(wm, owners, sw, sh, hud.bottom());
        Map<Long, Front> fronts = findFronts(wm);
        Map<Long, Integer> forces = new HashMap<>(); // owner + enemy -> flags and divisions facing that enemy
        Map<Integer, Integer> forcesOf = new HashMap<>();
        countForces(wm, forces, forcesOf);

        for (Shown s : shown.values()) s.seen = false;
        StringBuilder log = UiSelfTest.active ? new StringBuilder("fronts on screen " + fronts.size() + ": ") : null;
        float ease = Math.min(1f, delta * 4f);
        for (Front f : fronts.values()) {
            // One stretch of front: the touching points around the one nearest the middle of the screen.
            float seedX = f.points.get(0), seedY = f.points.get(1);
            for (int i = 0; i < f.points.size; i += 2) {
                if (dist2(f.points.get(i), f.points.get(i + 1), sw / 2, sh / 2) < dist2(seedX, seedY, sw / 2, sh / 2)) {
                    seedX = f.points.get(i);
                    seedY = f.points.get(i + 1);
                }
            }
            float reach = sh * 0.3f, fx = 0, fy = 0;
            int n = 0;
            for (int i = 0; i < f.points.size; i += 2) {
                if (dist2(f.points.get(i), f.points.get(i + 1), seedX, seedY) > reach * reach) continue;
                fx += f.points.get(i);
                fy += f.points.get(i + 1);
                n++;
            }
            fx /= n;
            fy /= n;

            // Whose land lies where around that stretch (a wide look, so a bulge does not turn the numbers).
            float radius = sh * 0.30f;
            float ax = 0, ay = 0, bx = 0, by = 0;
            int na = 0, nb = 0;
            for (int r = 0; r < rows; r++) {
                for (int c = 0; c < cols; c++) {
                    int h = holders[r * cols + c];
                    if (h != f.a && h != f.b) continue;
                    float x = c * step, y = top + r * step;
                    if (dist2(x, y, fx, fy) > radius * radius) continue;
                    if (h == f.a) {
                        ax += x;
                        ay += y;
                        na++;
                    } else {
                        bx += x;
                        by += y;
                        nb++;
                    }
                }
            }
            if (na == 0 || nb == 0) continue;
            ax /= na;
            ay /= na;
            bx /= nb;
            by /= nb;
            float dx = bx - ax, dy = by - ay;
            float len = (float) Math.sqrt(dx * dx + dy * dy);
            if (len < 0.5f) {
                dx = 1;
                dy = 0;
                len = 1;
            }
            dx /= len;
            dy /= len;
            // Both numbers at the same distance from the front, never on top of each other.
            float apart = Math.max(len / 2, sh * 0.11f);
            float angle = (float) Math.toDegrees(Math.atan2(dx, -dy));
            if (angle > 90) angle -= 180;
            if (angle < -90) angle += 180;
            place(wm, f.a, f.b, forces, forcesOf, fx, fy, -dx, -dy, apart, angle, ease, delta, log);
            place(wm, f.b, f.a, forces, forcesOf, fx, fy, dx, dy, apart, angle, ease, delta, log);
        }
        if (log != null) debug = log.toString();
        shown.values().removeIf(s -> {
            s.alpha += ((s.seen ? 1f : 0f) - s.alpha) * Math.min(1f, delta * 6f);
            return !s.seen && s.alpha < 0.03f;
        });
        if (!shown.isEmpty()) draw(hud, sw, sh);
    }

    /** Reads who holds the map under a grid of screen points between the top bar and the dock. */
    private void sample(WarManager wm, NativeIntGrid owners, float sw, float sh, float bottom) {
        step = Math.max(8f, sh / 36f);
        cols = (int) (sw / step) + 1;
        rows = (int) ((bottom - top) / step) + 1;
        if (holders.length < cols * rows) holders = new int[cols * rows];
        NativeIntGrid control = wm.getControlOwner();
        NativeFloatGrid strength = wm.getControlGrid();
        int w = owners.getWidth(), h = owners.getHeight();
        for (int r = 0; r < rows; r++) {
            int my = Math.round((top + r * step - originY) / pxPerMap);
            for (int c = 0; c < cols; c++) {
                int mx = Math.round((c * step - originX) / pxPerMap);
                int holder = -1;
                if (mx >= 0 && my >= 0 && mx < w && my < h) {
                    holder = owners.at(mx, my);
                    // Occupied land counts for the occupier (0.45 is the game's own threshold for "held").
                    if (holder != -1 && control != null && strength != null) {
                        int occupier = control.at(mx, my);
                        if (occupier != -1 && occupier != holder && strength.at(mx, my) >= 0.45f) holder = occupier;
                    }
                }
                holders[r * cols + c] = holder;
            }
        }
    }

    /** Fronts: where neighbouring samples are held by two countries at war. */
    private Map<Long, Front> findFronts(WarManager wm) {
        atWar.clear();
        Map<Long, Front> fronts = new HashMap<>();
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                int h = holders[r * cols + c];
                if (h == -1) continue;
                for (int side = 0; side < 2; side++) {
                    int c2 = side == 0 ? c + 1 : c, r2 = side == 0 ? r : r + 1;
                    if (c2 >= cols || r2 >= rows) continue;
                    int o = holders[r2 * cols + c2];
                    if (o == -1 || o == h) continue;
                    int a = Math.min(h, o), b = Math.max(h, o);
                    long k = key(a, b);
                    if (!atWar.computeIfAbsent(k, x -> wm.areAtWar(a, b))) continue;
                    Front f = fronts.computeIfAbsent(k, x -> new Front(a, b));
                    f.points.add((c + c2) * step / 2f, top + (r + r2) * step / 2f);
                }
            }
        }
        return fronts;
    }

    /** How many frontline flags and divisions each country has against each enemy, and in all. */
    private static void countForces(WarManager wm, Map<Long, Integer> forces, Map<Integer, Integer> forcesOf) {
        FrontlineDecor decor = wm.getFrontlineDecor();
        if (decor != null) {
            for (FrontlineDecor.Marker m : decor.getMarkers()) {
                if (!m.alive || m.enemyColorKey == m.ownerColorKey || !wm.areAtWar(m.ownerColorKey, m.enemyColorKey)) continue;
                forces.merge(key(m.ownerColorKey, m.enemyColorKey), 1, Integer::sum);
                forcesOf.merge(m.ownerColorKey, 1, Integer::sum);
            }
        }
        UnitManager units = wm.getUnitManager();
        if (units == null || units.isEmpty()) return;
        List<MilitaryUnit> list = units.getUnits();
        for (int i = 0; i < list.size(); i++) {
            MilitaryUnit u = list.get(i);
            if (u == null || u.state == MilitaryUnit.STATE_DEAD) continue;
            if (u.targetEnemyColor == -1 || u.targetEnemyColor == u.ownerColorKey || !wm.areAtWar(u.ownerColorKey, u.targetEnemyColor)) continue;
            forces.merge(key(u.ownerColorKey, u.targetEnemyColor), 1, Integer::sum);
            forcesOf.merge(u.ownerColorKey, 1, Integer::sum);
        }
    }

    /** One side's number: on its own land, {@code apart} screen pixels from the front along {@code dx, dy}. */
    private void place(WarManager wm, int owner, int enemy, Map<Long, Integer> forces, Map<Integer, Integer> forcesOf,
                       float fx, float fy, float dx, float dy, float apart, float angle, float ease, float delta, StringBuilder log) {
        CountryState state = wm.stateOf(owner);
        if (state == null) return;
        long army = Math.max(0, state.getInfantry()) + Math.max(0, state.getArtillery()) + Math.max(0, state.getTanks());
        int here = forces.getOrDefault(key(owner, enemy), 0), all = forcesOf.getOrDefault(owner, 0);
        // Nothing of its own sent to this front: count it as one front among the others.
        float share = here > 0 ? here / (float) all : 1f / (1 + all);
        float troops = army * share;
        if (troops < 1) return;
        // The first spot that really is this side's land and clear of the game's interface: straight
        // back from the front first, then further along the front to either side.
        GameHud.Layout hud = GameHud.layout();
        float sh = Gdx.graphics.getHeight(), sw = Gdx.graphics.getWidth(), room = sh * 0.06f;
        float x = fx + dx * apart, y = fy + dy * apart;
        boolean own = false, free = false;
        search:
        for (float along : new float[]{0f, 0.15f, -0.15f, 0.3f, -0.3f}) {
            for (float t : new float[]{1f, 1.4f, 0.7f, 1.9f, 0.45f}) {
                float tx = fx + dx * apart * t - dy * along * sh, ty = fy + dy * apart * t + dx * along * sh;
                int c = Math.round(tx / step), r = Math.round((ty - top) / step);
                if (c < 0 || r < 0 || c >= cols || r >= rows || holders[r * cols + c] != owner) continue;
                boolean clear = tx > room && tx < sw - room && ty > hud.top() + room && ty < hud.bottom() - room
                        && covered(hud, tx - room, ty - room, 2 * room) == null;
                if (!own || clear) {
                    x = tx;
                    y = ty;
                    own = true;
                }
                if (clear) {
                    free = true;
                    break search;
                }
            }
        }
        if (log != null) log.append(state.getName()).append(": army ").append(army).append(", forces ").append(here).append('/').append(all)
                .append(" -> ").append(Math.round(troops)).append(" at screen ").append(Math.round(x)).append(',').append(Math.round(y))
                .append(own ? " (own land)" : " (NOT own land)").append(free ? "" : " (no free spot)").append(" angle ").append(Math.round(angle)).append("; ");
        float mapX = (x - originX) / pxPerMap, mapY = (y - originY) / pxPerMap;
        Shown s = shown.get(key(owner, enemy));
        if (s == null) {
            s = new Shown();
            s.mapX = mapX;
            s.mapY = mapY;
            s.angle = angle;
            s.troops = troops;
            shown.put(key(owner, enemy), s);
        } else {
            s.mapX += (mapX - s.mapX) * ease;
            s.mapY += (mapY - s.mapY) * ease;
            float turn = angle - s.angle;
            if (turn > 90) turn -= 180;
            if (turn < -90) turn += 180;
            s.angle += turn * ease;
            if (s.angle > 90) s.angle -= 180;
            if (s.angle < -90) s.angle += 180;
            s.troops += (troops - s.troops) * Math.min(1f, delta * 2f);
        }
        s.x = originX + s.mapX * pxPerMap;
        s.y = originY + s.mapY * pxPerMap;
        s.seen = true;
    }

    private void draw(GameHud.Layout hud, float sw, float sh) {
        if (batch == null) batch = new SpriteBatch();
        BitmapFont font = Theme.font(Theme.Font.LARGE);
        float oldX = font.getData().scaleX, oldY = font.getData().scaleY;
        // About 2.6% of the screen height, whatever the game's UI scale.
        float scale = sh * 0.026f / Math.max(1f, Math.abs(font.getCapHeight() / oldY));
        font.getData().setScale(scale * Math.signum(oldX), scale * Math.signum(oldY));
        projection.setToOrtho2D(0, 0, sw, sh);
        projection.val[Matrix4.M11] = -2f / sh;
        projection.val[Matrix4.M13] = 1f;
        Gdx.gl.glViewport(0, 0, Gdx.graphics.getBackBufferWidth(), Gdx.graphics.getBackBufferHeight());
        Gdx.gl.glEnable(GL20.GL_BLEND);
        // The map only: never over the game's top bar or dock.
        float py = Gdx.graphics.getBackBufferHeight() / sh;
        Gdx.gl.glEnable(GL20.GL_SCISSOR_TEST);
        Gdx.gl.glScissor(0, Math.round((sh - hud.bottom()) * py), Gdx.graphics.getBackBufferWidth(), Math.round((hud.bottom() - hud.top()) * py));
        batch.setProjectionMatrix(projection);
        batch.begin();
        try {
            float o = Math.max(1f, sh / 540f);
            for (Shown s : shown.values()) {
                String text = String.format(java.util.Locale.GERMANY, "%,d", Math.round((double) s.troops));
                layout.setText(font, text);
                float w = layout.width, h = Math.abs(layout.height), reach = Math.max(w, h) / 2f;
                String by = covered(hud, s.x - reach, s.y - reach, 2 * reach);
                if (UiSelfTest.active) debug += "[draw " + text + " at " + Math.round(s.x) + "," + Math.round(s.y) + " alpha " + s.alpha + (by == null ? "" : " HIDDEN by " + by) + "] ";
                if (s.x < -reach || s.x > sw + reach || by != null) continue;
                transform.idt().translate(s.x, s.y, 0).rotate(0, 0, 1, s.angle);
                batch.setTransformMatrix(transform);
                float x = -w / 2f, y = -h / 2f;
                ink.set(0f, 0f, 0f, 0.55f * s.alpha);
                font.setColor(ink);
                for (int i = 0; i < 8; i++) {
                    double t = i * Math.PI / 4;
                    font.draw(batch, text, x + (float) Math.cos(t) * o, y + (float) Math.sin(t) * o);
                }
                ink.set(1f, 1f, 1f, s.alpha);
                font.setColor(ink);
                font.draw(batch, text, x, y);
            }
        } finally {
            batch.setTransformMatrix(identity);
            batch.end();
            Gdx.gl.glDisable(GL20.GL_SCISSOR_TEST);
            font.getData().setScale(oldX, oldY);
            font.setColor(Color.WHITE);
        }
    }

    /** What of the game's interface (panels, HUD buttons, notices) the square touches, or null. */
    private static String covered(GameHud.Layout hud, float x, float y, float size) {
        for (GameHud.Block b : hud.blocks()) {
            if (b.what().equals("top bar") || b.what().equals("dock")) continue;
            if (b.hits(x, y, size, size, 0)) return b.what();
        }
        return null;
    }

    private static float dist2(float x1, float y1, float x2, float y2) { return (x1 - x2) * (x1 - x2) + (y1 - y2) * (y1 - y2); }

    private static long key(int owner, int enemy) { return ((long) owner << 32) | (enemy & 0xffffffffL); }
}
