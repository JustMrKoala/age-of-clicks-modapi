package aoc.api.ui;

import com.badlogic.gdx.graphics.g2d.SpriteBatch;

import java.util.List;

/**
 * A movable window in the game's iron-framed panel style, with a title and close button.
 * Put widgets in {@link #content()}. Size is in design pixels; the window centres itself
 * the first time it opens unless {@link #position} was set.
 */
public class Window extends Widget<Window> {
    private String title;
    private final Panel content = Panel.column().padding(14).gap(10);
    private final int designW, designH;
    private boolean placed;
    private boolean closable = true;
    private Runnable onClose = () -> {};

    public Window(String title, int designW, int designH) {
        this.title = title;
        this.designW = designW;
        this.designH = designH;
    }

    public Panel content() { return content; }

    public Window title(String t) {
        title = t;
        return this;
    }

    public String title() { return title; }

    public Window closable(boolean c) {
        closable = c;
        return this;
    }

    public Window onClose(Runnable r) {
        onClose = r;
        return this;
    }

    /** Top-left corner in window pixels. */
    public Window position(float px, float py) {
        x = px;
        y = py;
        placed = true;
        return this;
    }

    public void open() { if (ui != null) ui.open(this); }

    public void close() {
        if (ui != null) ui.close(this);
    }

    void closedByUser() { onClose.run(); }

    public boolean isOpen() { return ui != null && ui.isOpen(this); }

    float headerHeight() { return Theme.s(44); }

    boolean inHeader(float mx, float my) { return my >= y && my < y + headerHeight() && mx >= x && mx < x + w; }

    boolean inClose(float mx, float my) {
        float s = Theme.s(28), m = Theme.s(10);
        return closable && mx >= x + w - m - s && mx < x + w - m && my >= y + m && my < y + m + s;
    }

    /**
     * Fits the window between {@code top} and {@code bottom} (in game: the game's top bar and dock,
     * which a window never covers) and lays out its content. {@code openLeft..openRight} is the
     * screen width no game panel uses; a window opens in the middle of it.
     */
    void place(float screenW, float screenH, float top, float bottom, float openLeft, float openRight) {
        float room = bottom - top;
        float ww = Math.min(Theme.s(designW), screenW - Theme.s(20));
        float wh = Math.min(Theme.s(designH), room - Theme.s(top > 0 || bottom < screenH ? 12 : 20));
        if (!placed) {
            // Next to an open game panel when the window fits there, else in the middle of the screen.
            x = openRight - openLeft >= ww ? openLeft + (openRight - openLeft - ww) / 2f : (screenW - ww) / 2f;
            y = top + (room - wh) / 2f;
            placed = true;
        }
        x = Math.max(0, Math.min(x, screenW - ww));
        y = Math.max(top, Math.min(y, bottom - wh));
        w = ww;
        h = wh;
        content.layout(x, y + headerHeight(), w, h - headerHeight());
    }

    void moveBy(float dx, float dy) {
        x += dx;
        y += dy;
    }

    @Override
    public List<Widget<?>> children() { return List.of(content); }

    @Override
    public void draw(SpriteBatch b, float delta) {
        Theme.shadow(b, (int) x, (int) y, (int) w, (int) h);
        Theme.panel(b, (int) x, (int) y, (int) w, (int) h);
        float pad = Theme.s(16);
        Theme.text(b, Theme.Font.LARGE, title, x + pad, y + (headerHeight() - Theme.lineHeight(Theme.Font.LARGE)) / 2f + Theme.s(2), Theme.textDark());
        Theme.separator(b, (int) (x + pad), (int) (y + headerHeight()), (int) (w - 2 * pad));
        if (closable) {
            float s = Theme.s(28), m = Theme.s(10);
            Theme.closeButton(b, (int) (x + w - m - s), (int) (y + m), (int) s, (int) s);
        }
        content.draw(b, delta);
    }
}
