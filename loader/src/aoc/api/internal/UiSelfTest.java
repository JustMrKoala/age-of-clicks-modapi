package aoc.api.internal;

import com.badlogic.gdx.Gdx;
import ru.ius.david.cgs.MapScreen;
import ru.ius.david.cgs.cgsGame;
import ru.ius.david.cgs.menus.CGSUpgradesMenu;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Properties;

/**
 * Developer self-test of how mod UI sits next to the game's interface, only active while
 * {@code mods/aoc-loader/uitest.properties} exists: opens the Mod Browser in the main menu, loads
 * a save ({@code save}, a folder name in saves/), posts a mod notice next to a game notice, opens the
 * game's economy, diplomacy and military menus and a mod window, saves a screenshot of each step in
 * {@code mods/aoc-loader/uitest/}, logs the HUD layout and closes the game.
 */
final class UiSelfTest {
    private final Properties p = new Properties();
    private final File out;
    private float clock;
    private int step;
    private boolean inMap;
    private String shot;

    private UiSelfTest(File cfg, File out) throws Exception {
        try (InputStream in = new FileInputStream(cfg)) {
            p.load(in);
        }
        this.out = out;
        out.mkdirs();
        File[] old = out.listFiles();
        if (old != null) for (File f : old) f.delete();
    }

    static UiSelfTest load(File gameDir) {
        File cfg = new File(gameDir, "mods/aoc-loader/uitest.properties");
        if (!cfg.isFile()) return null;
        try {
            Log.info("UITEST active: " + cfg);
            return new UiSelfTest(cfg, new File(gameDir, "mods/aoc-loader/uitest"));
        } catch (Exception e) {
            Log.error("UITEST config unreadable", e);
            return null;
        }
    }

    /** Runs {@code action} once when the step's time has come. */
    private void at(int index, float time, Runnable action) {
        if (step != index || clock < time) return;
        step++;
        try {
            action.run();
        } catch (Throwable t) {
            Log.error("UITEST step " + index + " failed", t);
        }
    }

    void tick(float delta) {
        clock += delta;
        MapScreen map = MapScreen.getActiveInstance();
        if (!inMap) {
            at(0, 3f, ModManager::openModBrowser);
            at(1, 9f, () -> shot = "browser");
            at(2, 10f, () -> {
                Log.info("UITEST loading save " + p.getProperty("save"));
                ((cgsGame) Gdx.app.getApplicationListener()).loadSaveGame(p.getProperty("save"));
            });
            if (step == 3 && map != null && map.getInGameHud() != null && !map.isCountrySelectMode()
                    && GameHud.layout().inGame() && clock > 16f) {
                inMap = true;
                clock = 0;
            }
            if (clock > 120f) quit("the save never loaded");
            return;
        }
        if (map == null) return;
        at(3, 4f, () -> {
            ModManager.systemUi().toast("Mod notice: this must sit under the game's notices", 30);
            map.getInGameHud().showToast("Game notice from the self-test");
        });
        at(4, 6f, () -> {
            GameHud.Layout l = GameHud.layout();
            Log.info("UITEST layout: screen " + Gdx.graphics.getWidth() + "x" + Gdx.graphics.getHeight() + " top " + l.top()
                    + " bottom " + l.bottom() + " noticeY " + l.noticeY() + " modal " + l.modal() + " blocks " + l.blocks().size());
            for (GameHud.Block b : l.blocks()) Log.info("UITEST   " + b);
            shot = "map";
        });
        at(5, 7f, () -> map.showUpgradesMenu(CGSUpgradesMenu.Mode.BUILDINGS));
        at(6, 9.5f, () -> logAndShoot("economy"));
        at(7, 10.5f, map::showDiplomacyMenu);
        at(8, 13f, () -> logAndShoot("diplomacy"));
        at(9, 14f, map::showMilitaryMenu);
        at(10, 16.5f, () -> logAndShoot("military"));
        at(11, 17.5f, ModManager::openCodeMods);
        at(12, 19f, () -> logAndShoot("window"));
        at(13, 20f, () -> quit(null));
    }

    private void logAndShoot(String name) {
        GameHud.Layout l = GameHud.layout();
        StringBuilder b = new StringBuilder("UITEST ").append(name).append(": modal ").append(l.modal()).append(", panels");
        for (GameHud.Block k : l.blocks()) if (!k.what().startsWith("hud ") && !k.what().startsWith("notice")) b.append(' ').append(k);
        Log.info(b.toString());
        shot = name;
    }

    void afterUi() {
        if (shot == null) return;
        try {
            MultiplayerSelfTest.dumpScreen(new File(out, shot + ".png"));
            Log.info("UITEST saved " + shot + ".png");
        } catch (Throwable t) {
            Log.error("UITEST screenshot failed", t);
        }
        shot = null;
    }

    private void quit(String failure) {
        Log.info(failure == null ? "UITEST done" : "UITEST FAILED: " + failure);
        Gdx.app.exit();
    }
}
