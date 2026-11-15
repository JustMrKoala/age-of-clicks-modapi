package aoc.replay;

import aoc.api.ModContext;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.PixmapIO;
import ru.ius.david.cgs.game.GameManager;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.Properties;

/**
 * Developer self-test, only active while {@code mods/replay/selftest.properties} exists: loads a
 * save, plays it at speed while filming, saves film frames and the player's own frames as PNG
 * (in {@code mods/replay/selftest/}) with extra film logging, then closes the game.
 * Keys: {@code save} (folder name in saves/), {@code seconds} (60), {@code speed} (4),
 * {@code dumpEvery} (film frames between saved frames, 40).
 */
final class SelfTest {
    private enum Step { MENU, LOADING, PLAYING, CLOSING, DONE }

    private final ModContext ctx;
    private final Properties p = new Properties();
    final File out;
    private Step step = Step.MENU;
    private float clock, sinceDump, sinceDialogCheck;
    private int playerShots;
    private final java.util.Set<String> switched = new java.util.HashSet<>();

    private File trigger;

    private SelfTest(ModContext ctx, File cfg) throws Exception {
        this.ctx = ctx;
        this.trigger = cfg;
        try (InputStream in = new FileInputStream(cfg)) {
            p.load(in);
        }
        out = new File(ctx.dataDir(), "selftest");
        out.mkdirs();
        File[] old = out.listFiles();
        if (old != null) for (File f : old) f.delete();
    }

    /** The self-test, or null when the trigger file is absent. */
    static SelfTest load(ModContext ctx) {
        File cfg = new File(ctx.dataDir(), "selftest.properties");
        if (!cfg.isFile()) return null;
        try {
            ctx.log("SELFTEST active: " + cfg);
            return new SelfTest(ctx, cfg);
        } catch (Exception e) {
            ctx.error("SELFTEST config unreadable", e);
            return null;
        }
    }

    int dumpEvery() { return Integer.parseInt(p.getProperty("dumpEvery", "40")); }

    /** film=false: play without filming, to measure the game's own frame rate. */
    boolean filming() {
        String f = p.getProperty("film", "true");
        // film=alternate: 10 s filming, 10 s not, to compare frame rates in the same game.
        if (f.equals("alternate")) return step != Step.PLAYING || ((int) (clock / 10f)) % 2 == 0;
        return Boolean.parseBoolean(f);
    }

    /** encode=false: capture film frames but do not send them to ffmpeg, to measure the encoder's cost. */
    boolean encoding() { return Boolean.parseBoolean(p.getProperty("encode", "true")); }

    private float fpsClock;
    private int fpsFrames, fpsRunning;
    /** Time spent on the game thread by the film and by the recorder. */
    long filmNanos, recorderNanos;

    /** followWar=true: the film follows the biggest war on the map, to test flags and garrisons. */
    boolean followBiggestWar() { return Boolean.parseBoolean(p.getProperty("followWar", "false")); }

    private float pacingClock, firstGameTime = Float.NaN;
    private int pFrames, pWar, pWaitTime, pWaitCap, pWaitBudget;

    private float camMin = Float.MAX_VALUE, camMax;
    private int camCuts = -1, camCutsLogged;
    private final java.util.Set<String> camShots = new java.util.TreeSet<>();

    /** The film camera per film frame: zoom range, cuts and shots, logged every 5 seconds with the pacing. */
    void camera(float height, int cuts, String shot) {
        camMin = Math.min(camMin, height);
        camMax = Math.max(camMax, height);
        if (camCuts < 0) camCutsLogged = cuts;
        camCuts = cuts;
        camShots.add(shot);
    }

    /** Film pacing per frame, summed up in the log every 5 seconds. */
    void pacing(float delta, boolean war, boolean waitTime, boolean waitCap, boolean waitBudget, float gameTime) {
        if (Float.isNaN(firstGameTime)) firstGameTime = gameTime;
        pacingClock += delta;
        pFrames++;
        if (war) pWar++;
        if (waitTime) pWaitTime++;
        else if (waitCap) pWaitCap++;
        else if (waitBudget) pWaitBudget++;
        if (pacingClock >= 5f) {
            ctx.log(String.format(java.util.Locale.ROOT,
                    "SELFTEST pacing over %.1f s: %d game frames, %d at war, %d waiting for game time, %d for the rate cap, %d for the time budget, %.1f game days",
                    pacingClock, pFrames, pWar, pWaitTime, pWaitCap, pWaitBudget, gameTime - firstGameTime)
                    + (camCuts < 0 ? "" : String.format(java.util.Locale.ROOT, "; camera height %.0f..%.0f cells, %d cuts, shots %s",
                    camMin, camMax, camCuts - camCutsLogged, camShots)));
            camMin = Float.MAX_VALUE;
            camMax = 0;
            camCutsLogged = Math.max(camCuts, 0);
            camShots.clear();
            pacingClock = 0;
            pFrames = pWar = pWaitTime = pWaitCap = pWaitBudget = 0;
            firstGameTime = gameTime;
        }
    }

