package aoc.api;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.g2d.freetype.FreeTypeFontGenerator;
import com.badlogic.gdx.utils.Align;

import java.util.HashMap;
import java.util.Map;

/**
 * Screen-space drawing on top of the game. Coordinates are in window pixels with the
 * origin at the bottom-left, like libGDX; use {@link #flipY} for input coordinates.
 * The batch opens on the first draw call and the loader closes it after each mod.
 */
public final class Overlay {
    private final SpriteBatch batch = new SpriteBatch();
    private final Texture white;
    private final FreeTypeFontGenerator generator;
    private final Map<Integer, BitmapFont> fonts = new HashMap<>();
    private final GlyphLayout layout = new GlyphLayout();
    private boolean active;

    public Overlay() {
        Pixmap px = new Pixmap(1, 1, Pixmap.Format.RGBA8888);
        px.setColor(Color.WHITE);
        px.fill();
        white = new Texture(px);
        px.dispose();
        // The game ships Arial; glyphs are generated on demand, so any script it covers works.
        generator = new FreeTypeFontGenerator(Gdx.files.internal("arial.ttf"));
    }

    /** The batch, opened if needed. */
    public SpriteBatch batch() {
        ensure();
        return batch;
    }

    public float width() { return Gdx.graphics.getWidth(); }

    public float height() { return Gdx.graphics.getHeight(); }

    /** Converts an input event's y (top-left origin) to overlay y. */
    public float flipY(int screenY) { return height() - screenY; }

    /** Arial at the given pixel size. */
    public BitmapFont font(int size) {
        return fonts.computeIfAbsent(size, s -> {
            FreeTypeFontGenerator.FreeTypeFontParameter p = new FreeTypeFontGenerator.FreeTypeFontParameter();
            p.size = s;
            p.incremental = true;
            p.minFilter = Texture.TextureFilter.Linear;
            p.magFilter = Texture.TextureFilter.Linear;
            return generator.generateFont(p);
        });
    }

    public void rect(float x, float y, float w, float h, Color color) {
        ensure();
        batch.setColor(color);
        batch.draw(white, x, y, w, h);
        batch.setColor(Color.WHITE);
    }

    public void outline(float x, float y, float w, float h, float thickness, Color color) {
        rect(x, y, w, thickness, color);
        rect(x, y + h - thickness, w, thickness, color);
        rect(x, y, thickness, h, color);
        rect(x + w - thickness, y, thickness, h, color);
    }

    /** Single line, {@code y} is the top of the text. Returns the drawn width. */
    public float text(BitmapFont font, CharSequence s, float x, float y, Color color) {
        ensure();
        font.setColor(color);
        layout.setText(font, s);
        font.draw(batch, layout, x, y);
        return layout.width;
    }

    /** Wrapped text inside {@code width}; {@code y} is the top. Returns the height used. */
    public float wrapped(BitmapFont font, CharSequence s, float x, float y, float width, int align, Color color) {
        ensure();
        font.setColor(color);
        layout.setText(font, s, color, width, align, true);
        font.draw(batch, layout, x, y);
        return layout.height;
    }

    /** Height {@link #wrapped} would use, without drawing. */
    public float measureWrapped(BitmapFont font, CharSequence s, float width) {
        layout.setText(font, s, Color.WHITE, width, Align.left, true);
        return layout.height;
    }

    public float measure(BitmapFont font, CharSequence s) {
        layout.setText(font, s);
        return layout.width;
    }

    /** Restricts drawing to a rectangle until {@link #unclip}. Does not nest. */
    public void clip(float x, float y, float w, float h) {
        ensure();
        batch.flush();
        float sx = Gdx.graphics.getBackBufferWidth() / width();
        float sy = Gdx.graphics.getBackBufferHeight() / height();
        Gdx.gl.glEnable(GL20.GL_SCISSOR_TEST);
        Gdx.gl.glScissor(Math.round(x * sx), Math.round(y * sy), Math.round(w * sx), Math.round(h * sy));
    }

    public void unclip() {
        if (!active) return;
        batch.flush();
        Gdx.gl.glDisable(GL20.GL_SCISSOR_TEST);
    }

    // ---- loader only ----

    private void ensure() {
        if (!active) begin();
    }

    private void begin() {
        active = true;
        Gdx.gl.glViewport(0, 0, Gdx.graphics.getBackBufferWidth(), Gdx.graphics.getBackBufferHeight());
        Gdx.gl.glEnable(GL20.GL_BLEND);
        batch.getProjectionMatrix().setToOrtho2D(0, 0, width(), height());
        batch.begin();
        batch.enableBlending();
    }

    /** Closes the batch if a mod drew anything. */
    public void end() {
        if (!active) return;
        active = false;
        batch.end();
        Gdx.gl.glDisable(GL20.GL_SCISSOR_TEST);
    }
}
