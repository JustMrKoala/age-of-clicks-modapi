package aoc.api.ui;

import com.badlogic.gdx.graphics.g2d.SpriteBatch;

import java.util.ArrayList;
import java.util.List;

/** Lays out children in a column or a row, with optional card background. */
public class Panel extends Widget<Panel> {
    public enum Direction { COLUMN, ROW }

    public enum Background { NONE, CARD, WELL }

    private final Direction direction;
    private final List<Widget<?>> children = new ArrayList<>();
    private int padding;
    private int gap = 6;
    private Background background = Background.NONE;

    public Panel(Direction direction) { this.direction = direction; }

    public static Panel column() { return new Panel(Direction.COLUMN); }

    public static Panel row() { return new Panel(Direction.ROW); }

    public Panel add(Widget<?>... widgets) {
        for (Widget<?> w : widgets) children.add(w);
        return this;
    }

    public Panel remove(Widget<?> widget) {
        children.remove(widget);
        return this;
    }

    public Panel clear() {
        children.clear();
        return this;
    }

    public Panel padding(int designPx) {
        padding = designPx;
        return this;
    }

    public Panel gap(int designPx) {
        gap = designPx;
        return this;
    }

    public Panel background(Background bg) {
        background = bg;
        return this;
    }

    @Override
    public List<Widget<?>> children() { return children; }

    @Override
    protected float naturalHeight(float width) {
        float pad = Theme.s(padding) * 2;
        if (direction == Direction.ROW) {
            float max = 0;
            for (Widget<?> c : children) if (c.visible) max = Math.max(max, c.preferredHeight(c.preferredWidth()));
            return max + pad;
        }
        float total = pad;
        int n = 0;
        for (Widget<?> c : children) {
            if (!c.visible) continue;
            total += c.preferredHeight(width - pad);
            n++;
        }
        return total + Math.max(0, n - 1) * Theme.s(gap);
    }

    @Override
    public void layout(float x, float y, float w, float h) {
        super.layout(x, y, w, h);
        float pad = Theme.s(padding), g = Theme.s(gap);
        float ix = x + pad, iy = y + pad, iw = w - 2 * pad, ih = h - 2 * pad;
        List<Widget<?>> shown = new ArrayList<>();
        for (Widget<?> c : children) if (c.visible) shown.add(c);
        if (shown.isEmpty()) return;
        boolean column = direction == Direction.COLUMN;
        float available = (column ? ih : iw) - g * (shown.size() - 1);
        float fixedTotal = 0, flexTotal = 0;
        float[] sizes = new float[shown.size()];
        for (int i = 0; i < shown.size(); i++) {
            Widget<?> c = shown.get(i);
            if (c.flex > 0) {
                flexTotal += c.flex;
            } else {
                sizes[i] = column ? c.preferredHeight(iw) : c.preferredWidth();
                fixedTotal += sizes[i];
            }
        }
        float leftover = Math.max(0, available - fixedTotal);
        float pos = column ? iy : ix;
        for (int i = 0; i < shown.size(); i++) {
            Widget<?> c = shown.get(i);
            float size = c.flex > 0 ? leftover * c.flex / flexTotal : sizes[i];
            if (column) c.layout(ix, pos, iw, size);
            else c.layout(pos, iy, size, ih);
            pos += size + g;
        }
    }

    @Override
    public void draw(SpriteBatch b, float delta) {
        if (background == Background.CARD) {
            Theme.box(b, (int) x, (int) y, (int) w, (int) h, Theme.surfaceHigh(), Theme.separator());
        } else if (background == Background.WELL) {
            Theme.box(b, (int) x, (int) y, (int) w, (int) h, Theme.surfaceLow(), Theme.separator());
        }
        for (Widget<?> c : children) if (c.visible) c.draw(b, delta);
    }
}
