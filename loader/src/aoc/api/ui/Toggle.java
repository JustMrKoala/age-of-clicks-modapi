package aoc.api.ui;

import com.badlogic.gdx.graphics.g2d.SpriteBatch;

import java.util.function.Consumer;

/** On/off switch: a small framed box with the game's red fill when on, and a label (On/Off if none). */
public class Toggle extends Widget<Toggle> {
    private final String label;
    private boolean on;
    private Consumer<Boolean> onChange = v -> {};

    public Toggle(String label, boolean on) {
        this.label = label;
        this.on = on;
    }

    public boolean value() { return on; }

    public Toggle value(boolean v) {
        on = v;
        return this;
    }

    public Toggle onChange(Consumer<Boolean> c) {
        onChange = c;
        return this;
    }

    @Override
    protected float naturalHeight(float width) { return Theme.s(30); }

    @Override
    protected float naturalWidth() { return Theme.s(64) + Theme.textWidth(Theme.Font.MAIN, label); }

    @Override
    public void draw(SpriteBatch b, float delta) {
        float tw = Theme.s(46), th = Theme.s(24);
        float ty = y + (h - th) / 2f;
        Theme.box(b, (int) x, (int) ty, (int) tw, (int) th, on ? Theme.red() : Theme.surfaceLow(), Theme.border());
        float knob = th - Theme.s(6);
        float kx = on ? x + tw - knob - Theme.s(3) : x + Theme.s(3);
        Theme.box(b, (int) kx, (int) (ty + Theme.s(3)), (int) knob, (int) knob, Theme.buttonBg(), Theme.border());
        if (hovered()) Theme.rect(b, x, ty, tw, th, Theme.rowHover());
        String shown = label == null || label.isEmpty() ? (on ? "On" : "Off") : label;
        Theme.text(b, Theme.Font.MAIN, shown, x + tw + Theme.s(10), y + (h - Theme.lineHeight(Theme.Font.MAIN)) / 2f, Theme.textDark());
    }

    @Override
    public boolean touchDown(float mx, float my, int button) { return true; }

    @Override
    public void touchUp(float mx, float my, int button, boolean inside) {
        if (!inside) return;
        on = !on;
        Theme.click();
        onChange.accept(on);
    }
}
