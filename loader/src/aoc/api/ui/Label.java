package aoc.api.ui;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.utils.Align;

import java.util.function.Supplier;

/** Text, wrapped to the widget's width. The text may be live ({@link #Label(Supplier)}). */
public class Label extends Widget<Label> {
    private Supplier<String> text;
    private Theme.Font font = Theme.Font.MAIN;
    private Supplier<Color> color = Theme::textDark;
    private int align = Align.left;

    public Label(String text) { this.text = () -> text; }

    public Label(Supplier<String> text) { this.text = text; }

    public Label text(String t) {
        text = () -> t;
        return this;
    }

    public Label font(Theme.Font f) {
        font = f;
        return this;
    }

    public Label color(Color c) {
        color = () -> c;
        return this;
    }

    public Label color(Supplier<Color> c) {
        color = c;
        return this;
    }

    public Label muted() { return color(Theme::textMuted); }

    public Label center() {
        align = Align.center;
        return this;
    }

    public Label right() {
        align = Align.right;
        return this;
    }

    public String text() {
        String t = text.get();
        return t == null ? "" : t;
    }

    @Override
    protected float naturalHeight(float width) { return Theme.wrappedHeight(font, text(), width); }

    @Override
    protected float naturalWidth() { return Theme.textWidth(font, text()) + Theme.s(4); }

    @Override
    public void draw(SpriteBatch b, float delta) {
        Theme.wrapped(b, font, text(), x, y, w, align, color.get());
    }
}
