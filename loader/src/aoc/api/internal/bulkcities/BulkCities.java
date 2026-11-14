package aoc.api.internal.bulkcities;

import aoc.api.AocMod;
import aoc.api.ModContext;
import aoc.api.actions.ActionResult;
import aoc.api.settings.Setting;
import aoc.api.ui.Button;
import aoc.api.ui.Label;
import aoc.api.ui.Panel;
import aoc.api.ui.Separator;
import aoc.api.ui.Window;
import aoc.api.world.World;
import ru.ius.david.cgs.MapScreen;
import ru.ius.david.cgs.game.CityManager;
import ru.ius.david.cgs.game.CountryState;
import ru.ius.david.cgs.game.GameManager;
import ru.ius.david.cgs.game.LanguageManager;
import ru.ius.david.cgs.ui.CGSTextInputDialog;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Found cities in bulk. The game's own "found city" mode stays on after each city (click, click,
 * click; right-click or Esc stops) and its name dialog is answered for you: with the real town that
 * lies at that spot (from the city lists of the game's other maps), else a name made up in the style
 * of the region's towns. A window can also found real towns at their real places across your land.
 * The game's own rules and costs apply to every city.
 *
 * <p>Part of Game Improvements (it was the separate Bulk Cities mod until loader 0.9.0): the
 * loader's {@code Improvements} entry owns the switch and passes the callbacks on.
 */
public final class BulkCities implements AocMod {
    /** Game Improvements switch for the whole feature. */
    private final java.util.function.BooleanSupplier enabled;

    public BulkCities(java.util.function.BooleanSupplier enabled) { this.enabled = enabled; }


    private ModContext ctx;
    private Setting<Boolean> autoName, keepFounding, realNames;

    /** All city lists, read once in the background. */
    private volatile List<Gazetteer.Source> sources;
    /** The lists placed on the current map (built in the background per map). */
    private volatile Gazetteer gazetteer;
    private MapScreen gazetteerMap;
    /** Names given in this game (also those of cities the game has not added yet). */
    private final Set<String> givenKeys = new HashSet<>();

    // name dialog handling
    private Object handled;
    /** Start found-city mode again once the confirmed name dialog has closed. */
    private boolean rearm;
    private String lastName;

    // fill
    private Window window;
    private List<Gazetteer.Place> candidates = List.of();
    /** The real towns just finished loading: recount the window's candidates. */
    private volatile boolean refreshWanted;
    private final Deque<Gazetteer.Place> queue = new ArrayDeque<>();
    private CompletableFuture<ActionResult> inFlight;
    private int fillTarget, fillDone;
    private String fillStatus = "";

    @Override public String id() { return "bulkcities"; }
    @Override public String name() { return "Bulk Cities"; }
    @Override public String version() { return "1.0.0"; }
    @Override public String author() { return "Age of Clicks Mod API"; }

    @Override
    public String description() {
        return "Found many cities quickly: found-city mode stays on and every city is named for you, with the real town "
                + "at that spot or a name in the region's style. Can also fill your land with its real towns.";
    }

    @Override
    public void onLoad(ModContext ctx) {
        this.ctx = ctx;
        autoName = ctx.settings().toggle("auto_name", "Name cities automatically", true,
                "Skip the name dialog. Off: the dialog opens with the suggested name filled in.");
        keepFounding = ctx.settings().toggle("keep_founding", "Keep founding after each city", true,
                "Found-city mode stays on: click, click, click. Right-click or Esc stops.");
        realNames = ctx.settings().toggle("real_names", "Prefer real town names", true,
                "Use the real town at that spot when one is known (from the game's other maps), else make up a fitting name.");
        // In the game's Economy menu, Cities section, right under its own "Found city" row.
        ctx.ui().addEconomyCityRow("Bulk Cities",
                "Found many cities in a row, each named for you, or fill your land with its real towns.",
                null, () -> "Open", this::openWindow, enabled);
    }

    @Override
    public void onGameReady(ModContext ctx) {
        Thread t = new Thread(() -> {
            sources = Gazetteer.loadSources(ctx.gameDir());
            int towns = 0;
            for (Gazetteer.Source s : sources) towns += s.names().size();
            ctx.log("City lists: " + sources.size() + " with " + towns + " towns");
        }, "bulkcities-lists");
        t.setDaemon(true);
        t.start();

        window = buildWindow();
    }

    /** Opens the window over the Economy menu (from its row under "Found city"). */
    private void openWindow() {
        if (window == null || !ctx.game().inGame()) return;
        refreshCandidates();
        if (!window.isOpen()) ctx.ui().open(window);
    }

    /** Starts found-city mode (each click a city, named for you), or stops it. */
    private void toggleFounding() {
        MapScreen map = MapScreen.getActiveInstance();
        if (map == null || !ctx.game().inGame() || map.isCountrySelectMode()) return;
        if (foundCityMode()) {
            call(map, "cancelFoundCityMode");
        } else {
            lastName = null;
            map.startFoundCityMode();
        }
    }

    // ---- every frame ----

    @Override
    public void beforeFrame(ModContext ctx, float delta) {
        // The name dialog opens while the game handles the click, before it draws: answer it now.
        watchNameDialog();
    }

    @Override
    public void render(ModContext ctx, float delta) {
        MapScreen map = MapScreen.getActiveInstance();
        if (map == null || !ctx.game().inGame()) {
            gazetteer = null;
            gazetteerMap = null;
            givenKeys.clear();
            queue.clear();
            return;
        }
        if (map != gazetteerMap && sources != null && !map.isCountrySelectMode()) {
            gazetteerMap = map;
            givenKeys.clear();
            buildGazetteer();
        }
        if (refreshWanted && window.isOpen()) {
            refreshWanted = false;
            refreshCandidates();
        }
        watchNameDialog();
        fillStep();
    }

    private void buildGazetteer() {
        List<Gazetteer.Known> towns = towns();
        int mapW = GameManager.getInstance().getWarManager().getMapWidth();
        List<Gazetteer.Source> src = sources;
        Thread t = new Thread(() -> {
            long t0 = System.nanoTime();
            Gazetteer g = Gazetteer.build(src, towns, mapW);
            gazetteer = g;
            refreshWanted = true;
            ctx.log("Placed " + g.places.size() + " real towns on this map (" + towns.size() + " towns on it) in "
                    + (System.nanoTime() - t0) / 1_000_000 + " ms");
        }, "bulkcities-place");
        t.setDaemon(true);
        t.start();
    }

    /**
     * This map's towns in map pixels, the units of a found-city click. The game itself treats a city's
     * stored position as map pixels (CityManager.seatOf, foundCity); World.cityPixel's guessed scale can
     * be fooled by a country that owns most of the world (it put Siberian towns in Greenland). Render thread.
     */
    private List<Gazetteer.Known> towns() {
        List<Gazetteer.Known> out = new ArrayList<>();
        CityManager cm = GameManager.getInstance().getCityManager();
        if (cm == null) return out;
        List<ru.ius.david.cgs.CityData> cities = cm.getCities();
        for (int i = 0; i < cities.size(); i++) {
            ru.ius.david.cgs.CityData c = cities.get(i);
            out.add(new Gazetteer.Known(cm.getName(i), c.x, c.y));
        }
        return out;
    }

    // ---- names ----

    /** A name for a new city at map pixel (x, y): the real town there, else one in the region's style. */
    String nameFor(int x, int y) {
        List<Gazetteer.Known> towns = towns();
        Set<String> used = new HashSet<>(givenKeys);
        for (Gazetteer.Known k : towns) used.add(Gazetteer.key(k.name()));
        int mapW = GameManager.getInstance().getWarManager().getMapWidth();
        Gazetteer g = gazetteer;
        String name = null;
        if (g != null && realNames.get()) {
            Gazetteer.Place p = g.nearestFree(x, y, mapW * 0.008f, used, towns, mapW * 0.004f);
            if (p != null) name = p.name();
        }
        if (name == null) {
            List<String> around = g != null ? g.namesAround(x, y, 60, towns) : nearestNames(towns, x, y, 60);
            name = NameForge.make(around, used, x * 31L + y * 7919L + givenKeys.size());
        }
        if (name == null) name = "Settlement " + (towns.size() + 1);
        givenKeys.add(Gazetteer.key(name));
        return name;
    }

    private static List<String> nearestNames(List<Gazetteer.Known> towns, float x, float y, int count) {
        List<Gazetteer.Known> sorted = new ArrayList<>(towns);
        sorted.sort((a, b) -> Double.compare(Math.hypot(a.x() - x, a.y() - y), Math.hypot(b.x() - x, b.y() - y)));
        List<String> out = new ArrayList<>();
        for (int i = 0; i < Math.min(count, sorted.size()); i++) out.add(sorted.get(i).name());
        return out;
    }

    // ---- the game's name dialog ----

    private void watchNameDialog() {
        MapScreen map = MapScreen.getActiveInstance();
        if (map == null) return;
        CGSTextInputDialog dialog = (CGSTextInputDialog) get(map, "textInputDialog");
        if (dialog == null) return;
        if (!dialog.isVisible()) {
            if (rearm) {
                // The player confirmed the filled-in name; the dialog has closed.
                rearm = false;
                if (!foundCityMode()) map.startFoundCityMode();
            }
            handled = null;
            return;
        }
        Object callback = get(dialog, "callback");
        if (callback == null || callback == handled) return;
        if (!LanguageManager.get("city_found_name_title").equals(get(dialog, "title"))) return;
        Integer x = (Integer) get(callback, "val$px"), y = (Integer) get(callback, "val$py");
        if (x == null || y == null) return; // not the city dialog we know
        handled = callback;
        String name = nameFor(x, y);
        CGSTextInputDialog.OnInputResult original = (CGSTextInputDialog.OnInputResult) callback;
        if (autoName.get()) {
            // The game checks the spot and gold again and submits the city (it may apply a tick later).
            dialog.hide();
            original.onResult(name);
            lastName = name;
            ctx.ui().toast(name, 2);
            if (keepFounding.get() && !foundCityMode()) map.startFoundCityMode();
        } else {
            // Fill in the name and see whether the player confirms it (perhaps edited) or cancels.
            set(dialog, "text", name);
            CGSTextInputDialog.OnInputResult watched = new CGSTextInputDialog.OnInputResult() {
                @Override
                public void onResult(String typed) {
                    original.onResult(typed);
                            lastName = typed;
                    givenKeys.add(Gazetteer.key(typed));
                    rearm = keepFounding.get();
                }

                @Override
                public void onCancel() { original.onCancel(); }
            };
            set(dialog, "callback", watched);
            handled = watched;
        }
    }

    private boolean foundCityMode() {
        MapScreen map = MapScreen.getActiveInstance();
        Object v = map == null ? null : get(map, "foundCityMode");
        return Boolean.TRUE.equals(v);
    }

    private static int cityCount() {
        CityManager cm = GameManager.getInstance().getCityManager();
        return cm == null ? 0 : cm.getCityCount();
    }

    // ---- filling the land with its real towns ----

    private Window buildWindow() {
        Window w = new Window("Bulk Cities", 500, 360);
        w.content().gap(8).add(
                new Label("Found the real towns of your land at their real places, as many as you choose and can afford. "
                        + "The game's own rules and costs apply."),
                new Label(this::fillSummary).muted(),
                Panel.row().gap(8).add(
                        new Button("Found 5").onClick(() -> startFill(5)).enabled(this::canFill),
                        new Button("Found 20").onClick(() -> startFill(20)).enabled(this::canFill),
                        new Button("All I can afford").primary().onClick(() -> startFill(Integer.MAX_VALUE)).enabled(this::canFill)),
                new Label(() -> fillStatus),
                Separator.spacer().flex(1),
                new Separator(),
                new Label(() -> "By hand: click the game's Found City button (or the one below), then click the map; "
                        + "each city is named for you. Right-click or Esc stops.").muted(),
                Panel.row().gap(8).add(
                        new Button("Found by hand").onClick(() -> {
                            ctx.ui().close(w);
                            if (!foundCityMode()) toggleFounding();
                        }),
                        new Button("Refresh").onClick(this::refreshCandidates),
                        new Button("Close").onClick(w::close)));
        return w;
    }

    private boolean canFill() { return queue.isEmpty() && inFlight == null && gazetteer != null; }

    private String fillSummary() {
        Gazetteer g = gazetteer;
        if (sources == null || g == null) return "Reading the game's city lists...";
        CountryState me = GameManager.getInstance().getPlayerCountry();
        CityManager cm = GameManager.getInstance().getCityManager();
        MapScreen map = MapScreen.getActiveInstance();
        if (me == null || cm == null || map == null) return "";
        long cost = cm.getFoundCityCost(map.getPlayerColorKey());
        return candidates.size() + " real towns fit in your land.  Next city costs " + fmt(cost) + " gold; you have " + fmt(me.getGold()) + ".";
    }

    private static String fmt(long v) {
        if (v >= 1_000_000) return String.format(java.util.Locale.ROOT, "%.1fM", v / 1e6);
        if (v >= 10_000) return Math.round(v / 1e3) + "k";
        return Long.toString(v);
    }

    /** Real towns in the player's land where a city may be founded now. Render thread. */
    private void refreshCandidates() {
        Gazetteer g = gazetteer;
        MapScreen map = MapScreen.getActiveInstance();
        if (g == null || map == null) {
            candidates = List.of();
            return;
        }
        GameManager gm = GameManager.getInstance();
        CityManager cm = gm.getCityManager();
        int me = map.getPlayerColorKey();
        List<Gazetteer.Known> towns = towns();
        Set<String> used = new HashSet<>(givenKeys);
        for (Gazetteer.Known k : towns) used.add(Gazetteer.key(k.name()));
        float taken = gm.getWarManager().getMapWidth() * 0.004f;
        List<Gazetteer.Place> out = new ArrayList<>();
        for (Gazetteer.Place p : g.places) {
            int x = Math.round(p.x()), y = Math.round(p.y());
            if (used.contains(p.key()) || gm.getWarManager().getCountryAt(x, y) != me || gm.getWarManager().isWaterAt(x, y)) continue;
            if (Gazetteer.occupied(p, towns, taken) || !cm.canFoundAt(x, y)) continue;
            out.add(p);
        }
        candidates = out; // best known towns first (the list is sorted that way)
    }

    private void startFill(int count) {
        refreshCandidates();
        queue.clear();
        queue.addAll(candidates);
        fillTarget = count;
        fillDone = 0;
        fillStatus = queue.isEmpty() ? "No free spots for real towns in your land." : "Founding...";
    }

    /** One city per frame; a city that no longer fits (a new neighbour, no gold) is skipped. */
    private void fillStep() {
        if (inFlight != null) {
            if (!inFlight.isDone()) return;
            inFlight = null;
        }
        if (queue.isEmpty()) return;
        GameManager gm = GameManager.getInstance();
        MapScreen map = MapScreen.getActiveInstance();
        CountryState me = gm.getPlayerCountry();
        if (map == null || me == null) {
            queue.clear();
            return;
        }
        long cost = gm.getCityManager().getFoundCityCost(map.getPlayerColorKey());
        if (fillDone >= fillTarget || me.getGold() < cost) {
            finishFill(me.getGold() < cost && fillDone < fillTarget ? " (out of gold)" : "");
            return;
        }
        Gazetteer.Place p = queue.poll();
        int x = Math.round(p.x()), y = Math.round(p.y());
        if (givenKeys.contains(p.key()) || !gm.getCityManager().canFoundAt(x, y)
                || gm.getWarManager().getCountryAt(x, y) != map.getPlayerColorKey()) return;
        int before = cityCount();
        givenKeys.add(p.key());
        inFlight = ctx.actions().performGameAction("FoundCityAction", me.getName(), Map.of("x", x, "y", y, "name", p.name()), false)
                .thenApply(r -> {
                    if (r.ok() && cityCount() > before) {
                        fillDone++;
                        lastName = p.name();
                        fillStatus = "Founded " + fillDone + (fillTarget == Integer.MAX_VALUE ? "" : " of " + fillTarget) + ": " + p.name();
                    }
                    return r;
                });
        if (queue.isEmpty()) finishFill("");
    }

    private void finishFill(String why) {
        queue.clear();
        fillStatus = fillDone == 0 ? "No city founded" + why + "." : "Founded " + fillDone + " real towns" + why + ".";
        if (fillDone > 0) ctx.ui().toast(fillStatus, 4);
        refreshCandidates();
    }

    // ---- reflection ----

    private static Object get(Object o, String field) {
        try {
            Field f = find(o.getClass(), field);
            return f == null ? null : f.get(o);
        } catch (IllegalAccessException e) {
            return null;
        }
    }

    private static void set(Object o, String field, Object value) {
        try {
            Field f = find(o.getClass(), field);
            if (f != null) f.set(o, value);
        } catch (IllegalAccessException ignored) {
            // the game changed; the dialog stays empty
        }
    }

    private static Field find(Class<?> c, String name) {
        for (; c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
                // superclass next
            }
        }
        return null;
    }

    private static void call(Object o, String method) {
        try {
            Method m = o.getClass().getDeclaredMethod(method);
            m.setAccessible(true);
            m.invoke(o);
        } catch (ReflectiveOperationException ignored) {
            // nothing to stop
        }
    }
}
