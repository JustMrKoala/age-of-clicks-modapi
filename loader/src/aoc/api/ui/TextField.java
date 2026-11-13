package aoc.api.ui;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;

import java.util.function.Consumer;

/** Single-line text input. Enter submits; Ctrl+V pastes; Escape drops focus. */
public class TextField extends Widget<TextField> {
    private final StringBuilder text = new StringBuilder();
    private String placeholder = "";
    private int maxLength = 500;
    private Consumer<String> onSubmit = s -> {};
    private Consumer<String> onChange = s -> {};
    private float blink;
    private boolean masked;

    public TextField placeholder(String p) {
        placeholder = p;
        return this;
    }

    public TextField maxLength(int n) {
        maxLength = n;
        return this;
    }

    public TextField onSubmit(Consumer<String> c) {
        onSubmit = c;
        return this;
    }

    public TextField onChange(Consumer<String> c) {
        onChange = c;
        return this;
    }

    public String text() { return text.toString(); }

    /** Shows dots instead of the text, for keys and passwords. */
    public TextField masked(boolean m) {
        masked = m;
        return this;
    }

    public TextField text(String t) {
        text.setLength(0);
        text.append(t);
        onChange.accept(text());
        return this;
    }

    public void focus() { if (ui != null) ui.setFocus(this); }

    @Override
    public boolean focusable() { return true; }

    @Override
    protected float naturalHeight(float width) { return Theme.s(34); }

    @Override
    public void draw(SpriteBatch b, float delta) {
        blink += delta;
        Theme.box(b, (int) x, (int) y, (int) w, (int) h, Theme.surfaceHigh(), focused() ? Theme.red() : Theme.separator());
        float pad = Theme.s(8);
        float ty = y + (h - Theme.lineHeight(Theme.Font.MAIN)) / 2f;
        if (text.length() == 0 && !focused()) {
            Theme.text(b, Theme.Font.MAIN, placeholder, x + pad, ty, Theme.textMuted());
            return;
        }
        String shown = masked ? "*".repeat(text.length()) : text.toString();
        while (shown.length() > 0 && Theme.textWidth(Theme.Font.MAIN, shown) > w - 2 * pad - Theme.s(6)) shown = shown.substring(1);
        Theme.text(b, Theme.Font.MAIN, shown, x + pad, ty, Theme.textDark());
        if (focused() && (int) (blink * 2) % 2 == 0) {
            float cx = x + pad + Theme.textWidth(Theme.Font.MAIN, shown) + 1;
            Theme.rect(b, cx, ty + 2, Math.max(1, Theme.s(2)), Theme.lineHeight(Theme.Font.MAIN) - 4, Theme.textDark());
        }
    }

    @Override
    public boolean touchDown(float mx, float my, int button) {
        focus();
        return true;
    }

    @Override
    public boolean keyDown(int keycode) {
        boolean ctrl = Gdx.input.isKeyPressed(Input.Keys.CONTROL_LEFT) || Gdx.input.isKeyPressed(Input.Keys.CONTROL_RIGHT);
        if (keycode == Input.Keys.V && ctrl) {
            String clip = Gdx.app.getClipboard().getContents();
            if (clip != null) insert(clip.replaceAll("[\\r\\n\\t]+", " "));
        } else if (keycode == Input.Keys.ESCAPE && ui != null) {
            ui.setFocus(null);
        }
        return true;
    }

    @Override
    public boolean keyTyped(char c) {
        blink = 0;
        if (c == '\b') {
            if (text.length() > 0) {
                text.setLength(text.length() - 1);
                onChange.accept(text());
            }
        } else if (c == '\r' || c == '\n') {
            onSubmit.accept(text());
        } else if (!Character.isISOControl(c)) {
            insert(String.valueOf(c));
        }
        return true;
    }

    private void insert(String s) {
        int room = Math.max(0, maxLength - text.length());
        text.append(s, 0, Math.min(s.length(), room));
        onChange.accept(text());
    }
}
