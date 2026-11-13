package aoc.replay;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.InputAdapter;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.math.Matrix4;

import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Plays a replay film full screen inside the game: ffmpeg decodes it to raw frames on a
 * background thread, the render thread uploads one per film frame. Takes all input while open:
 * Space pauses, Left/Right jump 10 seconds, Esc closes.
 */
final class VideoPlayer extends InputAdapter {
    private static final int FPS = 30;

    private String ffmpeg;
    private File file;
    private float duration;
    private int vw, vh;
    private Process decoder;
    private Thread reader;
    private BlockingQueue<byte[]> frames;
    private volatile boolean ended;
    private Pixmap pix;
    private Texture tex;
    private SpriteBatch batch;
    private Texture white;
    private final Matrix4 proj = new Matrix4();
    private final GlyphLayout layout = new GlyphLayout();
    private float position, startAt, clock, hintTime;
    private boolean paused;
    private String error;

    boolean isOpen() { return file != null; }

    void open(File film, String ffmpegPath) {
        close();
        file = film;
        ffmpeg = ffmpegPath;
        duration = probeDuration();
        int sw = Gdx.graphics.getBackBufferWidth(), sh = Gdx.graphics.getBackBufferHeight();
        // Decode at most 1080 lines; the GPU scales it up to the window.
        float scale = Math.min(1f, 1080f / sh);
        vw = Math.round(sw * scale) & ~1;
        vh = Math.round(sh * scale) & ~1;
        pix = new Pixmap(vw, vh, Pixmap.Format.RGBA8888);
        tex = new Texture(pix);
        tex.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
        batch = new SpriteBatch(16);
        Pixmap pm = new Pixmap(2, 2, Pixmap.Format.RGBA8888);
        pm.setColor(Color.WHITE);
        pm.fill();
        white = new Texture(pm);
        pm.dispose();
        paused = false;
        hintTime = 4f;
        startDecoder(0f);
    }

    void close() {
        stopDecoder();
        if (tex != null) tex.dispose();
        if (pix != null) pix.dispose();
        if (batch != null) batch.dispose();
        if (white != null) white.dispose();
        tex = null;
        pix = null;
        batch = null;
        white = null;
        file = null;
    }

    private void startDecoder(float at) {
        stopDecoder();
        startAt = Math.max(0f, at);
        position = startAt;
        clock = 0;
        ended = false;
        error = null;
        List<String> cmd = List.of(ffmpeg, "-loglevel", "error", "-ss", String.format(Locale.ROOT, "%.2f", startAt),
                "-i", file.getAbsolutePath(), "-an",
                "-vf", "fps=" + FPS + ",scale=" + vw + ":" + vh + ":force_original_aspect_ratio=decrease,pad=" + vw + ":" + vh
                        + ":(ow-iw)/2:(oh-ih)/2",
                "-f", "rawvideo", "-pix_fmt", "rgba", "-");
        try {
            decoder = new ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        } catch (IOException e) {
            error = "Cannot play: " + e.getMessage();
            return;
        }
        frames = new ArrayBlockingQueue<>(6);
        Process p = decoder;
        BlockingQueue<byte[]> q = frames;
        reader = new Thread(() -> {
            try (DataInputStream in = new DataInputStream(p.getInputStream())) {
                while (true) {
                    byte[] f = new byte[vw * vh * 4];
                    in.readFully(f);
                    while (!q.offer(f, 200, TimeUnit.MILLISECONDS)) {
                        if (!p.isAlive() && q.remainingCapacity() == 0) return;
                    }
                }
            } catch (Exception endOfFilm) {
                ended = true;
            }
        }, "replay-video-decoder");
        reader.setDaemon(true);
        reader.start();
    }

    private void stopDecoder() {
        if (decoder != null) decoder.destroy();
        if (reader != null) reader.interrupt();
        decoder = null;
        reader = null;
    }

