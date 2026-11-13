package aoc.api.ui;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.InputProcessor;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.utils.Align;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * A mod's UI layer, drawn on top of the game in the game's own style. Every mod gets one from
 * {@code ModContext.ui()}; the loader draws it after the frame and gives it input first.
 *
 * <pre>{@code
 * Window w = new Window("Hello", 400, 220);
 * w.content().add(new Label("Hi!"), new Button("Close").primary().onClick(w::close));
 * ctx.ui().open(w);
 * }</pre>
 */
public final class Ui implements InputProcessor {
    /** Screen corner or edge a HUD widget hangs from. */
    public enum Anchor { TOP_LEFT, TOP_CENTER, TOP_RIGHT, LEFT_CENTER, RIGHT_CENTER, BOTTOM_LEFT, BOTTOM_CENTER, BOTTOM_RIGHT }

    private record Hud(Widget<?> widget, Anchor anchor, int dx, int dy, BooleanSupplier when,
                       java.util.function.Supplier<float[]> rect) {}

    private record Toast(String text, float until) {}

    private final List<Window> windows = new ArrayList<>();
    private final List<Hud> hud = new ArrayList<>();
    private final List<Toast> toasts = new ArrayList<>();
    private final Map<Integer, Runnable> keys = new HashMap<>();
    private SpriteBatch batch;
    private final Matrix4 projection = new Matrix4();
    private float clock;

    private Widget<?> focus, hover, pressed;
    private Window dragging, closing;
    private float lastX, lastY;

    // ---- building ----

    public void open(Window w) {
        windows.remove(w);
        windows.add(w);
        w.ui = this;
    }

    public void close(Window w) {
        if (windows.remove(w)) {
            if (focus != null && within(w, focus)) focus = null;
        }
    }

    public boolean isOpen(Window w) { return windows.contains(w); }

    public void toggle(Window w) {
        if (isOpen(w)) close(w);
        else open(w);
    }

    /** Pins a widget to the screen, shown while {@code when} is true. Offsets/size are design pixels. */
    public void addHud(Widget<?> widget, Anchor anchor, int dx, int dy, BooleanSupplier when) {
        hud.add(new Hud(widget, anchor, dx, dy, when, null));
        widget.ui = this;
    }

    /**
     * Places a widget at a rectangle asked for every frame ({@code x, y, width, height} in y-down
     * window pixels), shown while {@code when} is true: for widgets that follow a game screen's layout.
     */
    public void addHud(Widget<?> widget, java.util.function.Supplier<float[]> rect, BooleanSupplier when) {
        hud.add(new Hud(widget, Anchor.TOP_LEFT, 0, 0, when, rect));
        widget.ui = this;
    }

    /** A short notice at the top of the screen. */
    public void toast(String text, float seconds) {
        synchronized (toasts) {
            toasts.add(new Toast(text, clock + seconds));
        }
    }

    /**
     * Adds a row to the game's Economy menu, in the Cities section right under the game's own
     * "Found city" row, drawn like the game's rows. {@code chip}: the text of the row's small badge
     * (null for none); {@code button}: the caption of the row's button; {@code onClick} runs when the
     * row or its button is clicked (render thread). Call once, e.g. in {@code onLoad}; rows appear the
     * next time the game builds the menu.
     */
    public void addEconomyCityRow(String name, String description, java.util.function.Supplier<String> chip,
                                  java.util.function.Supplier<String> button, Runnable onClick) {
        aoc.api.internal.EconomyRows.add(new aoc.api.internal.EconomyRows.Spec(name, description, chip, button, onClick));
    }

    /** Runs {@code action} when the key is pressed, even while typing; bind function keys, not letters. */
    public void bindKey(int keycode, Runnable action) { keys.put(keycode, action); }

    /**
     * Adds a launcher for this mod: a row in the main menu under MODS while there is room (the loader
     * keeps at most two rows there, so the game's own rows and EXIT stay on screen), otherwise a button
     * at the top of the Code Mods window (main menu > MODS > CODE MODS). Call it once, in {@code onLoad}
     * or later; {@code onClick} runs on the render thread.
     */
    public void addMainMenuButton(String label, Runnable onClick) {
        aoc.api.internal.Hooks.addMainMenuRow(label, onClick);
    }

    public void unbindKey(int keycode) { keys.remove(keycode); }

    public Widget<?> focus() { return focus; }

    public Widget<?> hover() { return hover; }

    public Widget<?> pressedWidget() { return pressed; }

    public void setFocus(Widget<?> w) { focus = w == null || w.focusable() ? w : null; }

    public boolean hasContent() { return !windows.isEmpty() || !hud.isEmpty() || !toasts.isEmpty(); }

    // ---- drawing ----

