package aoc.api.internal;

import aoc.api.ui.Theme;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.math.Matrix4;
import ru.ius.david.cgs.NativeIntGrid;
import ru.ius.david.cgs.WarManager;
import ru.ius.david.cgs.game.CountryState;
import ru.ius.david.cgs.game.GameManager;
import ru.ius.david.cgs.units.FrontlineDecor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Game Improvements: army numbers along the fronts. For every pair of countries at war with a
 * front on screen, each side's army on that front is written on its own land next to the front,
 * turned along it. In classic combat the game shows army flags but no numbers; a country's army
 * (infantry + artillery + tanks) is split over its fronts by how many of its flags stand on each.
 *
 * <p>Where the front is comes from the flags; which way it runs and where each side's land lies
 * comes from the map itself (who holds the land around the front), because both sides' flags stand
 * on the same line and say little about its direction.
 */
final class FrontNumbers {
    /** The front between two countries (a before b by colour key). */
    private static final class Front {
        final int a, b;
        /** On-screen flags of both sides, in map pixels. */
        final List<float[]> flags = new ArrayList<>();
        int flagsA, flagsB;

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
    /** Sums over the land of side a and side b around a front: x, y, cells. */
    private final float[] landA = new float[3], landB = new float[3];
    private SpriteBatch batch;
    /** Self-test: the numbers of the last frame, as text. */
    static String debug = "";

