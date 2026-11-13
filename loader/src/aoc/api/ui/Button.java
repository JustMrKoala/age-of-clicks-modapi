package aoc.api.ui;

import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.utils.Align;

import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** The game's iron-edged button. */
public class Button extends Widget<Button> {
    private Supplier<String> text;
    private Theme.ButtonStyle style = Theme.ButtonStyle.SECONDARY;
    private Runnable onClick = () -> {};
    private BooleanSupplier enabled = () -> true;

    public Button(String text) { this.text = () -> text; }

    public Button(Supplier<String> text) { this.text = text; }

    public Button style(Theme.ButtonStyle s) {
        style = s;
        return this;
    }

    public Button primary() { return style(Theme.ButtonStyle.PRIMARY); }

    public Button onClick(Runnable r) {
        onClick = r;
        return this;
    }

    public Button enabled(BooleanSupplier e) {
        enabled = e;
        return this;
    }

    public boolean isEnabled() { return enabled.getAsBoolean(); }

    @Override
    protected float naturalHeight(float width) { return Theme.s(34); }

    @Override
    protected float naturalWidth() { return Theme.textWidth(Theme.Font.MAIN, text.get()) + Theme.s(32); }

    @Override
    public void draw(SpriteBatch b, float delta) {
        boolean on = isEnabled();
        Theme.ButtonStyle s = on ? style : Theme.ButtonStyle.TERTIARY;
        Theme.button(b, (int) x, (int) y, (int) w, (int) h, s, on && pressed());
        if (on && hovered() && !pressed()) Theme.rect(b, x, y, w, h, Theme.rowHover());
        float lh = Theme.lineHeight(Theme.Font.MAIN);
        Theme.wrapped(b, Theme.Font.MAIN, text.get(), x, y + (h - lh) / 2f, w, Align.center,
                on ? Theme.buttonText(style) : Theme.textDisabled());
    }

    @Override
    public boolean touchDown(float mx, float my, int button) { return true; }

    @Override
    public void touchUp(float mx, float my, int button, boolean inside) {
        if (inside && isEnabled()) {
            Theme.click();
            onClick.run();
        }
    }
}