    /** Called by the loader after the game's frame. */
    public void render(float delta) {
        clock += delta;
        if (!hasContent()) return;
        float sw = Gdx.graphics.getWidth(), sh = Gdx.graphics.getHeight();
        if (batch == null) batch = new SpriteBatch();
        // Same y-down projection the game's own UI uses.
        projection.setToOrtho2D(0, 0, sw, sh);
        projection.val[Matrix4.M11] = -2f / sh;
        projection.val[Matrix4.M13] = 1f;
        Gdx.gl.glViewport(0, 0, Gdx.graphics.getBackBufferWidth(), Gdx.graphics.getBackBufferHeight());
        Gdx.gl.glEnable(GL20.GL_BLEND);
        batch.setProjectionMatrix(projection);
        batch.begin();
        try {
            for (Hud hd : hud) {
                if (!hd.when().getAsBoolean() || !hd.widget().visible) continue;
                placeHud(hd, sw, sh);
                attach(hd.widget());
                hd.widget().draw(batch, delta);
            }
            for (Window w : new ArrayList<>(windows)) {
                w.place(sw, sh);
                attach(w);
                w.draw(batch, delta);
            }
            drawToasts(sw);
            drawTooltip();
        } finally {
            batch.end();
            Gdx.gl.glDisable(GL20.GL_SCISSOR_TEST);
        }
    }

    private void placeHud(Hud hd, float sw, float sh) {
        Widget<?> wd = hd.widget();
        if (hd.rect() != null) {
            float[] r = hd.rect().get();
            wd.layout(r[0], r[1], r[2], r[3]);
            return;
        }
        float w = wd.preferredWidth(), h = wd.preferredHeight(w);
        float dx = Theme.s(hd.dx()), dy = Theme.s(hd.dy());
        float x, y;
        switch (hd.anchor()) {
            case TOP_CENTER: x = (sw - w) / 2 + dx; y = dy; break;
            case TOP_RIGHT: x = sw - w - dx; y = dy; break;
            case LEFT_CENTER: x = dx; y = (sh - h) / 2 + dy; break;
            case RIGHT_CENTER: x = sw - w - dx; y = (sh - h) / 2 + dy; break;
            case BOTTOM_LEFT: x = dx; y = sh - h - dy; break;
            case BOTTOM_CENTER: x = (sw - w) / 2 + dx; y = sh - h - dy; break;
            case BOTTOM_RIGHT: x = sw - w - dx; y = sh - h - dy; break;
            default: x = dx; y = dy;
        }
        wd.layout(x, y, w, h);
    }

    private void drawToasts(float sw) {
        float y = Theme.s(90);
        synchronized (toasts) {
            toasts.removeIf(t -> t.until() < clock);
            for (Toast t : toasts) {
                float tw = Math.min(sw - Theme.s(40), Theme.textWidth(Theme.Font.MAIN, t.text()) + Theme.s(40));
                float th = Theme.wrappedHeight(Theme.Font.MAIN, t.text(), tw - Theme.s(32)) + Theme.s(20);
                float x = (sw - tw) / 2;
                Theme.panel(batch, (int) x, (int) y, (int) tw, (int) th);
                Theme.wrapped(batch, Theme.Font.MAIN, t.text(), x + Theme.s(16), y + Theme.s(10), tw - Theme.s(32), Align.center, Theme.textDark());
                y += th + Theme.s(8);
            }
        }
    }

    private void drawTooltip() {
        if (hover == null || hover.tooltip == null || pressed != null) return;
        float pad = Theme.s(8);
        float tw = Math.min(Theme.s(320), Theme.textWidth(Theme.Font.SMALL, hover.tooltip) + 2 * pad);
        float th = Theme.wrappedHeight(Theme.Font.SMALL, hover.tooltip, tw - 2 * pad) + 2 * pad;
        float x = Math.min(lastX + Theme.s(14), Gdx.graphics.getWidth() - tw), y = lastY + Theme.s(18);
        Theme.rect(batch, x, y, tw, th, Theme.tooltipBg());
        Theme.wrapped(batch, Theme.Font.SMALL, hover.tooltip, x + pad, y + pad, tw - 2 * pad, Align.left, Theme.tooltipText());
    }

    /** Limits drawing to a rectangle (y-down window pixels). */
    public void clip(float x, float y, float w, float h, SpriteBatch b) {
        b.flush();
        float sx = Gdx.graphics.getBackBufferWidth() / (float) Gdx.graphics.getWidth();
        float sy = Gdx.graphics.getBackBufferHeight() / (float) Gdx.graphics.getHeight();
        Gdx.gl.glEnable(GL20.GL_SCISSOR_TEST);
        Gdx.gl.glScissor(Math.round(x * sx), Math.round((Gdx.graphics.getHeight() - y - h) * sy),
                Math.max(0, Math.round(w * sx)), Math.max(0, Math.round(h * sy)));
    }

