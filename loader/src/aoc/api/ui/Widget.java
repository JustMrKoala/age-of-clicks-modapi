package aoc.api.ui;

import com.badlogic.gdx.graphics.g2d.SpriteBatch;

import java.util.List;

/**
 * Base of every UI element. Bounds are y-down window pixels set by the parent's layout.
 *
 * <p>Sizing: a widget has a fixed {@link #width}/{@link #height}, a flexible share of the
 * space left in its row or column ({@link #flex}), or reports its natural size through
 * {@link #preferredWidth} and {@link #preferredHeight}. Sizes are design pixels, scaled
 * by the game's UI scale.
 */
public abstract class Widget<S extends Widget<S>> {
    protected float x, y, w, h;
    protected boolean visible = true;
    /** HUD widgets only: false while the game's interface leaves no room for it. */
    boolean hudShown = true;
    protected int fixedW = -1, fixedH = -1;
    protected float flex;
    protected String tooltip;
    Ui ui;

    // ---- configuration (chainable) ----

    public S width(int designPx) {
        fixedW = designPx;
        return self();
    }

    public S height(int designPx) {
        fixedH = designPx;
        return self();
    }

    /** Takes a share of the space left after fixed-size siblings. */
    public S flex(float weight) {
        flex = weight;
        return self();
    }

    public S tooltip(String text) {
        tooltip = text;
        return self();
    }

    public S visible(boolean v) {
        visible = v;
        return self();
    }

    @SuppressWarnings("unchecked")
    protected final S self() { return (S) this; }

    public boolean isVisible() { return visible; }

    public float x() { return x; }
    public float y() { return y; }
    public float width() { return w; }
    public float height() { return h; }

    public boolean contains(float px, float py) { return px >= x && px < x + w && py >= y && py < y + h; }

    // ---- layout and drawing ----

    /** Height this widget wants when given {@code width}; used by column layouts. */
    public float preferredHeight(float width) {
        return fixedH >= 0 ? Theme.s(fixedH) : naturalHeight(width);
    }

    /** Width this widget wants; used by row layouts for non-flex children. */
    public float preferredWidth() { return fixedW >= 0 ? Theme.s(fixedW) : naturalWidth(); }

    protected float naturalHeight(float width) { return Theme.lineHeight(Theme.Font.MAIN) * 1.6f; }

    protected float naturalWidth() { return Theme.s(100); }

    public void layout(float x, float y, float w, float h) {
        this.x = x;
        this.y = y;
        this.w = w;
        this.h = h;
    }

    public abstract void draw(SpriteBatch b, float delta);

    /** Child widgets, for containers. */
    public List<Widget<?>> children() { return List.of(); }

    // ---- input; return true when handled ----

    public boolean touchDown(float mx, float my, int button) { return false; }

    public void touchUp(float mx, float my, int button, boolean inside) {}

    public void dragged(float mx, float my) {}

    public boolean scrolled(float amount, float mx, float my) { return false; }

    /** Only the focused widget receives keys. */
    public boolean keyTyped(char c) { return false; }

    public boolean keyDown(int keycode) { return false; }

    public boolean focusable() { return false; }

    protected boolean focused() { return ui != null && ui.focus() == this; }

    protected boolean hovered() { return ui != null && ui.hover() == this; }

    protected boolean pressed() { return ui != null && ui.pressedWidget() == this; }
}
