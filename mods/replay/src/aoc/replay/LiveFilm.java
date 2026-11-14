package aoc.replay;

import aoc.api.ModContext;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.utils.BufferUtils;
import ru.ius.david.cgs.game.GameManager;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Films the game while it is played. Before each game frame (now and then, paced by game time)
 * the game draws its own map once more from the {@link Director}'s camera, without its
 * interface; the {@link FilmOverlay} adds bars and captions; the pixels are read back and
 * streamed to ffmpeg, which writes a fragmented MP4 (playable even if the game is closed
 * mid-match). The game's normal frame then paints over the screen, so the player never sees it.
 */
final class LiveFilm {
    /** Suffix while a film is being made; on finishing it is rewritten as a normal MP4. */
    static final String PART = ".part.mp4";

    private final ModContext ctx;
    private final Recorder recorder;
    /** Created on the render thread when filming starts (no GL while mods load). */
    private FilmOverlay overlay;

    private Object session;
    private Director director;
    private int focus;
    private Process ffmpeg;
    private String ffmpegPath;
    private Thread writer;
    /** Put on the queue to end the film. */
    private static final byte[] END = new byte[0];

    private BlockingQueue<byte[]> queue;
    private BlockingQueue<byte[]> spare;
    private volatile boolean writerDone;
    private volatile String error;
    private final StringBuilder ffmpegLog = new StringBuilder();
    private ByteBuffer read;
    private int w, h;
    private File out;

    private final List<FilmOverlay.Caption> captions = new ArrayList<>();
    private final List<FilmOverlay.Caption> pending = new ArrayList<>();
    private boolean urgent;
    private int lastDay = Integer.MIN_VALUE;
    private float sinceDayChange, dayRate, sinceCapture, filmTime;
    /** The game moves the date in steps (several days per tick when sped up): typical step and gap between steps. */
    private float daysPerStep, stepSeconds;
    /** Game time (days, fractional) the film has covered so far; NaN before the first frame. */
    private float filmDay = Float.NaN;
    /**
     * Most film frames rendered during one game frame. At war a slow game frame may need more than one
     * film frame; each is rendered on its own (the camera moves on between them) instead of repeating
     * one picture, which made the film stutter. Beyond this the film skips ahead.
     */
    private static final int MAX_PER_GAME_FRAME = 2;
    /**
     * Share of the game thread's time the film may use. Filming as many frames as a slow war asks for
     * cost the game most of its frame rate (and its speed, since the game simulates on the same thread);
     * within this budget the film skips ahead instead.
     */
    private static final float FILM_SHARE = 0.25f, FILM_SHARE_AT_WAR = 0.4f;
    private float captureMs = 10f, budgetMs;
    /** Reads frames back without waiting for the graphics card; null falls back to glReadPixels. */
    private AsyncReadback readback;
    /** Time the self-test spent saving pictures during the last capture (not the film's cost). */
    private long testNanos;
    private int frames, dropped;
    private long slowCaptures;
    /** Developer self-test: saves every n-th film frame as PNG and logs timings; null normally. */
    SelfTest selfTest;
    private int captures;

    /** Why filming is off for this match (shown in the Replays window), or null. */
    volatile String status;

    LiveFilm(ModContext ctx, Recorder recorder) {
        this.ctx = ctx;
        this.recorder = recorder;
        recorder.onFrame = this::onRecordedFrame;
        recorder.onRename = (key, text) -> {
            if (out == null) return;
            pending.add(0, new FilmOverlay.Caption(text, ReplayState.EventType.NATION));
            if (key == focus && director != null) director.showHome(); // our country became a new nation: show it
        };
    }

    boolean filming() { return out != null; }

    float filmSeconds() { return filmTime; }

    File file() { return out; }

