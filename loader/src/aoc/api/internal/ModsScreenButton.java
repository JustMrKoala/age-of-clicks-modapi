package aoc.api.internal;

import aoc.api.ui.Button;
import aoc.api.ui.Ui;
import com.badlogic.gdx.Game;
import com.badlogic.gdx.Gdx;
import ru.ius.david.cgs.CFG;
import ru.ius.david.cgs.menus.ModManagerScreen;

import java.lang.reflect.Field;

/**
 * Code Mods lives in the game's own MODS screen (main menu > MODS, the index of scenario mods):
 * the screen's full-width Back button keeps the left half of its row and a CODE MODS button takes the
 * right half. Back stays the game's own button (its fields are narrowed before the game draws it,
 * so its drawing and clicks follow).
 */
final class ModsScreenButton {
    private static Field backX, backY, backW, backH, boxW, confirm, changelog;
    private static boolean failed;

    private ModsScreenButton() {}

    static void install(Ui ui, Runnable openCodeMods) {
        ui.addHud(new Button("CODE MODS").onClick(openCodeMods), ModsScreenButton::rect, ModsScreenButton::shown);
    }

    /** The game's mod screen, when it is the current screen. */
    private static ModManagerScreen screen() {
        if (failed || !(Gdx.app.getApplicationListener() instanceof Game g)) return null;
        return g.getScreen() instanceof ModManagerScreen s ? s : null;
    }

    /** Start of the frame: halve Back before the game draws it (its layout restores the full width on resize). */
    static void beforeFrame() {
        ModManagerScreen s = screen();
        if (s == null) return;
        try {
            fields();
            int full = boxW.getInt(s) - CFG.s(20);
            if (backW.getInt(s) >= full - 1) backW.setInt(s, (full - CFG.s(10)) / 2);
        } catch (Throwable t) {
            failed = true;
            Log.error("Code Mods button in the MODS screen failed", t);
        }
    }

    private static boolean shown() {
        ModManagerScreen s = screen();
        if (s == null) return false;
        try {
            fields();
            Object dialog = confirm.get(s), log = changelog.get(s);
            boolean blocked = (dialog instanceof ru.ius.david.cgs.ui.CGSConfirmDialog d && d.isVisible())
                    || (log instanceof ru.ius.david.cgs.menus.ChangelogOverlay c && c.isOpen());
            return !blocked && backW.getInt(s) < boxW.getInt(s) - CFG.s(20) - 1;
        } catch (Throwable t) {
            return false;
        }
    }

    /** The right half of the Back row, in window pixels. */
    private static float[] rect() {
        ModManagerScreen s = screen();
        try {
            int x = backX.getInt(s), w = backW.getInt(s), gap = CFG.s(10);
            int full = boxW.getInt(s) - CFG.s(20);
            return new float[]{x + w + gap, backY.getInt(s), full - w - gap, backH.getInt(s)};
        } catch (Throwable t) {
            return new float[]{0, 0, 0, 0};
        }
    }

    private static void fields() throws NoSuchFieldException {
        if (backX != null) return;
        Class<?> c = ModManagerScreen.class;
        backX = field(c, "btnBackX");
        backY = field(c, "btnBackY");
        backW = field(c, "btnBackW");
        backH = field(c, "btnBackH");
        boxW = field(c, "boxLeftW");
        confirm = field(c, "confirmDialog");
        changelog = field(c, "changelog");
    }

    private static Field field(Class<?> c, String name) throws NoSuchFieldException {
        Field f = c.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }
}
