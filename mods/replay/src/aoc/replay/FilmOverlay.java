package aoc.replay;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.g2d.freetype.FreeTypeFontGenerator;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.utils.Disposable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The film's own layer over the game's map: letterbox bars with the date and the focus
 * country's economy, news captions, and a title card at the start. Drawn y-down in window
 * pixels, straight after the game renders the cinematic view.
 */
final class FilmOverlay implements Disposable {
    static final float TITLE_TIME = 4.5f, CAPTION_TIME = 4f;
    private static final Color WAR_RED = new Color(1f, 0.35f, 0.18f, 1f);
    private static final Color GOLD = new Color(0.95f, 0.78f, 0.38f, 1f);
    private static final Color ALLY_BLUE = new Color(0.45f, 0.7f, 1f, 1f);

    /** A caption on screen; age counts film seconds. */
    static final class Caption {
        final String text;
        final ReplayState.EventType type;
        float age;

        Caption(String text, ReplayState.EventType type) {
            this.text = text;
            this.type = type;
        }
    }

    private final SpriteBatch batch = new SpriteBatch(1000);
    private final Matrix4 proj = new Matrix4();
    private final GlyphLayout layout = new GlyphLayout();
    private final Texture white;
    private final List<FreeTypeFontGenerator> generators = new ArrayList<>();
    private BitmapFont titleFont, titleAltFont, captionFont, smallFont, tinyFont;
    private int fontHeight = -1;

    FilmOverlay() {
        Pixmap pm = new Pixmap(2, 2, Pixmap.Format.RGBA8888);
        pm.setColor(Color.WHITE);
        pm.fill();
        white = new Texture(pm);
        pm.dispose();
    }

    static float barHeight(int H) { return H * 0.08f; }

    static float cardHeight(int H) { return H * 0.11f; }

    /** {@code filmTime}: seconds of film so far (drives the title card). */
    void draw(int W, int H, ReplayState st, int focus, List<Caption> captions, float filmTime, CountryCard card,
              List<TroopBadges.Badge> badges) {
        ensureFonts(H);
        proj.setToOrtho(0, W, H, 0, -1f, 1f);
        batch.setProjectionMatrix(proj);
        Gdx.gl.glEnable(GL20.GL_BLEND);
        batch.begin();
        drawBadges(H, st, badges);
        float barH = barHeight(H), cardH = cardHeight(H), pad = W * 0.035f;
        batch.setColor(0f, 0f, 0f, 0.92f);
        batch.draw(white, 0, 0, W, barH);
        batch.draw(white, 0, H - cardH, W, cardH);

        Replay r = st.replay;
        String date = r.frames.get(st.frame()).date();
        BitmapFont df = titleFor(date);
        df.getData().setScale(0.6f);
        df.setColor(1f, 1f, 1f, 0.95f);
        layout.setText(df, date);
        df.draw(batch, date, pad, (barH - layout.height) / 2f);
        df.getData().setScale(1f);
        smallFont.setColor(1f, 1f, 1f, 0.5f);
        String title = r.title.toUpperCase(Locale.ROOT);
        layout.setText(smallFont, title);
        smallFont.draw(batch, title, W - pad - layout.width, (barH - layout.height) / 2f);

        drawCard(W, H, cardH, pad, card);
        drawCaptions(W, H, cardH, captions);
        if (filmTime < TITLE_TIME) drawTitle(W, H, st, focus, filmTime);
        batch.end();
        batch.setColor(Color.WHITE);
    }