    /**
     * Called before every game frame.
     * @param ffmpegPath   null when ffmpeg is missing (no filming)
     * @param peaceDays    game days per second of film while the focus country is at peace
     * @param warDays      game days per second of film while it (or its alliance) is at war
     * @param maxPerSec    most film frames rendered per real second
     */
    void beforeFrame(float delta, String ffmpegPath, File folder, int fps, int maxHeight, float peaceDays, float warDays, int maxPerSec) {
        ru.ius.david.cgs.MapScreen map = ru.ius.david.cgs.MapScreen.getActiveInstance();
        ReplayState st = recorder.live();
        // Not while the player is still choosing a country: the game's 'player' is a placeholder then.
        if (map == null || !ctx.game().inGame() || st == null || map.isCountrySelectMode()) {
            if (map == null) finish();
            return;
        }
        if (map != session) {
            finish();
            session = map;
            if (ffmpegPath == null) {
                status = "ffmpeg not found, so this game is not filmed.";
                return;
            }
            start(st, ffmpegPath, folder, fps, maxHeight);
        }
        if (out == null) return;
        if (error != null) {
            ctx.log("Filming stopped: " + error);
            status = "Filming stopped: " + error;
            finish();
            return;
        }

        // Measure how fast game days pass, to estimate game time between the game's date steps. Sped up,
        // the date jumps several days a few times a second; spreading the film's frames evenly over a step
        // (instead of waiting for the next one) keeps the film smooth.
        GameManager gm = GameManager.getInstance();
        int day = gm.getGameDays();
        if (lastDay == Integer.MIN_VALUE) lastDay = day;
        if (gm.isGamePaused() || gm.getPlaySpeed() <= 0) {
            sinceDayChange = 0; // paused (also by pop-ups): the film pauses too, and the pause is not a slow step
            return;
        }
        sinceDayChange += delta;
        if (day != lastDay) {
            int dd = day - lastDay;
            // A step much slower than usual is a stall (a heavy game tick), not the game's pace.
            boolean stall = stepSeconds > 0 && sinceDayChange > stepSeconds * 4f;
            if (dd > 0 && dd < 400 && !stall) {
                daysPerStep = daysPerStep == 0 ? dd : daysPerStep * 0.8f + dd * 0.2f;
                stepSeconds = stepSeconds == 0 ? sinceDayChange : stepSeconds * 0.8f + sinceDayChange * 0.2f;
                dayRate = daysPerStep / Math.max(stepSeconds, 1e-3f);
            }
            lastDay = day;
            sinceDayChange = 0;
        }
        sinceCapture += delta;
        if (dayRate <= 0) return; // no pace measured yet
        float gameTime = day + Math.min(Math.max(1f, daysPerStep) - 0.001f, sinceDayChange * dayRate);
        if (Float.isNaN(filmDay)) filmDay = gameTime;

        // Peace goes by quickly, wars play slowly however fast the game runs: the film owes one frame
        // per 'days per frame' of game time. At war several film frames may be rendered in one game
        // frame, each its own picture, within the per-second cap and the time budget.
        boolean war = atWar(st);
        limitWarSpeed(gm, ownWar(st));
        // Wars may take a bigger share of the game's time: they need the most film frames.
        budgetMs = Math.min(budgetMs + delta * 1000f * (war ? FILM_SHARE_AT_WAR : FILM_SHARE), 50f);
        float perFrame = (war ? warDays : peaceDays) / fps;
        float owed = (gameTime - filmDay) / perFrame;
        float allowed = sinceCapture * Math.max(1, maxPerSec);
        boolean overBudget = budgetMs <= 0;
        if (selfTest != null) selfTest.pacing(delta, war, owed < 1f, allowed < 1f, overBudget, gameTime);
        if (owed < 1f || allowed < 1f || overBudget) return;
        int n = 1;
        if (war) n = (int) Math.min(Math.min(owed, allowed), Math.min(MAX_PER_GAME_FRAME, 1f + budgetMs / Math.max(1f, captureMs)));
        // Keep the remainder, so the cap is met evenly whatever the game's frame rate.
        sinceCapture = Math.max(0f, Math.min(sinceCapture - n / (float) Math.max(1, maxPerSec), 1f / Math.max(1, maxPerSec)));
        filmDay += n * perFrame;
        if (gameTime - filmDay > perFrame) filmDay = gameTime - perFrame; // too far behind: skip ahead
        for (int i = 0; i < n; i++) {
            long t0 = System.nanoTime();
            testNanos = 0;
            capture(st, fps, n);
            budgetMs -= (System.nanoTime() - t0 - testNanos) / 1e6f;
        }
    }