    void render(float delta) {
        GameHud.Layout hud = GameHud.layout();
        GameManager gm = GameManager.getInstance();
        WarManager wm = gm == null ? null : gm.getWarManager();
        FrontlineDecor decor = wm == null ? null : wm.getFrontlineDecor();
        NativeIntGrid owners = wm == null ? null : wm.getOwnershipGrid();
        if (!hud.inGame() || decor == null || decor.isEmpty() || owners == null || !gm.isClassicCombat()) {
            shown.clear();
            return;
        }
        // A pop-up or hidden interface: nothing is drawn, but the numbers keep their place for afterwards.
        if (hud.hidden() || hud.modal()) return;
        NativeIntGrid control = wm.getControlOwner();
        float sw = Gdx.graphics.getWidth(), sh = Gdx.graphics.getHeight();
        // Screen pixels per map pixel under the player's camera (no rotation, same scale both ways).
        if (!Hooks.mapToScreen(0, 0, point) || !Hooks.mapToScreen(1000, 0, other)) return;
        float pxPerMap = Math.abs(other[0] - point[0]) / 1000f;
        if (pxPerMap <= 0) return;

        Map<Long, Front> fronts = new HashMap<>();
        Map<Integer, Integer> flagsOf = new HashMap<>();
        for (FrontlineDecor.Marker m : decor.getMarkers()) {
            // Thinned-out flags (zoomed out) still mark the front; only dead ones do not.
            if (!m.alive || m.enemyColorKey == m.ownerColorKey || !wm.areAtWar(m.ownerColorKey, m.enemyColorKey)) continue;
            flagsOf.merge(m.ownerColorKey, 1, Integer::sum);
            int a = Math.min(m.ownerColorKey, m.enemyColorKey), b = Math.max(m.ownerColorKey, m.enemyColorKey);
            Front f = fronts.computeIfAbsent(key(a, b), k -> new Front(a, b));
            if (m.ownerColorKey == a) f.flagsA++;
            else f.flagsB++;
            if (!Hooks.mapToScreen(m.x, m.y, point)) continue;
            if (point[0] < 0 || point[0] > sw || point[1] < hud.top() || point[1] > hud.bottom()) continue;
            f.flags.add(new float[]{m.x, m.y, point[0], point[1]});
        }

        for (Shown s : shown.values()) s.seen = false;
        StringBuilder log = UiSelfTest.active ? new StringBuilder() : null;
        float ease = Math.min(1f, delta * 4f);
        for (Front f : fronts.values()) {
            if (f.flags.isEmpty()) continue;
            // One stretch of front: the flags around the one nearest the middle of the screen.
            float[] seed = f.flags.get(0);
            for (float[] p : f.flags) {
                if (dist2(p[2], p[3], sw / 2, sh / 2) < dist2(seed[2], seed[3], sw / 2, sh / 2)) seed = p;
            }
            float reach = sh * 0.3f, fx = 0, fy = 0;
            int n = 0;
            for (float[] p : f.flags) {
                if (dist2(p[2], p[3], seed[2], seed[3]) > reach * reach) continue;
                fx += p[0];
                fy += p[1];
                n++;
            }
            fx /= n;
            fy /= n;

            // Whose land lies where around that stretch.
            // A wide look, so a bulge in the line does not turn the numbers: they follow the front as a whole.
            float radius = Math.max(12f, sh * 0.30f / pxPerMap);
            survey(owners, control, fx, fy, radius, f.a, f.b);
            if (landA[2] < 1 && landB[2] < 1) continue;
            float ax = landA[2] > 0 ? landA[0] / landA[2] : fx, ay = landA[2] > 0 ? landA[1] / landA[2] : fy;
            float bx = landB[2] > 0 ? landB[0] / landB[2] : fx, by = landB[2] > 0 ? landB[1] / landB[2] : fy;
            if (landA[2] < 1) { // one side holds nothing here: mirror the other through the front
                ax = 2 * fx - bx;
                ay = 2 * fy - by;
            } else if (landB[2] < 1) {
                bx = 2 * fx - ax;
                by = 2 * fy - ay;
            }
            float dx = bx - ax, dy = by - ay;
            float len = (float) Math.sqrt(dx * dx + dy * dy);
            if (len < 0.5f) {
                dx = 1;
                dy = 0;
                len = 1;
            }
            dx /= len;
            dy /= len;
            // Both numbers at the same distance from the line between the two lands, never on top of each other.
            float mx = (ax + bx) / 2, my = (ay + by) / 2;
            float apart = Math.max(len / 2, sh * 0.11f / pxPerMap);
            float angle = (float) Math.toDegrees(Math.atan2(dx, -dy));
            if (angle > 90) angle -= 180;
            if (angle < -90) angle += 180;
            place(wm, f.a, f.b, f.flagsA, flagsOf, mx, my, -dx, -dy, apart, angle, owners, control, ease, delta, log);
            place(wm, f.b, f.a, f.flagsB, flagsOf, mx, my, dx, dy, apart, angle, owners, control, ease, delta, log);
        }
        if (log != null) debug = log.toString();
        shown.values().removeIf(s -> {
            s.alpha += ((s.seen ? 1f : 0f) - s.alpha) * Math.min(1f, delta * 6f);
            return !s.seen && s.alpha < 0.03f;
        });
        if (!shown.isEmpty()) draw(hud, sw, sh);
    }