    /** Called after every game frame. */
    void tick(float delta, LiveFilm film) {
        // One run per trigger file (a test left armed would take over the player's next game). Here and
        // not when loading: mods are also loaded in the launcher process that only restarts the game.
        if (trigger != null) {
            trigger.delete();
            trigger = null;
        }
        clock += delta;
        ru.ius.david.cgs.MapScreen map = ru.ius.david.cgs.MapScreen.getActiveInstance();
        switch (step) {
            case MENU -> {
                if (clock > 3f) {
                    String save = p.getProperty("save");
                    ctx.log("SELFTEST loading save " + save);
                    ((ru.ius.david.cgs.cgsGame) Gdx.app.getApplicationListener()).loadSaveGame(save);
                    step = Step.LOADING;
                    clock = 0;
                }
            }
            case LOADING -> {
                if (map != null && ctx.game().inGame() && !map.isCountrySelectMode() && clock > 4f) {
                    GameManager gm = GameManager.getInstance();
                    gm.setGamePaused(false);
                    gm.setPlaySpeed(Integer.parseInt(p.getProperty("speed", "4")));
                    ctx.log("SELFTEST playing at speed " + gm.getPlaySpeed() + " from day " + gm.getGameDays());
                    step = Step.PLAYING;
                    clock = 0;
                } else if (clock > 120f) {
                    ctx.log("SELFTEST gave up: the save did not open");
                    step = Step.CLOSING;
                    clock = 0;
                }
            }
            case PLAYING -> {
                fpsClock += delta;
                fpsFrames++;
                if (!GameManager.getInstance().isGamePaused()) fpsRunning++;
                if (fpsClock >= 5f) {
                    ctx.log(String.format(java.util.Locale.ROOT,
                            "SELFTEST frames: %.1f fps over %.1f s (%d of %d frames unpaused), filming %s; film %.0f ms/s, recorder %.0f ms/s",
                            fpsFrames / fpsClock, fpsClock, fpsRunning, fpsFrames, filming(),
                            filmNanos / 1e6 / fpsClock, recorderNanos / 1e6 / fpsClock));
                    fpsClock = 0;
                    fpsFrames = fpsRunning = 0;
                    filmNanos = recorderNanos = 0;
                }
                sinceDump += delta;
                sinceDialogCheck += delta;
                if (sinceDialogCheck > 0.5f && map != null) {
                    sinceDialogCheck = 0;
                    dismissDialogs(map);
                    rejectPeaceDemand(map);
                    // Pop-ups pause the game; once they are handled, play on.
                    GameManager gm = GameManager.getInstance();
                    if (gm.isGamePaused()) {
                        gm.setGamePaused(false);
                        gm.setPlaySpeed(Integer.parseInt(p.getProperty("speed", "4")));
                    }
                }
                if (sinceDump > 6f) {
                    sinceDump = 0;
                    dumpScreen(new File(out, String.format("player_%02d.png", playerShots++)));
                }
                // modes=ALLIANCES@15,DIPLOMACY@30: switch the player's map mode like the map-mode buttons do.
                for (String m : p.getProperty("modes", "").split(",")) {
                    String[] at = m.split("@");
                    if (at.length == 2 && !switched.contains(m) && clock >= Float.parseFloat(at[1])) {
                        switched.add(m);
                        ru.ius.david.cgs.MapModeManager.getInstance().setMapMode(ru.ius.david.cgs.MapModeManager.MapMode.valueOf(at[0]));
                        ctx.log("SELFTEST player map mode -> " + at[0]);
                    }
                }
                if (clock > Float.parseFloat(p.getProperty("seconds", "60"))) {
                    ctx.log("SELFTEST done playing at day " + GameManager.getInstance().getGameDays() + "; finishing the film");
                    film.finish();
                    step = Step.CLOSING;
                    clock = 0;
                }
            }
            case CLOSING -> {
                if (clock > 10f) {
                    ctx.log("SELFTEST closing the game");
                    step = Step.DONE;
                    Gdx.app.exit();
                }
            }
            default -> { }
        }
    }

