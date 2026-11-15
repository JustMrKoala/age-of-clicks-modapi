package aoc.api.internal;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.PixmapIO;
import ru.ius.david.cgs.cgsGame;
import ru.ius.david.cgs.game.GameManager;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.Properties;

/**
 * Developer self-test of the central-server start flow, only active while
 * {@code mods/aoc-loader/mptest.properties} exists: creates a lobby from the MULTIPLAYER window,
 * checks the lobby panel and the creator's start button, starts early, presses "Start without
 * waiting" while countries are picked, checks that game days advance, saves screenshots in
 * {@code mods/aoc-loader/mptest/} and closes the game. Keys: {@code scenario} (scenarios/1x1/),
 * {@code needed} (5), {@code waitPlayers} (seconds to wait in the lobby for other clients, 0),
 * {@code seconds} (play time, 20).
 */
final class MultiplayerSelfTest {
    private enum Step { MENU, CREATING, LOBBY, PICKING, PLAYING, CLOSING, DONE }

    private final Properties p = new Properties();
    private final File out;
    private Step step = Step.MENU;
    private float clock;
    private String shot;
    private int startDay = -1;
    private boolean pressed, failed;

    private MultiplayerSelfTest(File cfg, File out) throws Exception {
        try (InputStream in = new FileInputStream(cfg)) {
            p.load(in);
        }
        this.out = out;
        out.mkdirs();
        File[] old = out.listFiles();
        if (old != null) for (File f : old) f.delete();
    }

    /** The self-test, or null when the trigger file is absent. */
    static MultiplayerSelfTest load(File gameDir) {
        File cfg = new File(gameDir, "mods/aoc-loader/mptest.properties");
        if (!cfg.isFile()) return null;
        try {
            Log.info("MPTEST active: " + cfg);
            MultiplayerSelfTest test = new MultiplayerSelfTest(cfg, new File(gameDir, "mods/aoc-loader/mptest"));
            // One run per trigger file: a test left armed would take over the player's next game.
            cfg.delete();
            return test;
        } catch (Exception e) {
            Log.error("MPTEST config unreadable", e);
            return null;
        }
    }

    void tick(float delta) {
        clock += delta;
        MultiplayerTestWindow w = ModManager.multiplayerWindow();
        switch (step) {
            case MENU -> {
                Object screen = ((cgsGame) Gdx.app.getApplicationListener()).getScreen();
                if (clock > 3f && screen != null && screen.getClass().getSimpleName().equals("MainMenuScreen")) {
                    ModManager.openMultiplayerTest();
                    next(Step.CREATING);
                }
            }
            case CREATING -> {
                if (w != null && w.selfTestCreate(p.getProperty("scenario", "scenarios/1x1/"),
                        Integer.parseInt(p.getProperty("needed", "5")))) {
                    Log.info("MPTEST lobby requested");
                    next(Step.LOBBY);
                } else if (clock > 20f) {
                    fail("the central server's info never arrived");
                }
            }
            case LOBBY -> {
                if (!MultiplayerTestWindow.inCentralLobby()) {
                    if (clock > 40f) fail("never reached the game's lobby screen in a central lobby");
                    return;
                }
                float wait = Float.parseFloat(p.getProperty("waitPlayers", "0"));
                if (!pressed && (clock < wait + 8f || MultiplayerTestWindow.joinedLobby() == null) && clock < wait + 30f) return;
                if (!pressed) {
                    Log.info("MPTEST in lobby: panel=" + w.showLobbyPanel() + " startButton=" + MultiplayerTestWindow.canStartOwnLobby()
                            + " codeModsPaused=" + ModManager.multiplayerActive() + " status='" + MultiplayerTestWindow.lobbyStatusText() + "'");
                    shot = "lobby";
                    pressed = true;
                    return;
                }
                if (shot != null) return;
                Log.info("MPTEST pressing 'Start the match now'");
                w.startCreatedLobby();
                pressed = false;
                next(Step.PICKING);
            }
            case PICKING -> {
                if (!(MultiplayerTestWindow.pickingCountries() && MultiplayerTestWindow.canStartOwnLobby())) {
                    if (clock > 90f) fail("the match did not reach the country pick with a start button (picking="
                            + MultiplayerTestWindow.pickingCountries() + ")");
                    return;
                }
                if (!pressed) {
                    if (clock < 3f) return;
                    Log.info("MPTEST country pick: startButton=" + MultiplayerTestWindow.canStartOwnLobby() + " day=" + GameManager.getInstance().getGameDays());
                    shot = "pick";
                    pressed = true;
                    return;
                }
                if (shot != null) return;
                if (picks()) {
                    pickRandomCountryAndReady();
                } else {
                    Log.info("MPTEST pressing 'Start without waiting'");
                    w.startCreatedLobby();
                }
                startDay = GameManager.getInstance().getGameDays();
                next(Step.PLAYING);
            }
            case PLAYING -> {
                if (clock < Float.parseFloat(p.getProperty("seconds", "20"))) return;
                int day = GameManager.getInstance().getGameDays();
                boolean picking = MultiplayerTestWindow.pickingCountries();
                boolean pass = day > startDay && !picking;
                Log.info("MPTEST RESULT: " + (pass ? "PASS" : "FAIL") + " - days " + startDay + " -> " + day
                        + ", still picking=" + picking + ", playing as " + GameManager.getInstance().getLocalPlayerCountryName());
                shot = "play";
                next(Step.CLOSING);
            }
            case CLOSING -> {
                if (clock > 3f) {
                    Log.info("MPTEST closing the game");
                    step = Step.DONE;
                    Gdx.app.exit();
                }
            }
            default -> { }
        }
    }