    public void unclip(SpriteBatch b) {
        b.flush();
        Gdx.gl.glDisable(GL20.GL_SCISSOR_TEST);
    }

    private void attach(Widget<?> w) {
        w.ui = this;
        for (Widget<?> c : w.children()) attach(c);
    }

    private static boolean within(Widget<?> root, Widget<?> target) {
        if (root == target) return true;
        for (Widget<?> c : root.children()) if (within(c, target)) return true;
        return false;
    }

    // ---- hit testing ----

    private Window windowAt(float mx, float my) {
        for (int i = windows.size() - 1; i >= 0; i--) {
            Window w = windows.get(i);
            if (w.contains(mx, my)) return w;
        }
        return null;
    }

    /** Deepest visible widgets under the point, innermost first. */
    private static void chain(Widget<?> root, float mx, float my, List<Widget<?>> out) {
        if (!root.visible || !root.contains(mx, my)) return;
        List<Widget<?>> kids = root.children();
        for (int i = kids.size() - 1; i >= 0; i--) {
            int before = out.size();
            chain(kids.get(i), mx, my, out);
            if (out.size() > before) break;
        }
        out.add(root);
    }

    private List<Widget<?>> chainAt(float mx, float my) {
        List<Widget<?>> out = new ArrayList<>();
        Window w = windowAt(mx, my);
        if (w != null) {
            chain(w, mx, my, out);
            return out;
        }
        for (int i = hud.size() - 1; i >= 0; i--) {
            Hud hd = hud.get(i);
            if (hd.when().getAsBoolean() && hd.widget().visible && hd.widget().contains(mx, my)) {
                chain(hd.widget(), mx, my, out);
                return out;
            }
        }
        return out;
    }

    // ---- input ----

    @Override
    public boolean touchDown(int sx, int sy, int pointer, int button) {
        lastX = sx;
        lastY = sy;
        Window w = windowAt(sx, sy);
        if (w != null) {
            open(w); // bring to front
            if (w.inClose(sx, sy)) {
                closing = w;
                return true;
            }
            if (w.inHeader(sx, sy)) {
                dragging = w;
                return true;
            }
        }
        List<Widget<?>> chain = chainAt(sx, sy);
        if (chain.isEmpty()) {
            focus = null;
            return false;
        }
        for (Widget<?> wd : chain) {
            if (wd.touchDown(sx, sy, button)) {
                pressed = wd;
                if (!wd.focusable()) focus = null;
                return true;
            }
        }
        focus = null;
        return true;
    }

    @Override
    public boolean touchDragged(int sx, int sy, int pointer) {
        float dx = sx - lastX, dy = sy - lastY;
        lastX = sx;
        lastY = sy;
        if (dragging != null) {
            dragging.moveBy(dx, dy);
            return true;
        }
        if (pressed != null) {
            pressed.dragged(sx, sy);
            return true;
        }
        return windowAt(sx, sy) != null;
    }

    @Override
    public boolean touchUp(int sx, int sy, int pointer, int button) {
        boolean handled = dragging != null || pressed != null || closing != null;
        if (closing != null) {
            Window w = closing;
            closing = null;
            if (w.inClose(sx, sy)) {
                Theme.click();
                close(w);
                w.closedByUser();
            }
        }
        dragging = null;
        if (pressed != null) {
            Widget<?> p = pressed;
            pressed = null;
            p.touchUp(sx, sy, button, p.contains(sx, sy));
        }
        return handled || windowAt(sx, sy) != null;
    }

    @Override
    public boolean mouseMoved(int sx, int sy) {
        lastX = sx;
        lastY = sy;
        List<Widget<?>> chain = chainAt(sx, sy);
        hover = chain.isEmpty() ? null : chain.get(0);
        return windowAt(sx, sy) != null;
    }

    @Override
    public boolean scrolled(float amountX, float amountY) {
        int mx = Gdx.input.getX(), my = Gdx.input.getY();
        for (Widget<?> wd : chainAt(mx, my)) if (wd.scrolled(amountY, mx, my)) return true;
        return windowAt(mx, my) != null;
    }

    @Override
    public boolean keyDown(int keycode) {
        Runnable r = keys.get(keycode);
        if (r != null) {
            r.run();
            return true;
        }
        return focus != null && focus.keyDown(keycode);
    }

    @Override
    public boolean keyTyped(char c) { return focus != null && focus.keyTyped(c); }

    @Override
    public boolean keyUp(int keycode) { return focus != null; }

    @Override
    public boolean touchCancelled(int sx, int sy, int pointer, int button) {
        dragging = null;
        pressed = null;
        closing = null;
        return false;
    }
}
