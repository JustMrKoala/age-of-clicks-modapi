package aoc.api.ui;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.g2d.freetype.FreeTypeFontGenerator;
import com.badlogic.gdx.utils.Align;
import ru.ius.david.cgs.CFG;
import ru.ius.david.cgs.managers.SoundManager;

/**
 * The game's own look: its fonts, colours, iron-framed panels and buttons, and click sound,
 * taken from {@code ru.ius.david.cgs.CFG}. Everything follows the game's light/dark theme and
 * UI scale. Coordinates are y-down window pixels, like the game's UI.
 */
public final class Theme {
    public enum Font { SMALL, MAIN, LARGE, TITLE }

    /** Button colours from the game: red call-to-action, parchment, blue and quiet tertiary. */
    public enum ButtonStyle { PRIMARY, SECONDARY, BLUE, TERTIARY }

    private static Texture white;
    private static BitmapFont fallback;
    private static final GlyphLayout LAYOUT = new GlyphLayout();

    private Theme() {}

    // ---- metrics ----

    /** Scales a design size by the game's UI scale setting. */
    public static int s(int px) {
        try {
            return CFG.s(px);
        } catch (Throwable t) {
            return px;
        }
    }

    // ---- fonts ----

    public static BitmapFont font(Font f) {
        BitmapFont font;
        switch (f) {
            case SMALL: font = CFG.fontSmall; break;
            case LARGE: font = CFG.fontLarge; break;
            case TITLE: font = CFG.fontTitle; break;
            default: font = CFG.fontMain;
        }
        return font != null ? font : fallbackFont();
    }

    private static BitmapFont fallbackFont() {
        if (fallback == null) {
            FreeTypeFontGenerator gen = new FreeTypeFontGenerator(Gdx.files.internal("arial.ttf"));
            FreeTypeFontGenerator.FreeTypeFontParameter p = new FreeTypeFontGenerator.FreeTypeFontParameter();
            p.size = 16;
            p.flip = true;
            p.incremental = true;
            fallback = gen.generateFont(p);
        }
        return fallback;
    }

    public static float lineHeight(Font f) { return Math.abs(font(f).getLineHeight()); }

    public static float textWidth(Font f, String text) {
        LAYOUT.setText(font(f), text);
        return LAYOUT.width;
    }

    public static float wrappedHeight(Font f, String text, float width) {
        LAYOUT.setText(font(f), text, Color.WHITE, Math.max(1, width), Align.left, true);
        return Math.abs(LAYOUT.height) + lineHeight(f) * 0.25f;
    }

    /** One line; {@code y} is the top of the text. */
    public static void text(SpriteBatch b, Font f, String text, float x, float y, Color color) {
        BitmapFont font = font(f);
        font.setColor(color);
        font.draw(b, text, x, y);
    }

    /** Wrapped text; {@code y} is the top. Returns the height used. */
    public static float wrapped(SpriteBatch b, Font f, String text, float x, float y, float width, int align, Color color) {
        BitmapFont font = font(f);
        LAYOUT.setText(font, text, color, Math.max(1, width), align, true);
        font.draw(b, LAYOUT, x, y);
        return Math.abs(LAYOUT.height);
    }

    // ---- colours ----

    public static Color textDark() { return or(CFG.COLOR_TEXT_DARK, new Color(0.13f, 0.12f, 0.11f, 1)); }
    public static Color textMuted() { return or(CFG.COLOR_TEXT_MUTED, new Color(0.42f, 0.40f, 0.37f, 1)); }
    public static Color textOnAccent() { return or(CFG.COLOR_TEXT_ON_ACCENT, Color.WHITE); }
    public static Color textDisabled() { return or(CFG.COLOR_TEXT_DISABLED, Color.GRAY); }
    public static Color panelBg() { return or(CFG.COLOR_PARCHMENT_PANEL_BG, new Color(0.95f, 0.93f, 0.88f, 1)); }
    public static Color buttonBg() { return or(CFG.COLOR_PARCHMENT_BTN_BG, new Color(0.93f, 0.90f, 0.84f, 1)); }
    public static Color red() { return or(CFG.COLOR_RED_BTN, new Color(0.64f, 0.12f, 0.12f, 1)); }
    public static Color blue() { return or(CFG.COLOR_BLUE_BTN, new Color(0.18f, 0.36f, 0.62f, 1)); }
    public static Color tertiary() { return or(CFG.COLOR_TERTIARY_BTN, new Color(0.55f, 0.52f, 0.47f, 1)); }
    public static Color border() { return or(CFG.COLOR_IRON_BORDER, new Color(0.16f, 0.16f, 0.16f, 1)); }
    public static Color separator() { return or(CFG.COLOR_SEPARATOR_LINE, new Color(0.7f, 0.67f, 0.6f, 1)); }
    public static Color rowHover() { return or(CFG.COLOR_ROW_HOVER, new Color(0, 0, 0, 0.06f)); }
    public static Color rowAlt() { return or(CFG.COLOR_ROW_ALT, new Color(0, 0, 0, 0.03f)); }
    public static Color surfaceLow() { return or(CFG.COLOR_SURFACE_LOW, new Color(0.9f, 0.88f, 0.83f, 1)); }
    public static Color surfaceHigh() { return or(CFG.COLOR_SURFACE_HIGH, new Color(0.98f, 0.97f, 0.94f, 1)); }
    public static Color good() { return or(CFG.COLOR_WELLBEING_GOOD, new Color(0.2f, 0.55f, 0.25f, 1)); }
    public static Color warn() { return or(CFG.COLOR_WELLBEING_WARN, new Color(0.75f, 0.55f, 0.1f, 1)); }
    public static Color bad() { return or(CFG.COLOR_WELLBEING_BAD, new Color(0.7f, 0.15f, 0.12f, 1)); }
    public static Color gold() { return or(CFG.COLOR_ICON_GOLD, new Color(0.8f, 0.62f, 0.2f, 1)); }
    public static Color tooltipBg() { return or(CFG.COLOR_TOOLTIP_BG, new Color(0.12f, 0.12f, 0.14f, 0.95f)); }
    public static Color tooltipText() { return or(CFG.COLOR_TOOLTIP_TEXT, Color.WHITE); }