    /** Bottom bar: flag, name and subtitle on the left, a row of labelled stats on the right. */
    private void drawCard(int W, int H, float cardH, float pad, CountryCard card) {
        float y0 = H - cardH;
        float flagH = cardH * 0.46f, flagW = flagH * 1.5f;
        float fx = pad, fy = y0 + (cardH - flagH) / 2f;
        boolean drewFlag = false;
        if (card.flagImage() >= 0) {
            try {
                ru.ius.david.cgs.images.Image img = ru.ius.david.cgs.images.ImageManager.getImage(card.flagImage());
                if (img != null) {
                    batch.setColor(Color.WHITE);
                    img.draw(batch, Math.round(fx), Math.round(fy), Math.round(flagW), Math.round(flagH));
                    drewFlag = true;
                }
            } catch (Throwable ignored) {
                // fall back to a plain frame
            }
        }
        batch.setColor(1f, 1f, 1f, drewFlag ? 0.35f : 0.8f);
        frame(fx, fy, flagW, flagH);

        // Stats first (right-aligned, each column as wide as its text), so the left side knows its room.
        List<String[]> stats = card.stats();
        String growth = Double.isNaN(card.growth()) ? null : String.format(Locale.ROOT, " %+.1f%%", card.growth());
        float gap = W * 0.022f;
        float[] widths = new float[stats.size()];
        float total = 0;
        for (int i = 0; i < stats.size(); i++) {
            layout.setText(labelFont(), stats.get(i)[0].toUpperCase(Locale.ROOT));
            float lw = layout.width;
            layout.setText(captionFont, stats.get(i)[1]);
            float vw = layout.width;
            if (i == 0 && growth != null) {
                layout.setText(smallFont, growth);
                vw += layout.width;
            }
            widths[i] = Math.max(lw, vw);
            total += widths[i] + (i > 0 ? gap : 0);
        }
        float statsLeft = W - pad - total;
        float labelY = y0 + cardH * 0.26f, valueY = y0 + cardH * 0.48f;
        float cx = statsLeft;
        for (int i = 0; i < stats.size(); i++) {
            String[] st = stats.get(i);
            labelFont().setColor(1f, 1f, 1f, 0.5f);
            labelFont().draw(batch, st[0].toUpperCase(Locale.ROOT), cx, labelY);
            captionFont.setColor(Color.WHITE);
            captionFont.draw(batch, st[1], cx, valueY);
            if (i == 0 && growth != null) {
                layout.setText(captionFont, st[1]);
                smallFont.setColor(card.growth() >= 0 ? new Color(0.45f, 0.9f, 0.5f, 1f) : new Color(1f, 0.45f, 0.4f, 1f));
                smallFont.draw(batch, growth, cx + layout.width, valueY + layout.height * 0.3f);
            }
            cx += widths[i] + gap;
        }

        // Name and subtitle, cut short where the stats begin.
        float tx = fx + flagW + W * 0.012f;
        float room = statsLeft - gap - tx;
        String name = fit(captionFont, card.name(), room);
        captionFont.setColor(Color.WHITE);
        layout.setText(captionFont, name);
        float nameH = layout.height;
        String sub = card.subtitle() == null || card.subtitle().isEmpty() ? null : fit(smallFont, card.subtitle(), room);
        float textTop = y0 + (cardH - nameH - (sub != null ? H * 0.028f : 0f)) / 2f;
        captionFont.draw(batch, name, tx, textTop);
        if (sub != null) {
            smallFont.setColor(1f, 1f, 1f, 0.6f);
            smallFont.draw(batch, sub, tx, textTop + nameH + H * 0.012f);
        }
    }

    /** {@code text}, shortened with an ellipsis to fit {@code width} pixels. */
    private String fit(BitmapFont font, String text, float width) {
        layout.setText(font, text);
        if (layout.width <= width) return text;
        String t = text;
        while (t.length() > 1) {
            t = t.substring(0, t.length() - 1).stripTrailing();
            layout.setText(font, t + "…");
            if (layout.width <= width) return t + "…";
        }
        return "";
    }

    /** Small caps-style labels above the stat values. */
    private BitmapFont labelFont() { return tinyFont; }

    /** One-pixel frame around a rectangle. */
    private void frame(float x, float y, float w, float h) {
        batch.draw(white, x, y, w, 1);
        batch.draw(white, x, y + h - 1, w, 1);
        batch.draw(white, x, y, 1, h);
        batch.draw(white, x + w - 1, y, 1, h);
    }

    /** Troop counts: a dark tag in the country's colour above each group of its army flags. */
    private void drawBadges(int H, ReplayState st, List<TroopBadges.Badge> badges) {
        if (badges == null) return;
        float padX = H * 0.006f, padY = H * 0.003f, strip = H * 0.004f, lift = H * 0.03f;
        List<float[]> placed = new ArrayList<>();
        for (TroopBadges.Badge b : badges) {
            String text = TroopBadges.format(b.troops());
            layout.setText(tinyFont, text);
            float w = layout.width + padX * 2 + strip, h = layout.height + padY * 2 + H * 0.004f;
            float x = b.x() - w / 2f, y = b.y() - lift - h;
            boolean overlaps = false;
            for (float[] r : placed) {
                if (x < r[0] + r[2] && x + w > r[0] && y < r[1] + r[3] && y + h > r[1]) {
                    overlaps = true;
                    break;
                }
            }
            if (overlaps) continue; // bigger groups were placed first
            placed.add(new float[]{x, y, w, h});
            Replay.Country c = st.replay.countries.get(b.owner());
            Color col = c != null ? new Color(c.rgba()) : Color.GRAY;
            batch.setColor(0.06f, 0.05f, 0.04f, 0.82f);
            batch.draw(white, x, y, w, h);
            batch.setColor(col.r, col.g, col.b, 1f);
            batch.draw(white, x, y, strip, h);
            tinyFont.setColor(1f, 1f, 1f, 0.95f);
            tinyFont.draw(batch, text, x + strip + padX, y + (h - layout.height) / 2f);
        }
        batch.setColor(Color.WHITE);
    }