    private int testFocus = -1;

    /** The player's country; the self-test may follow the attacker in the biggest war instead. */
    private int playerOf(ReplayState st) {
        if (selfTest == null || !selfTest.followBiggestWar()) return st.player;
        ReplayState.Front big = null;
        for (ReplayState.Front fr : st.fronts) {
            if (fr.war.attacker() == testFocus) return testFocus; // stay with it while it is at war
            if (big == null || fr.cells.size() > big.cells.size()) big = fr;
        }
        if (big != null) testFocus = big.war.attacker();
        return testFocus != -1 ? testFocus : st.player;
    }

    /** Most game speed while the focus country itself fights (0 = no limit); set from the mod's settings. */
    int warSpeedLimit;
    private int speedBeforeWar;
    private boolean warNoticeShown;

    /**
     * A live film can only show the frames the game draws: a war played at x16 is over in seconds and no
     * film speed setting brings it back. So while the player's own country is at war the game itself is
     * held at the limit, and gets the player's speed back when the war ends.
     */
    private void limitWarSpeed(GameManager gm, boolean ownWar) {
        if (selfTest != null) return;
        if (warSpeedLimit > 0 && ownWar) {
            int speed = gm.getPlaySpeed();
            if (speed > warSpeedLimit) {
                if (speedBeforeWar == 0) speedBeforeWar = speed;
                gm.setPlaySpeed(warSpeedLimit);
                if (!warNoticeShown) {
                    warNoticeShown = true;
                    ctx.ui().toast("Replay: at war, so the game runs at x" + warSpeedLimit
                            + " for the film (change it in Code Mods > Replays)", 6);
                }
            }
        } else if (speedBeforeWar > 0) {
            if (!ownWar && gm.getPlaySpeed() == warSpeedLimit) gm.setPlaySpeed(speedBeforeWar);
            speedBeforeWar = 0;
            warNoticeShown = false;
        } else if (!ownWar) {
            warNoticeShown = false;
        }
    }

    /** The focus country itself is the attacker or defender of a war. */
    private boolean ownWar(ReplayState st) {
        for (Replay.War w : st.replay.frames.get(st.frame()).wars()) {
            if (w.attacker() == focus || w.defender() == focus) return true;
        }
        return false;
    }

    /** The focus country or a member of its alliance is in a war. */
    private boolean atWar(ReplayState st) {
        Set<Integer> side = st.allianceOf(focus);
        for (Replay.War w : st.replay.frames.get(st.frame()).wars()) {
            if (side.contains(w.attacker()) || side.contains(w.defender())) return true;
        }
        return false;
    }

