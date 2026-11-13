package aoc.api.internal;

import com.badlogic.gdx.graphics.Color;
import ru.ius.david.cgs.CFG;
import ru.ius.david.cgs.elements.ButtonElement;
import ru.ius.david.cgs.elements.MenuElement;
import ru.ius.david.cgs.game.LanguageManager;
import ru.ius.david.cgs.menus.SliderMenu;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Adds rows to the main menu right under the game's own "MODS" row, built with the menu's own
 * row factory so they look and animate like their neighbours. Runs after every
 * {@code MainMenuScreen.buildMenu()} (the menu rebuilds on resize/theme).
 */
final class MainMenuInjector {
    record Row(String label, Runnable onClick) {}

    private MainMenuInjector() {}

    static void inject(Object screen, java.util.List<Row> rows) {
        for (int i = rows.size() - 1; i >= 0; i--) inject(screen, rows.get(i));
    }

    /** Inserts one row directly under MODS (so inserting in reverse keeps the given order). */
    private static void inject(Object screen, Row newRow) {
        String label = newRow.label();
        Runnable onClick = newRow.onClick();
        try {
            Class<?> cls = screen.getClass();
            SliderMenu panel = (SliderMenu) field(cls, "menuPanel").get(screen);
            if (panel == null) return;
            List<MenuElement> elements = new ArrayList<>(panel.getMenuElements());
            for (MenuElement e : elements) {
                if (e instanceof ButtonElement b && label.equals(b.getText())) return; // already there
            }
            String modsLabel = LanguageManager.get("menu_mods").toUpperCase();
            int modsIndex = -1;
            for (int i = 0; i < elements.size(); i++) {
                if (elements.get(i) instanceof ButtonElement b && (modsLabel.equals(b.getText()) || "MODS".equals(b.getText()))) {
                    modsIndex = i;
                    break;
                }
            }
            if (modsIndex < 0) {
                Log.info("Main menu: no MODS row found, " + label + " not added");
                return;
            }
            MenuElement mods = elements.get(modsIndex);
            int btnH = field(cls, "btnH").getInt(screen);
            int gap = field(cls, "gap").getInt(screen);
            int delta = btnH + gap;
            int modsY = mods.getPosY();

            Method row = cls.getDeclaredMethod("row", int.class, int.class, int.class, int.class, String.class,
                    int.class, Color.class, Color.class, Runnable.class);
            row.setAccessible(true);
            ButtonElement codeMods = (ButtonElement) row.invoke(screen, mods.getPosX(), modsY + delta, mods.getWidth(),
                    mods.getHeight(), label, icon(), CFG.COLOR_PARCHMENT_BTN_BG, CFG.COLOR_TEXT_DARK, onClick);

            for (MenuElement e : elements) {
                if (e.getPosY() > modsY) e.setPosY(e.getPosY() + delta);
            }
            elements.add(modsIndex + 1, codeMods);
            panel.setMenuElements(elements);

            for (String sep : new String[]{"separatorDrawY", "separatorDrawY2", "separatorDrawY3"}) {
                Field f = field(cls, sep);
                if (f.getInt(screen) > modsY) f.setInt(screen, f.getInt(screen) + delta);
            }
            Field panelH = field(cls, "panelH");
            panelH.setInt(screen, panelH.getInt(screen) + delta);
            Field menuH = field(SliderMenu.class, "iHeight");
            menuH.setInt(panel, menuH.getInt(panel) + delta);
        } catch (Throwable t) {
            Log.error("Could not add " + label + " to the main menu", t);
        }
    }

    private static int icon() {
        for (String name : new String[]{"ic_mat_tune", "ic_menu_settings"}) {
            try {
                return ru.ius.david.cgs.images.Images.class.getField(name).getInt(null);
            } catch (ReflectiveOperationException ignored) {
                // try the next icon
            }
        }
        return 0;
    }

    private static Field field(Class<?> cls, String name) throws NoSuchFieldException {
        for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
                // look in the superclass
            }
        }
        throw new NoSuchFieldException(name);
    }
}
