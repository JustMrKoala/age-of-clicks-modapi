package aoc.api.internal;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

/**
 * Mod rows in the game's Economy menu ({@code CGSUpgradesMenu}), in the Cities section right under
 * the game's own "Found city" row. They are the game's own row objects (made like the found-city
 * row), so the menu draws them like its own. The loader calls in at three points: when the menu
 * builds its rows ({@link #rows}), after its per-frame update ({@link #afterUpdate}: the mod's badge
 * text, button caption and click action replace the found-city values the game puts there), and
 * when a row is opened ({@link #opened}: the mod's action runs instead of the game's detail panel).
 */
public final class EconomyRows {
    /** One mod row. */
    public record Spec(String name, String description, Supplier<String> chip, Supplier<String> button, Runnable onClick,
                       java.util.function.BooleanSupplier shown) {
        public Spec(String name, String description, Supplier<String> chip, Supplier<String> button, Runnable onClick) {
            this(name, description, chip, button, onClick, () -> true);
        }
    }

    private static final String ROW = "ru.ius.david.cgs.menus.CGSUpgradesMenu$UpgradeRow";
    private static final List<Spec> SPECS = new CopyOnWriteArrayList<>();
    /** The game's row objects made for mods (they do not override equals, so this is by identity). */
    private static final Map<Object, Spec> ROWS = Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<String, Field> FIELDS = new java.util.HashMap<>();
    private static Method foundCityRow;
    private static boolean failed;

    private EconomyRows() {}

    public static void add(Spec spec) { SPECS.add(spec); }

    /** End of {@code CGSUpgradesMenu.buildUpgradesRows()}: insert the mod rows after the found-city row. */
    @SuppressWarnings("unchecked")
    public static List<Object> rows(List<Object> rows) {
        if (SPECS.isEmpty() || rows == null || failed) return rows;
        try {
            int at = -1;
            for (int i = 0; i < rows.size(); i++) {
                if (Boolean.TRUE.equals(field(rows.get(i), "isFoundCity").get(rows.get(i)))) {
                    at = i;
                    break;
                }
            }
            if (at < 0) return rows;
            int icon = field(rows.get(at), "iconId").getInt(rows.get(at));
            if (foundCityRow == null) {
                foundCityRow = Class.forName(ROW).getDeclaredMethod("foundCity", int.class, String.class, String.class);
                foundCityRow.setAccessible(true);
            }
            for (Spec s : SPECS) {
                if (!s.shown().getAsBoolean()) continue;
                Object row = foundCityRow.invoke(null, icon, s.name(), s.description());
                ROWS.put(row, s);
                rows.add(++at, row);
            }
        } catch (Throwable t) {
            fail(t);
        }
        return rows;
    }

    /** End of {@code CGSUpgradesMenu.update(float)}: the mod rows' badge, caption and action. */
    public static void afterUpdate(Object menu) {
        if (ROWS.isEmpty() || failed) return;
        try {
            List<?> all = (List<?>) field(menu, "allRows").get(menu);
            if (all == null) return;
            for (Object row : all) {
                Spec s = ROWS.get(row);
                if (s == null) continue;
                field(row, "cityCountText").set(row, s.chip() == null ? null : s.chip().get());
                field(row, "canAfford").setBoolean(row, true);
                field(row, "isLocked").setBoolean(row, false);
                field(row, "isMaxLevel").setBoolean(row, false);
                Object button = field(row, "button").get(row);
                if (button == null) continue;
                field(button, "text").set(button, s.button() == null ? "" : s.button().get());
                field(button, "active").setBoolean(button, true);
                Field action = field(button, "onClickAction");
                if (!(action.get(button) instanceof Click)) action.set(button, new Click(s));
            }
        } catch (Throwable t) {
            fail(t);
        }
    }

    /** Start of {@code CGSUpgradesMenu.openUpgradeDetail(row)}: true when a mod row handled it. */
    public static boolean opened(Object row) {
        Spec s = row == null ? null : ROWS.get(row);
        if (s == null) return false;
        run(s);
        return true;
    }

    private static void run(Spec s) {
        try {
            s.onClick().run();
        } catch (Throwable t) {
            Log.error("Economy row '" + s.name() + "' failed", t);
        }
    }

    /** A mod row's button action (recognisable, so it is set once). */
    private record Click(Spec spec) implements Runnable {
        @Override
        public void run() { EconomyRows.run(spec); }
    }

    private static Field field(Object o, String name) throws NoSuchFieldException {
        String key = o.getClass().getName() + "#" + name;
        Field f = FIELDS.get(key);
        if (f != null) return f;
        for (Class<?> c = o.getClass(); c != null; c = c.getSuperclass()) {
            try {
                f = c.getDeclaredField(name);
                f.setAccessible(true);
                FIELDS.put(key, f);
                return f;
            } catch (NoSuchFieldException ignored) {
                // superclass next
            }
        }
        throw new NoSuchFieldException(key);
    }

    private static void fail(Throwable t) {
        failed = true;
        Log.error("Mod rows in the Economy menu are off (the game changed?)", t);
    }
}