    private void start(ReplayState st, String ffmpegPath, File folder, int fps, int maxHeight) {
        w = Gdx.graphics.getBackBufferWidth();
        h = Gdx.graphics.getBackBufferHeight();
        if (w < 320 || h < 200) return;
        if (overlay == null) overlay = new FilmOverlay();
        focus = playerOf(st) != -1 ? playerOf(st) : st.replay.playerKey;
        director = new Director(st.w, st.h);
        director.snap(st, focus, w / (float) h);
        captions.clear();
        pending.clear();
        filmTime = 0;
        frames = dropped = 0;
        lastDay = Integer.MIN_VALUE;
        dayRate = sinceDayChange = sinceCapture = daysPerStep = stepSeconds = 0;
        filmDay = Float.NaN;
        error = null;
        folder.mkdirs();
        String id = st.replay.scenario.replaceAll("/+$", "");
        id = id.substring(id.lastIndexOf('/') + 1).replaceAll("[^A-Za-z0-9_-]+", "_");
        out = new File(folder, id + "_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm")) + PART);
        int outH = Math.min(maxHeight, h) & ~1;
        // The window is often an odd number of pixels tall: crop the last row instead of resampling (sharper, cheaper).
        String vf = outH == (h & ~1) ? "vflip,crop=" + (w & ~1) + ":" + outH + ":0:0" : "vflip,scale=-2:" + outH + ":flags=lanczos";
        Encoder enc = encoder != null ? encoder : X264;
        List<String> cmd = new ArrayList<>(List.of(ffmpegPath, "-y", "-loglevel", "error",
                "-f", "rawvideo", "-pix_fmt", "rgb0", "-s", w + "x" + h, "-r", String.valueOf(fps), "-i", "-", "-vf", vf));
        cmd.addAll(enc.args());
        cmd.addAll(List.of("-movflags", "+frag_keyframe+empty_moov+default_base_moof", out.getAbsolutePath()));
        this.ffmpegPath = ffmpegPath;
        try {
            ffmpeg = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        } catch (IOException e) {
            status = "Could not start ffmpeg: " + e.getMessage();
            out = null;
            return;
        }
        Process p = ffmpeg;
        lowerPriority(p);
        Thread log = new Thread(() -> drain(p.getInputStream()), "film-ffmpeg-log");
        log.setDaemon(true);
        log.start();
        read = BufferUtils.newByteBuffer(w * h * 4);
        try {
            readback = new AsyncReadback(w, h);
        } catch (Throwable t) {
            readback = null; // no pixel buffers: read synchronously
            ctx.log("Film: reading frames synchronously (" + t + ")");
        }
        budgetMs = 0;
        // Frames waiting for ffmpeg: enough for a short stall of the encoder (about a third of a second).
        queue = new ArrayBlockingQueue<>(16);
        spare = new ArrayBlockingQueue<>(12);
        for (int i = 0; i < 12; i++) spare.add(new byte[w * h * 4]);
        writerDone = false;
        BlockingQueue<byte[]> q = queue;
        BlockingQueue<byte[]> sp = spare;
        writer = new Thread(() -> write(p, q, sp), "film-ffmpeg-writer");
        writer.setDaemon(true);
        writer.start();
        status = null;
        ctx.log("Filming to " + out.getName() + " (" + w + "x" + h + " -> " + outH + "p, " + fps + " fps, " + enc.name() + ")");
        ctx.ui().toast("Filming this game for a replay", 3);
    }

    /** One film frame: camera, the game's map, our overlay, read back, hand to ffmpeg ({@code batch}: frames this game frame). */
    private void capture(ReplayState st, int fps, int batch) {
        if (Gdx.graphics.getBackBufferWidth() != w || Gdx.graphics.getBackBufferHeight() != h) return; // window resized
        byte[] buf = spare.poll();
        if (buf == null) {
            dropped++; // ffmpeg is behind; skip rather than stall the game
            return;
        }
        long t0 = System.nanoTime();
        float dt = 1f / fps;
        filmTime += dt;
        for (FilmOverlay.Caption c : captions) c.age += dt;
        captions.removeIf(c -> c.age > FilmOverlay.CAPTION_TIME);
        while (!pending.isEmpty() && captions.size() < 3) captions.add(pending.remove(0));
        int player = playerOf(st);
        if (player != -1 && player != focus) {
            focus = player; // a nation was formed: follow the new country
            urgent = true;
        }
        director.update(dt, st, focus, w / (float) h, urgent);
        if (selfTest != null) selfTest.camera(director.height, director.cuts, director.shot.name());
        urgent = false;
        if (director.cutCaption != null) {
            captions.add(new FilmOverlay.Caption(director.cutCaption, ReplayState.EventType.WAR));
            director.cutCaption = null;
        }
        float s = st.replay.scale;
        if (!ctx.camera().renderCleanMapView(director.x * s, director.y * s, director.height * s)) {
            spare.add(buf);
            return;
        }
        long t1 = System.nanoTime();
        List<TroopBadges.Badge> badges = TroopBadges.collect(director.x * s, director.y * s, director.height * s, w, h,
                FilmOverlay.barHeight(h), h - FilmOverlay.cardHeight(h));
        overlay.draw(w, h, st, focus, captions, filmTime, CountryCard.of(st, focus), badges);
        long t2 = System.nanoTime();
        boolean ready = true; // buf holds a finished frame
        if (readback != null) {
            readback.start();
            ready = readback.full();
            if (ready) readback.take(buf); // the frame from two captures ago, long finished on the card
        } else {
            readSync(buf);
        }
        long t3 = System.nanoTime();
        captures++;
        captureMs = captureMs * 0.9f + (t3 - t0) / 1e6f * 0.1f;
        if (selfTest != null && captures % selfTest.dumpEvery() == 1) {
            long d0 = System.nanoTime();
            float[] pass = aoc.api.internal.Hooks.filmPassTimes();
            ctx.log(String.format(java.util.Locale.ROOT,
                    "SELFTEST capture %d: total %.0f ms (map %.0f, flags+garrisons %.0f, overlay %.0f, read %.0f), %d this game frame, shot %s, game %d fps",
                    captures, (t3 - t0) / 1e6, pass[0], pass[1], (t2 - t1) / 1e6, (t3 - t2) / 1e6, batch, director.shot,
                    Gdx.graphics.getFramesPerSecond()) + "; badges " + badges.size() + " (" + TroopBadges.debug + ")"
                    + String.format(java.util.Locale.ROOT, "; game clock %.1f days/s in steps of %.1f days every %.2f s", dayRate, daysPerStep, stepSeconds));
            byte[] now = new byte[w * h * 4];
            readSync(now);
            SelfTest.save(now, w, h, new File(selfTest.out, String.format("film_%04d.png", captures)));
            // The same camera without the film's clean-up: what the player's own view looks like there.
            if (ctx.camera().renderMapView(director.x * s, director.y * s, director.height * s)) {
                readSync(now);
                SelfTest.save(now, w, h, new File(selfTest.out, String.format("film_%04d_raw.png", captures)));
            }
            testNanos = System.nanoTime() - d0;
        }
        frames++;
        if (!ready || (selfTest != null && !selfTest.encoding())) spare.add(buf);
        else queue.add(buf);
        if (System.nanoTime() - t0 > 60_000_000L && ++slowCaptures % 50 == 1) {
            ctx.log("Film frames are slow (" + (System.nanoTime() - t0) / 1_000_000 + " ms); lower 'film frames per real second' if the game stutters");
        }
    }

    private void readSync(byte[] dst) {
        read.clear();
        Gdx.gl.glPixelStorei(GL20.GL_PACK_ALIGNMENT, 1);
        Gdx.gl.glReadPixels(0, 0, w, h, GL20.GL_RGBA, GL20.GL_UNSIGNED_BYTE, read);
        read.get(dst);
    }

    /** New recorded frame: turn its events into captions (only what matters to the focus country or is big). */
    private void onRecordedFrame(ReplayState st) {
        if (out == null) return;
        int player = playerOf(st);
        if (player != -1) focus = player;
        Set<Integer> side = st.allianceOf(focus);
        int total = 0;
        for (ReplayState.Area a : st.areas.values()) total += a.cells;
        for (ReplayState.Event e : st.events) {
            boolean ours = side.contains(e.a()) || (e.type() != ReplayState.EventType.CITY && side.contains(e.b()));
            if (e.type() == ReplayState.EventType.CITY) {
                if (e.a() == focus) pending.add(new FilmOverlay.Caption(e.text(), e.type()));
                continue;
            }
            if (e.type() == ReplayState.EventType.WAR && ours) urgent = true;
            boolean notable = e.type() != ReplayState.EventType.ALLIANCE && big(st, e.a(), total) && big(st, e.b(), total);
            if (ours || notable) pending.add(new FilmOverlay.Caption(e.text(), e.type()));
        }
        while (pending.size() > 6) pending.remove(0);
    }

    private static boolean big(ReplayState st, int key, int total) {
        ReplayState.Area a = st.areas.get(key);
        return a == null ? st.replay.countries.containsKey(key) : a.cells >= total * 0.015f;
    }

    /** Ends the film of this match; ffmpeg finishes the file in the background. */
    void finish() {
        session = null;
        if (readback != null) {
            // The last frames are still in the graphics card's buffers.
            while (out != null && readback.pending() > 0) {
                byte[] b = null;
                try {
                    b = spare.poll(2, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                if (b == null) break;
                readback.take(b);
                if (!queue.offer(b)) break;
            }
            readback.dispose();
            readback = null;
        }
        if (out == null) return;
        File done = out;
        int count = frames;
        out = null;
        BlockingQueue<byte[]> q = queue;
        Process p = ffmpeg;
        Thread wr = writer;
        q.offer(END);
        String ff = ffmpegPath;
        Thread closer = new Thread(() -> {
            try {
                wr.join(30_000);
                p.waitFor(60, TimeUnit.SECONDS);
                if (count < 30) {
                    done.delete(); // a few seconds of nothing
                    return;
                }
                // Rewrite the streamed (fragmented) file as a normal MP4: exact length, quick seeking.
                File fin = new File(done.getParentFile(), done.getName().replace(PART, ".mp4"));
                Process remux = new ProcessBuilder(ff, "-y", "-loglevel", "error", "-i", done.getAbsolutePath(), "-c", "copy",
                        "-movflags", "+faststart", fin.getAbsolutePath()).redirectErrorStream(true).start();
                remux.getInputStream().readAllBytes();
                if (remux.waitFor(300, TimeUnit.SECONDS) && remux.exitValue() == 0 && fin.length() > 0) done.delete();
            } catch (Exception ignored) {
                // the .part.mp4 stays and still plays
            }
        }, "film-finish");
        closer.setDaemon(false);
        closer.start();
        ctx.log("Film finished: " + done.getName() + " (" + count + " frames, " + dropped + " skipped)");
        if (count >= 30) ctx.ui().toast("Replay film saved: " + done.getName(), 5);
    }

    private void write(Process p, BlockingQueue<byte[]> q, BlockingQueue<byte[]> sp) {
        try (OutputStream os = p.getOutputStream()) {
            while (true) {
                byte[] frame = q.poll(500, TimeUnit.MILLISECONDS);
                if (frame == null) continue;
                if (frame == END) break;
                os.write(frame);
                sp.offer(frame);
            }
        } catch (InterruptedException ignored) {
            // shutting down
        } catch (IOException e) {
            synchronized (ffmpegLog) {
                error = "ffmpeg stopped: " + e.getMessage() + " " + ffmpegLog.toString().trim();
            }
        }
    }

    private void drain(InputStream in) {
        try (in) {
            byte[] b = new byte[4096];
            int n;
            while ((n = in.read(b)) > 0) {
                synchronized (ffmpegLog) {
                    if (ffmpegLog.length() < 4000) ffmpegLog.append(new String(b, 0, n));
                }
            }
        } catch (IOException ignored) {
            // process ended
        }
    }

    /** A video encoder and its ffmpeg options (input is RGB; the encoder or ffmpeg converts it). */
    record Encoder(String name, List<String> args) {}

    /** Software encoding: works everywhere but competes with the game for the processor. */
    private static final Encoder X264 = new Encoder("libx264",
            List.of("-c:v", "libx264", "-preset", "veryfast", "-crf", "20", "-pix_fmt", "yuv420p", "-threads", "4"));

    /** Graphics card encoders, best first: almost no processor time, so the game keeps its frame rate. */
    private static final List<Encoder> HARDWARE = List.of(
            new Encoder("h264_nvenc", List.of("-c:v", "h264_nvenc", "-preset", "p5", "-rc", "vbr", "-cq", "21", "-b:v", "0", "-profile:v", "high")),
            new Encoder("h264_amf", List.of("-c:v", "h264_amf", "-quality", "quality", "-rc", "cqp", "-qp_i", "20", "-qp_p", "22", "-pix_fmt", "nv12")),
            new Encoder("h264_qsv", List.of("-c:v", "h264_qsv", "-global_quality", "22", "-pix_fmt", "nv12")));

    /** The encoder films use; null until {@link #probeEncoder} has tried the graphics card's (libx264 meanwhile). */
    private static volatile Encoder encoder;
    private static volatile String probed;

    /** Tries the graphics card encoders with a few blank frames, in the background (once per ffmpeg path). */
    static void probeEncoder(String ffmpegPath, ModContext ctx) {
        if (ffmpegPath == null || ffmpegPath.equals(probed)) return;
        probed = ffmpegPath;
        Thread t = new Thread(() -> {
            for (Encoder e : HARDWARE) {
                List<String> cmd = new ArrayList<>(List.of(ffmpegPath, "-hide_banner", "-loglevel", "error",
                        "-f", "lavfi", "-i", "color=c=black:s=1280x720:r=30", "-frames:v", "5", "-vf", "format=rgb0"));
                cmd.addAll(e.args());
                cmd.addAll(List.of("-f", "null", "-"));
                try {
                    Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
                    p.getInputStream().readAllBytes();
                    if (p.waitFor(20, TimeUnit.SECONDS) && p.exitValue() == 0) {
                        encoder = e;
                        ctx.log("Films are encoded on the graphics card (" + e.name() + ")");
                        return;
                    }
                } catch (Exception ignored) {
                    // try the next one
                }
            }
            encoder = X264;
            ctx.log("No graphics card video encoder answered; films use libx264 (more processor time)");
        }, "film-encoder-probe");
        t.setDaemon(true);
        t.start();
    }

    /** Encoding must not take processor time from the game: run ffmpeg below normal priority (Windows). */
    private static void lowerPriority(Process p) {
        if (!System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")) return;
        long pid = p.pid();
        Thread t = new Thread(() -> {
            try {
                // Also its children: a launcher such as scoop's shim starts the real ffmpeg as a child.
                String ids = "@(" + pid + ") + @((Get-CimInstance Win32_Process -Filter 'ParentProcessId=" + pid + "').ProcessId)";
                Process ps = new ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-Command",
                        "foreach ($i in " + ids + ") { if ($i) { (Get-Process -Id $i).PriorityClass = 'BelowNormal' } }")
                        .redirectErrorStream(true).start();
                ps.getInputStream().readAllBytes();
                ps.waitFor(20, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                // normal priority then
            }
        }, "film-ffmpeg-priority");
        t.setDaemon(true);
        t.start();
    }

    /** "ffmpeg" on the PATH, the configured path, or null when none answers {@code -version}. */
    static String findFfmpeg(String configured) {
        List<String> candidates = new ArrayList<>();
        if (configured != null && !configured.isBlank()) candidates.add(configured.trim());
        candidates.add("ffmpeg");
        String home = System.getProperty("user.home");
        candidates.add(home + "\\scoop\\shims\\ffmpeg.exe");
        candidates.add("C:\\ffmpeg\\bin\\ffmpeg.exe");
        candidates.add("C:\\Program Files\\ffmpeg\\bin\\ffmpeg.exe");
        for (String c : candidates) {
            try {
                Process p = new ProcessBuilder(c, "-version").redirectErrorStream(true).start();
                p.getInputStream().readAllBytes();
                if (p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0) return c;
            } catch (Exception ignored) {
                // try the next one
            }
        }
        return null;
    }
}