    /** Pop-ups pause the game; decline them like a player clicking the button (test copies of saves only). */
    private boolean peaceFailed;

    /** The peace demand window is a menu, not a dialog: reject the demand, so the war goes on for the camera. */
    private void rejectPeaceDemand(ru.ius.david.cgs.MapScreen map) {
        if (peaceFailed) return;
        try {
            java.lang.reflect.Field mf = ru.ius.david.cgs.MapScreen.class.getDeclaredField("peaceConferenceMenu");
            mf.setAccessible(true);
            Object menu = mf.get(map);
            if (menu == null) return;
            java.lang.reflect.Field vf = ru.ius.david.cgs.menus.SliderMenu.class.getDeclaredField("visible");
            vf.setAccessible(true);
            if (!vf.getBoolean(menu)) return;
            java.lang.reflect.Field cf = ru.ius.david.cgs.menus.CGSPeaceConferenceMenu.class.getDeclaredField("peaceCallback");
            cf.setAccessible(true);
            ((ru.ius.david.cgs.menus.CGSPeaceConferenceMenu.PeaceCallback) cf.get(menu)).onDecision(false, 0);
            ((ru.ius.david.cgs.menus.CGSPeaceConferenceMenu) menu).setVisible(false);
            ctx.log("SELFTEST rejected a peace demand");
        } catch (Exception e) {
            peaceFailed = true;
            ctx.log("SELFTEST cannot answer the peace demand: " + e);
        }
    }

    private void dismissDialogs(ru.ius.david.cgs.MapScreen map) {
        for (java.lang.reflect.Field f : map.getClass().getDeclaredFields()) {
            if (!f.getType().getSimpleName().endsWith("Dialog")) continue;
            try {
                f.setAccessible(true);
                Object d = f.get(map);
                if (d == null) continue;
                Object visible = d.getClass().getMethod("isVisible").invoke(d);
                if (!Boolean.TRUE.equals(visible)) continue;
                for (String key : new String[]{"decline", "close", "ok", "continue", "accept"}) {
                    int[] r;
                    try {
                        r = (int[]) d.getClass().getMethod("rectForTest", String.class).invoke(d, key);
                    } catch (NoSuchMethodException e) {
                        break;
                    }
                    if (r == null || r.length < 4) continue;
                    int x = r[0] + r[2] / 2, y = r[1] + r[3] / 2;
                    d.getClass().getMethod("touchDown", int.class, int.class).invoke(d, x, y);
                    d.getClass().getMethod("touchUp", int.class, int.class).invoke(d, x, y);
                    ctx.log("SELFTEST clicked '" + key + "' on " + f.getName());
                    return;
                }
                try {
                    d.getClass().getMethod("hide").invoke(d);
                    ctx.log("SELFTEST hid " + f.getName());
                } catch (NoSuchMethodException ignored) {
                    ctx.log("SELFTEST cannot close " + f.getName());
                }
            } catch (Exception e) {
                ctx.log("SELFTEST dialog " + f.getName() + ": " + e);
            }
        }
    }

    /** The back buffer as the player sees it. */
    private void dumpScreen(File f) {
        int w = Gdx.graphics.getBackBufferWidth(), h = Gdx.graphics.getBackBufferHeight();
        ByteBuffer buf = com.badlogic.gdx.utils.BufferUtils.newByteBuffer(w * h * 4);
        Gdx.gl.glPixelStorei(GL20.GL_PACK_ALIGNMENT, 1);
        Gdx.gl.glReadPixels(0, 0, w, h, GL20.GL_RGBA, GL20.GL_UNSIGNED_BYTE, buf);
        byte[] bytes = new byte[w * h * 4];
        buf.get(bytes);
        save(bytes, w, h, f);
    }

    /** Writes bottom-up RGBA pixels (as read from GL) as an upright PNG. */
    static void save(byte[] rgba, int w, int h, File f) {
        Pixmap pm = new Pixmap(w, h, Pixmap.Format.RGBA8888);
        ByteBuffer px = pm.getPixels();
        for (int y = 0; y < h; y++) {
            px.position((h - 1 - y) * w * 4);
            px.put(rgba, y * w * 4, w * 4);
        }
        px.position(0);
        // Opaque: the film's alpha channel is meaningless.
        for (int i = 3; i < w * h * 4; i += 4) px.put(i, (byte) 255);
        PixmapIO.writePNG(Gdx.files.absolute(f.getAbsolutePath()), pm);
        pm.dispose();
    }
}