    private void drawCaptions(int W, int H, float barH, List<Caption> captions) {
        float y = H - barH - H * 0.03f;
        for (int i = captions.size() - 1; i >= 0; i--) {
            Caption c = captions.get(i);
            float a = Math.min(1f, Math.min(c.age / 0.4f, (CAPTION_TIME - c.age) / 0.7f));
            if (a <= 0) continue;
            layout.setText(captionFont, c.text);
            float cy = y - layout.height;
            float cx = (W - layout.width) / 2f;
            Color accent = switch (c.type) {
                case WAR -> WAR_RED;
                case ALLIANCE -> ALLY_BLUE;
                case CITY, NATION -> GOLD;
                default -> Color.WHITE;
            };
            batch.setColor(0f, 0f, 0f, 0.6f * a);
            batch.draw(white, cx - H * 0.02f, cy - H * 0.012f, layout.width + H * 0.04f, layout.height + H * 0.024f);
            batch.setColor(accent.r, accent.g, accent.b, a);
            batch.draw(white, cx - H * 0.02f, cy - H * 0.012f, H * 0.005f, layout.height + H * 0.024f);
            captionFont.setColor(1f, 1f, 1f, a);
            captionFont.draw(batch, c.text, cx, cy);
            y = cy - H * 0.035f;
        }
    }

    private void drawTitle(int W, int H, ReplayState st, int focus, float t) {
        float dark = 1f - smooth(Math.max(0f, (t - 1.5f) / (TITLE_TIME - 1.5f)));
        batch.setColor(0f, 0f, 0f, 0.75f * dark);
        batch.draw(white, 0, 0, W, H);
        float a = Math.min(1f, t / 0.8f) * Math.min(1f, (TITLE_TIME - t) / 0.9f);
        String big = st.replay.title;
        BitmapFont tf = titleFor(big);
        tf.setColor(1f, 1f, 1f, a);
        layout.setText(tf, big);
        float y = H * 0.38f;
        tf.draw(batch, big, (W - layout.width) / 2f, y);
        y += layout.height + H * 0.04f;
        batch.setColor(GOLD.r, GOLD.g, GOLD.b, a);
        batch.draw(white, W / 2f - W * 0.06f, y - H * 0.015f, W * 0.12f, 2f);
        String name = st.replay.countryName(focus);
        captionFont.setColor(GOLD.r, GOLD.g, GOLD.b, a);
        layout.setText(captionFont, name);
        captionFont.draw(batch, name, (W - layout.width) / 2f, y);
    }

    private BitmapFont titleFor(String s) {
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) > 0x24F) return titleAltFont;
        return titleFont;
    }

    private void ensureFonts(int H) {
        if (Math.abs(H - fontHeight) < 2) return;
        disposeFonts();
        titleFont = font("newsreader.ttf", Math.round(H * 0.075f));
        titleAltFont = font("noto-serif.ttf", Math.round(H * 0.07f));
        captionFont = font("noto-serif.ttf", Math.round(H * 0.03f));
        smallFont = font("noto-serif.ttf", Math.round(H * 0.021f));
        tinyFont = font("noto-serif.ttf", Math.round(H * 0.016f));
        fontHeight = H;
    }

    private BitmapFont font(String file, int size) {
        FileHandle fh = Gdx.files.internal(file);
        if (!fh.exists()) fh = Gdx.files.internal("arial.ttf");
        FreeTypeFontGenerator gen = new FreeTypeFontGenerator(fh);
        generators.add(gen);
        FreeTypeFontGenerator.FreeTypeFontParameter p = new FreeTypeFontGenerator.FreeTypeFontParameter();
        p.size = Math.max(8, size);
        p.flip = true;
        p.incremental = true;
        p.minFilter = Texture.TextureFilter.Linear;
        p.magFilter = Texture.TextureFilter.Linear;
        BitmapFont f = gen.generateFont(p);
        f.setUseIntegerPositions(false);
        return f;
    }

    private void disposeFonts() {
        for (BitmapFont f : new BitmapFont[]{titleFont, titleAltFont, captionFont, smallFont, tinyFont}) if (f != null) f.dispose();
        for (FreeTypeFontGenerator g : generators) g.dispose();
        generators.clear();
    }

    private static float smooth(float t) { return t * t * (3f - 2f * t); }

    static String compact(double v) {
        double a = Math.abs(v);
        if (a >= 1e9) return String.format(Locale.ROOT, "%.1f B", v / 1e9);
        if (a >= 1e6) return String.format(Locale.ROOT, "%.1f M", v / 1e6);
        if (a >= 1e3) return String.format(Locale.ROOT, "%.1f k", v / 1e3);
        return String.format(Locale.ROOT, "%.0f", v);
    }

    @Override
    public void dispose() {
        disposeFonts();
        batch.dispose();
        white.dispose();
    }
}