    void render(float delta, BitmapFont font) {
        if (file == null) return;
        if (!paused) clock += delta;
        hintTime -= delta;
        // Show the frame that belongs to the current time.
        int wanted = (int) (clock * FPS);
        int shown = (int) ((position - startAt) * FPS);
        while (!paused && shown < wanted && frames != null) {
            byte[] f = frames.poll();
            if (f == null) break;
            pix.getPixels().clear();
            pix.getPixels().put(f).flip();
            tex.draw(pix, 0, 0);
            shown++;
            position = startAt + shown / (float) FPS;
        }
        if (ended && (frames == null || frames.isEmpty())) paused = true;

        int W = Gdx.graphics.getBackBufferWidth(), H = Gdx.graphics.getBackBufferHeight();
        Gdx.gl.glViewport(0, 0, W, H);
        Gdx.gl.glClearColor(0, 0, 0, 1);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
        proj.setToOrtho(0, W, H, 0, -1, 1);
        batch.setProjectionMatrix(proj);
        batch.begin();
        batch.setColor(Color.WHITE);
        batch.draw(tex, 0, 0, W, H, 0f, 0f, 1f, 1f);
        if (paused || hintTime > 0 || error != null) {
            String hint = error != null ? error
                    : ended && paused ? "The end   -   Left: back 10 s   Esc: close"
                    : (paused ? "Paused   -   " : "") + "Space: pause   Left/Right: 10 s   Esc: close   " + time(position) + " / " + time(duration);
            layout.setText(font, hint);
            batch.setColor(0f, 0f, 0f, 0.6f);
            batch.draw(white, (W - layout.width) / 2f - 16, H * 0.06f - 10, layout.width + 32, layout.height + 20);
            font.setColor(Color.WHITE);
            font.draw(batch, hint, (W - layout.width) / 2f, H * 0.06f);
            float p = duration > 0 ? Math.min(1f, position / duration) : 0f;
            batch.setColor(1f, 1f, 1f, 0.2f);
            batch.draw(white, 0, H - 4, W, 4);
            batch.setColor(0.95f, 0.78f, 0.38f, 0.9f);
            batch.draw(white, 0, H - 4, W * p, 4);
        }
        batch.end();
    }

    @Override
    public boolean keyDown(int key) {
        if (file == null) return false;
        switch (key) {
            case Input.Keys.ESCAPE -> close();
            case Input.Keys.SPACE -> {
                if (ended && paused) startDecoder(0f);
                else paused = !paused;
                hintTime = 2f;
            }
            case Input.Keys.LEFT -> {
                paused = false;
                startDecoder(position - 10f);
            }
            case Input.Keys.RIGHT -> {
                paused = false;
                startDecoder(duration > 0 ? Math.min(Math.max(0f, duration - 1f), position + 10f) : position + 10f);
            }
            default -> { }
        }
        return true;
    }

    @Override public boolean keyUp(int key) { return file != null; }
    @Override public boolean keyTyped(char c) { return file != null; }

    @Override
    public boolean touchDown(int x, int y, int pointer, int button) {
        if (file == null) return false;
        paused = !paused;
        hintTime = 2f;
        return true;
    }

    @Override public boolean touchUp(int x, int y, int pointer, int button) { return file != null; }
    @Override public boolean touchDragged(int x, int y, int pointer) { return file != null; }
    @Override public boolean mouseMoved(int x, int y) { return file != null; }
    @Override public boolean scrolled(float ax, float ay) { return file != null; }

    /** Seconds, from ffmpeg's "Duration: 00:01:23.45" (0 when unknown, e.g. a film still being made). */
    private float probeDuration() {
        try {
            Process p = new ProcessBuilder(ffmpeg, "-hide_banner", "-i", file.getAbsolutePath()).redirectErrorStream(true).start();
            String text = new String(p.getInputStream().readAllBytes());
            p.waitFor(10, TimeUnit.SECONDS);
            Matcher m = Pattern.compile("Duration: (\\d+):(\\d+):(\\d+(?:\\.\\d+)?)").matcher(text);
            if (m.find()) return Integer.parseInt(m.group(1)) * 3600 + Integer.parseInt(m.group(2)) * 60 + Float.parseFloat(m.group(3));
        } catch (Exception ignored) {
            // unknown length
        }
        return 0f;
    }

    private static String time(float s) {
        int t = Math.max(0, (int) s);
        return String.format(Locale.ROOT, "%d:%02d", t / 60, t % 60);
    }
}