    /** pick=true (default): pick a country and press Ready like a player; false: press "Start without waiting" unpicked. */
    private boolean picks() { return Boolean.parseBoolean(p.getProperty("pick", "true")); }

    /** The pick menu's dice button, then its Ready button. */
    private void pickRandomCountryAndReady() {
        try {
            ru.ius.david.cgs.MapScreen map = ru.ius.david.cgs.MapScreen.getActiveInstance();
            java.lang.reflect.Field mf = ru.ius.david.cgs.MapScreen.class.getDeclaredField("countrySelectMenu");
            mf.setAccessible(true);
            ru.ius.david.cgs.menus.CGSCountrySelectMenu menu = (ru.ius.david.cgs.menus.CGSCountrySelectMenu) mf.get(map);
            java.lang.reflect.Method roll = menu.getClass().getDeclaredMethod("rollRandomCountry");
            roll.setAccessible(true);
            // The dice may land on a country another player has (small maps): roll again.
            java.util.Set<Integer> taken = new java.util.HashSet<>();
            ru.ius.david.cgs.multiplayer.MultiplayerManager mp = ((cgsGame) Gdx.app.getApplicationListener()).getActiveMultiplayerManager();
            if (mp != null && mp.getLatestLobbyState() != null && mp.getLatestLobbyState().players != null) {
                for (ru.ius.david.cgs.multiplayer.NetworkProtocol.PlayerInfo pi : mp.getLatestLobbyState().players) {
                    if (pi.playerId != mp.getLocalPlayerId()) taken.add(pi.selectedColorKey);
                }
            }
            ru.ius.david.cgs.CountryData c = null;
            for (int i = 0; i < 30 && (c == null || taken.contains(c.colorKey)); i++) {
                roll.invoke(menu);
                c = menu.getSelectedCountry();
            }
            Log.info("MPTEST picked " + (c == null ? "nothing" : c.name) + "; pressing Ready");
            java.lang.reflect.Field bf = menu.getClass().getDeclaredField("btnReady");
            bf.setAccessible(true);
            ((ru.ius.david.cgs.elements.ButtonElement) bf.get(menu)).actionElement();
        } catch (Exception e) {
            Log.error("MPTEST could not pick a country", e);
        }
    }

    /** After the loader's UI is drawn: saves a requested screenshot. */
    void afterUi() {
        if (shot == null) return;
        try {
            dumpScreen(new File(out, shot + ".png"));
            Log.info("MPTEST saved " + shot + ".png");
        } catch (Throwable t) {
            Log.error("MPTEST screenshot failed", t);
        }
        shot = null;
    }

    private void next(Step s) {
        step = s;
        clock = 0;
    }

    private void fail(String why) {
        if (failed) return;
        failed = true;
        Log.info("MPTEST RESULT: FAIL - " + why);
        shot = "fail";
        next(Step.CLOSING);
    }

    static void dumpScreen(File f) {
        int w = Gdx.graphics.getBackBufferWidth(), h = Gdx.graphics.getBackBufferHeight();
        ByteBuffer buf = com.badlogic.gdx.utils.BufferUtils.newByteBuffer(w * h * 4);
        Gdx.gl.glPixelStorei(GL20.GL_PACK_ALIGNMENT, 1);
        Gdx.gl.glReadPixels(0, 0, w, h, GL20.GL_RGBA, GL20.GL_UNSIGNED_BYTE, buf);
        Pixmap pm = new Pixmap(w, h, Pixmap.Format.RGBA8888);
        ByteBuffer px = pm.getPixels();
        byte[] row = new byte[w * 4];
        for (int y = 0; y < h; y++) {
            buf.position(y * w * 4);
            buf.get(row);
            px.position((h - 1 - y) * w * 4);
            px.put(row);
        }
        px.position(0);
        for (int i = 3; i < w * h * 4; i += 4) px.put(i, (byte) 255);
        PixmapIO.writePNG(Gdx.files.absolute(f.getAbsolutePath()), pm);
        pm.dispose();
    }
}
