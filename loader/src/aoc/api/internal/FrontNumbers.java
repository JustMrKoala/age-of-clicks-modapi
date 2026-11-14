package aoc.api.internal;

import aoc.api.ui.Theme;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.math.Matrix4;
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
 * front on screen, each side's army on that front is written on its own side of the line, turned
 * along the front. In classic combat the game shows army flags but no numbers; a country's army
 * (infantry + artillery + tanks) is split over its fronts by how many of its flags stand on each.
 */
final class FrontNumbers {
    /** One side of one front: where its flags stand on screen, and how many there are in all. */
    private static final class Side {
        final int owner, enemy;
        float sumX, sumY;
        int onScreen, flags;

        Side(int owner, int enemy) {
            this.owner = owner;
            this.enemy = enemy;
        }
    }

    /** A number as it was drawn last frame; positions, angles and numbers ease from it. */
    private static final class Shown {
        float x, y, angle, troops, alpha;
        boolean seen;
    }

    private final Map<Long, Shown> shown = new HashMap<>();
    private final GlyphLayout layout = new GlyphLayout();
    private final Matrix4 projection = new Matrix4(), transform = new Matrix4(), identity = new Matrix4();
    private final Color ink = new Color();
    private final float[] point = new float[2];
    private SpriteBatch batch;
    /** Self-test: the numbers of the last frame, as text. */
    static String debug = "";

    void render(float delta) {
        GameHud.Layout hud = GameHud.layout();
        GameManager gm = GameManager.getInstance();
        WarManager wm = gm == null ? null : gm.getWarManager();
        FrontlineDecor decor = wm == null ? null : wm.getFrontlineDecor();
        if (!hud.inGame() || hud.hidden() || hud.modal() || decor == null || decor.isEmpty() || !gm.isClassicCombat()) {
            shown.clear();
            return;
        }
        float sw = Gdx.graphics.getWidth(), sh = Gdx.graphics.getHeight();
        Map<Long, Side> sides = new HashMap<>();
        Map<Integer, Integer> flagsOf = new HashMap<>();
        for (FrontlineDecor.Marker m : decor.getMarkers()) {
            if (!m.alive || m.alpha < 0.3f || m.enemyColorKey == m.ownerColorKey) continue;
            if (!wm.areAtWar(m.ownerColorKey, m.enemyColorKey) || !gm.showsUnitFlagsOf(m.ownerColorKey)) continue;
            flagsOf.merge(m.ownerColorKey, 1, Integer::sum);
            Side s = sides.computeIfAbsent(key(m.ownerColorKey, m.enemyColorKey), k -> new Side(m.ownerColorKey, m.enemyColorKey));
            s.flags++;
            if (!Hooks.mapToScreen(m.x, m.y, point)) continue;
            if (point[0] < 0 || point[0] > sw || point[1] < hud.top() || point[1] > hud.bottom()) continue;
            s.sumX += point[0];
            s.sumY += point[1];
            s.onScreen++;
        }
        StringBuilder log = UiSelfTest.active ? new StringBuilder() : null;
        for (Shown s : shown.values()) s.seen = false;
        float ease = Math.min(1f, delta * 4f);
        float back = sh * 0.075f;
        for (Side a : sides.values()) {
            Side b = sides.get(key(a.enemy, a.owner));
            if (a.onScreen == 0 || b == null || b.onScreen == 0) continue;
            CountryState state = wm.stateOf(a.owner);
            if (state == null) continue;
            long army = Math.max(0, state.getInfantry()) + Math.max(0, state.getArtillery()) + Math.max(0, state.getTanks());
            float troops = army * (a.flags / (float) Math.max(1, flagsOf.getOrDefault(a.owner, 1)));
            if (troops < 1) continue;
            if (log != null) log.append(state.getName()).append(" vs ").append(wm.stateOf(a.enemy) == null ? "?" : wm.stateOf(a.enemy).getName())
                    .append(": army ").append(army).append(", flags ").append(a.flags).append('/').append(flagsOf.get(a.owner))
                    .append(" (").append(a.onScreen).append(" on screen) -> ").append(Math.round(troops)).append("; ");
            // From this side's flags towards the enemy's: the number sits behind its own flags, along the line.
            float ax = a.sumX / a.onScreen, ay = a.sumY / a.onScreen;
            float dx = b.sumX / b.onScreen - ax, dy = b.sumY / b.onScreen - ay;
            float len = (float) Math.sqrt(dx * dx + dy * dy);
            if (len < 1f) {
                dx = 1;
                dy = 0;
                len = 1;
            }
            dx /= len;
            dy /= len;
            float x = ax - dx * back, y = ay - dy * back;
            float angle = (float) Math.toDegrees(Math.atan2(dx, -dy));
            // Keep the text upright: readable from left to right.
            if (angle > 90) angle -= 180;
            if (angle < -90) angle += 180;
            Shown s = shown.get(key(a.owner, a.enemy));
            if (s == null) {
                s = new Shown();
                s.x = x;
                s.y = y;
                s.angle = angle;
                s.troops = troops;
                shown.put(key(a.owner, a.enemy), s);
            } else {
                s.x += (x - s.x) * ease;
                s.y += (y - s.y) * ease;
                float turn = angle - s.angle;
                if (turn > 90) turn -= 180;
                if (turn < -90) turn += 180;
                s.angle += turn * ease;
                if (s.angle > 90) s.angle -= 180;
                if (s.angle < -90) s.angle += 180;
                s.troops += (troops - s.troops) * Math.min(1f, delta * 2f);
            }
            s.seen = true;
        }
        if (log != null) debug = log.toString();
        shown.values().removeIf(s -> {
            s.alpha += ((s.seen ? 1f : 0f) - s.alpha) * Math.min(1f, delta * 6f);
            return !s.seen && s.alpha < 0.03f;
        });
        if (!shown.isEmpty()) draw(hud, sw, sh);
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
                if (covered(hud, s.x - reach, s.y - reach, 2 * reach)) continue;
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

    private static long key(int owner, int enemy) { return ((long) owner << 32) | (enemy & 0xffffffffL); }
}