    /** One side's number: on its own land, {@code apart} from the middle of the front along {@code dx, dy}. */
    private void place(WarManager wm, int owner, int enemy, int flags, Map<Integer, Integer> flagsOf, float mx, float my,
                       float dx, float dy, float apart, float angle, NativeIntGrid owners, NativeIntGrid control,
                       float ease, float delta, StringBuilder log) {
        CountryState state = wm.stateOf(owner);
        if (state == null) return;
        long army = Math.max(0, state.getInfantry()) + Math.max(0, state.getArtillery()) + Math.max(0, state.getTanks());
        int all = flagsOf.getOrDefault(owner, 0);
        // No flags of its own on this front (thin lines, a siege): count it as one front among the others.
        float share = flags > 0 ? flags / (float) all : 1f / (1 + all);
        float troops = army * share;
        if (troops < 1) return;
        // The first spot along the way that really is this side's land.
        float x = mx + dx * apart, y = my + dy * apart;
        for (float step : new float[]{1f, 1.4f, 0.7f, 1.9f, 0.45f}) {
            float tx = mx + dx * apart * step, ty = my + dy * apart * step;
            if (holder(owners, control, Math.round(tx), Math.round(ty)) == owner) {
                x = tx;
                y = ty;
                break;
            }
        }
        if (log != null) log.append(state.getName()).append(": army ").append(army).append(", flags ").append(flags).append('/').append(all)
                .append(" -> ").append(Math.round(troops)).append(" at ").append(Math.round(x)).append(',').append(Math.round(y))
                .append(holder(owners, control, Math.round(x), Math.round(y)) == owner ? " (own land)" : " (NOT own land)")
                .append(" angle ").append(Math.round(angle)).append("; ");
        Shown s = shown.get(key(owner, enemy));
        if (s == null) {
            s = new Shown();
            s.mapX = x;
            s.mapY = y;
            s.angle = angle;
            s.troops = troops;
            shown.put(key(owner, enemy), s);
        } else {
            s.mapX += (x - s.mapX) * ease;
            s.mapY += (y - s.mapY) * ease;
            float turn = angle - s.angle;
            if (turn > 90) turn -= 180;
            if (turn < -90) turn += 180;
            s.angle += turn * ease;
            if (s.angle > 90) s.angle -= 180;
            if (s.angle < -90) s.angle += 180;
            s.troops += (troops - s.troops) * Math.min(1f, delta * 2f);
        }
        if (Hooks.mapToScreen(s.mapX, s.mapY, point)) {
            s.x = point[0];
            s.y = point[1];
            s.seen = true;
        }
    }

    /** Who holds a map cell: the occupier where the game tracks one, else the owner. */
    private static int holder(NativeIntGrid owners, NativeIntGrid control, int x, int y) {
        if (x < 0 || y < 0 || x >= owners.getWidth() || y >= owners.getHeight()) return -1;
        if (control != null && x < control.getWidth() && y < control.getHeight()) {
            int c = control.at(x, y);
            if (c > 0) return c;
        }
        return owners.at(x, y);
    }

    /** Sums the cells each side holds in a disc around the front into {@link #landA} and {@link #landB}. */
    private void survey(NativeIntGrid owners, NativeIntGrid control, float cx, float cy, float radius, int a, int b) {
        landA[0] = landA[1] = landA[2] = 0;
        landB[0] = landB[1] = landB[2] = 0;
        float step = Math.max(1f, radius / 18f);
        for (float y = -radius; y <= radius; y += step) {
            for (float x = -radius; x <= radius; x += step) {
                if (x * x + y * y > radius * radius) continue;
                int h = holder(owners, control, Math.round(cx + x), Math.round(cy + y));
                float[] land = h == a ? landA : h == b ? landB : null;
                if (land == null) continue;
                land[0] += cx + x;
                land[1] += cy + y;
                land[2]++;
            }
        }
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
                if (UiSelfTest.active) {
                    String by = "";
                    for (GameHud.Block b : hud.blocks()) {
                        if (!b.what().equals("top bar") && !b.what().equals("dock") && b.hits(s.x - reach, s.y - reach, 2 * reach, 2 * reach, 0)) by += b + " ";
                    }
                    debug += "[draw " + text + " at " + Math.round(s.x) + "," + Math.round(s.y) + " alpha " + s.alpha + " covered by: " + by + "] ";
                }
                if (s.x < -reach || s.x > sw + reach || covered(hud, s.x - reach, s.y - reach, 2 * reach)) continue;
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

    /** True when the square touches anything of the game's interface (panels, HUD buttons, notices). */
    private static boolean covered(GameHud.Layout hud, float x, float y, float size) {
        for (GameHud.Block b : hud.blocks()) {
            if (b.what().equals("top bar") || b.what().equals("dock")) continue;
            if (b.hits(x, y, size, size, 0)) return true;
        }
        return false;
    }

    private static float dist2(float x1, float y1, float x2, float y2) { return (x1 - x2) * (x1 - x2) + (y1 - y2) * (y1 - y2); }

    private static long key(int owner, int enemy) { return ((long) owner << 32) | (enemy & 0xffffffffL); }
}