    public static Color buttonColor(ButtonStyle style) {
        switch (style) {
            case PRIMARY: return red();
            case BLUE: return blue();
            case TERTIARY: return tertiary();
            default: return buttonBg();
        }
    }

    public static Color buttonText(ButtonStyle style) {
        return style == ButtonStyle.SECONDARY ? textDark() : textOnAccent();
    }

    private static Color or(Color c, Color fallbackColor) { return c != null ? c : fallbackColor; }

    // ---- shapes ----

    public static void rect(SpriteBatch b, float x, float y, float w, float h, Color color) {
        if (white == null) {
            Pixmap px = new Pixmap(1, 1, Pixmap.Format.RGBA8888);
            px.setColor(Color.WHITE);
            px.fill();
            white = new Texture(px);
            px.dispose();
        }
        Color old = b.getColor().cpy();
        b.setColor(color);
        b.draw(white, x, y, w, h);
        b.setColor(old);
    }

    /** The game's riveted iron-and-parchment window. */
    public static void panel(SpriteBatch b, int x, int y, int w, int h) {
        try {
            CFG.drawIronMenuPanel(b, x, y, w, h);
        } catch (Throwable t) {
            rect(b, x, y, w, h, border());
            rect(b, x + 3, y + 3, w - 6, h - 6, panelBg());
        }
    }

    /** The game's bordered box, used for cards, inputs and wells. */
    public static void box(SpriteBatch b, int x, int y, int w, int h, Color fill, Color edge) {
        box(b, x, y, w, h, fill, edge, 1);
    }

    /** Bordered box with an edge of {@code designThickness} pixels (scaled). */
    public static void box(SpriteBatch b, int x, int y, int w, int h, Color fill, Color edge, int designThickness) {
        int t = Math.max(1, s(designThickness));
        try {
            CFG.drawBorderedBox(b, x, y, w, h, fill, edge, t);
        } catch (Throwable e) {
            rect(b, x, y, w, h, edge);
            rect(b, x + t, y + t, w - 2 * t, h - 2 * t, fill);
        }
    }

    /** The game's selected-item look: bright fill with a red frame. */
    public static void selected(SpriteBatch b, int x, int y, int w, int h) {
        box(b, x, y, w, h, surfaceHigh(), red(), 2);
    }

    public static void button(SpriteBatch b, int x, int y, int w, int h, ButtonStyle style, boolean pressed) {
        try {
            CFG.drawIronButtonBg(b, x, y, w, h, buttonColor(style), pressed);
        } catch (Throwable t) {
            box(b, x, y, w, h, buttonColor(style), border());
        }
    }

    public static void closeButton(SpriteBatch b, int x, int y, int w, int h) {
        try {
            CFG.drawCloseButton(b, x, y, w, h);
        } catch (Throwable t) {
            box(b, x, y, w, h, red(), border());
            text(b, Font.MAIN, "x", x + w / 2f - 4, y + 2, textOnAccent());
        }
    }

    public static void separator(SpriteBatch b, int x, int y, int w) {
        try {
            CFG.drawLineSeparator(b, x, y, w, separator());
        } catch (Throwable t) {
            rect(b, x, y, w, 1, separator());
        }
    }

    public static void row(SpriteBatch b, int x, int y, int w, int h, Color tint) {
        try {
            CFG.drawRowBgTextured(b, x, y, w, h, tint);
        } catch (Throwable t) {
            rect(b, x, y, w, h, tint);
        }
    }

    public static void shadow(SpriteBatch b, int x, int y, int w, int h) {
        try {
            CFG.drawShadow(b, x, y, w, h, 1f);
        } catch (Throwable ignored) {
            // shadows are decoration only
        }
    }

    // ---- sound ----

    public static void click() {
        try {
            SoundManager.getInstance().play(SoundManager.SFX.BUTTON_CLICK);
        } catch (Throwable ignored) {
            // no sound is fine
        }
    }
}
