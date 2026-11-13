package aoc.api.ui;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

/** Scrollable, selectable list of rows with a main label and an optional coloured note on the right. */
public class ListView<T> extends Widget<ListView<T>> {
    /** Right-hand note of a row. */
    public record Note(String text, Color color) {}

    private Supplier<List<T>> items = List::of;
    private Function<T, String> label = String::valueOf;
    private Function<T, Note> note = t -> null;
    private Predicate<T> filter = t -> true;
    private Consumer<T> onSelect = t -> {};
    private T selected;
    private float scroll;
    private int rowDesignH = 30;

    public ListView<T> items(Supplier<List<T>> s) {
        items = s;
        return this;
    }

    public ListView<T> label(Function<T, String> f) {
        label = f;
        return this;
    }

    public ListView<T> note(Function<T, Note> f) {
        note = f;
        return this;
    }

    public ListView<T> filter(Predicate<T> p) {
        filter = p;
        scroll = 0;
        return this;
    }

    public ListView<T> onSelect(Consumer<T> c) {
        onSelect = c;
        return this;
    }

    public ListView<T> rowHeight(int designPx) {
        rowDesignH = designPx;
        return this;
    }

    public T selected() { return selected; }

    public void select(T item) { selected = item; }

    private List<T> visibleItems() {
        List<T> out = new ArrayList<>();
        for (T t : items.get()) if (filter.test(t)) out.add(t);
        return out;
    }

    @Override
    protected float naturalHeight(float width) { return Theme.s(rowDesignH) * 8; }

    @Override
    public void draw(SpriteBatch b, float delta) {
        Theme.box(b, (int) x, (int) y, (int) w, (int) h, Theme.surfaceLow(), Theme.separator());
        List<T> list = visibleItems();
        float rh = Theme.s(rowDesignH);
        scroll = Math.max(0, Math.min(scroll, Math.max(0, list.size() * rh - h)));
        ui.clip(x + 1, y + 1, w - 2, h - 2, b);
        float lh = Theme.lineHeight(Theme.Font.MAIN);
        float pad = Theme.s(10);
        int first = (int) (scroll / rh);
        for (int i = first; i < list.size(); i++) {
            float ry = y + i * rh - scroll;
            if (ry > y + h) break;
            T item = list.get(i);
            boolean sel = item.equals(selected);
            if (sel) Theme.selected(b, (int) x + 1, (int) ry + 1, (int) w - 2, (int) rh - 2);
            else if (i % 2 == 1) Theme.rect(b, x, ry, w, rh, Theme.rowAlt());
            Note n = note.apply(item);
            float noteW = 0;
            if (n != null && n.text() != null) {
                noteW = Theme.textWidth(Theme.Font.SMALL, n.text());
                Theme.text(b, Theme.Font.SMALL, n.text(), x + w - pad - noteW, ry + (rh - Theme.lineHeight(Theme.Font.SMALL)) / 2f,
                        n.color() == null ? Theme.textMuted() : n.color());
            }
            String text = label.apply(item);
            while (text.length() > 1 && Theme.textWidth(Theme.Font.MAIN, text) > w - 2 * pad - noteW - Theme.s(6)) {
                text = text.substring(0, text.length() - 4) + "...";
            }
            Theme.text(b, Theme.Font.MAIN, text, x + pad, ry + (rh - lh) / 2f, Theme.textDark());
        }
        ui.unclip(b);
    }

    @Override
    public boolean touchDown(float mx, float my, int button) {
        List<T> list = visibleItems();
        int i = (int) ((my - y + scroll) / Theme.s(rowDesignH));
        if (i >= 0 && i < list.size()) {
            selected = list.get(i);
            Theme.click();
            onSelect.accept(selected);
        }
        return true;
    }

    @Override
    public boolean scrolled(float amount, float mx, float my) {
        scroll += amount * Theme.s(rowDesignH) * 3;
        return true;
    }
}
