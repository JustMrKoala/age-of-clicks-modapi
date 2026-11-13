package aoc.api.internal;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Rectangle;
import com.badlogic.gdx.utils.IntArray;
import ru.ius.david.cgs.MapModeManager;
import ru.ius.david.cgs.MapScreen;
import ru.ius.david.cgs.game.GameManager;
import ru.ius.david.cgs.units.FrontlineDecor.Marker;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

/**
 * Draws the game's own map once more from another camera, as a neutral documentary view instead
 * of the player's: political colours (snapped, not fading from the player's map mode), no
 * selected or targeted country, no hover, no data overlays, no arrow that is still being aimed or
 * painted; then, from the game's interface pass, only unit flags with troop counts (the troop
 * sprites themselves are switched off by the loader during this pass) and besieged cities'
 * garrison bars. Everything the player sees is put back afterwards.
 */
final class FilmView {
    /** True while the film pass draws; {@code Hooks.filmPass()} reads it for the skipped methods. */
    static volatile boolean active;

    private static final String[] MAP_INTS = {"selectedCountryColorKey", "selectedCityIdx", "hoverDivisionId", "hoverMarker", "planAimStage"};
    private static final Map<String, Field> FIELDS = new HashMap<>();
    private static Method mapFrame, updateLut, siegeRings, unitFlags, frontDecor;
    private static boolean extrasFailed, snapFailed;
    private static int debugPasses;

    /** Milliseconds of the last pass: map, then flags and garrisons. */
    static volatile float lastMapMs, lastExtrasMs;
    /** Set by a mod's self-test: log what each pass drew. */
    static volatile boolean debug;

    private FilmView() {}

    static boolean render(MapScreen map, OrthographicCamera cam, float x, float y, float visibleHeight, boolean clean) {
        float px = cam.position.x, py = cam.position.y, pz = cam.zoom;
        Saved saved = clean ? neutralise(map) : null;
        try {
            if (mapFrame == null) mapFrame = method(map, "renderMapFrame", float.class, long.class, boolean.class);
            float worldH = ((Rectangle) field(map, "worldBounds").get(map)).height;
            cam.position.set(x, worldH - y, cam.position.z);
            cam.zoom = visibleHeight / cam.viewportHeight;
            cam.update();
            Gdx.gl.glViewport(0, 0, Gdx.graphics.getBackBufferWidth(), Gdx.graphics.getBackBufferHeight());
            active = clean;
            long t0 = System.nanoTime();
            mapFrame.invoke(map, 0f, System.currentTimeMillis(), false);
            long t1 = System.nanoTime();
            if (clean) drawExtras(map);
            lastMapMs = (t1 - t0) / 1e6f;
            lastExtrasMs = (System.nanoTime() - t1) / 1e6f;
            return true;
        } catch (ReflectiveOperationException e) {
            Log.error("Could not draw the map from another camera", e);
            return false;
        } finally {
            active = false;
            cam.position.set(px, py, cam.position.z);
            cam.zoom = pz;
            cam.update();
            if (saved != null) restore(map, saved);
        }
    }

    /** Unit flags with troop counts and besieged cities' garrison bars, drawn like the interface pass does. */
    private static void drawExtras(MapScreen map) {
        if (extrasFailed) return;
        SpriteBatch batch = null;
        try {
            if (siegeRings == null) {
                siegeRings = method(map, "drawSiegeRings", ru.ius.david.cgs.game.CityManager.class);
                unitFlags = method(map, "drawUnitFlagsAndRallyPoint", boolean.class);
                frontDecor = method(map, "drawFrontlineDecorAndFlares", boolean.class);
            }
            batch = (SpriteBatch) field(map, "batch").get(map);
            batch.setProjectionMatrix((Matrix4) field(map, "uiProjection").get(map));
            batch.setColor(Color.WHITE);
            batch.begin();
            GameManager gm = GameManager.getInstance();
            // The game's own argument means "hide units" (it passes true in the Economy map mode).
            // Classic combat: army flags with troop counts, sparks and flares along the fronts.
            frontDecor.invoke(map, false);
            if (gm != null && gm.getCityManager() != null) siegeRings.invoke(map, gm.getCityManager());
            // Division combat: unit flags with troop counts (the player's rally point is taken away for the pass).
            unitFlags.invoke(map, false);
            if (debug && debugPasses++ % 60 == 0) {
                ru.ius.david.cgs.WarManager wm = gm == null ? null : gm.getWarManager();
                int units = wm == null || wm.getUnitManager() == null ? -1 : wm.getUnitManager().getUnits().size();
                int besieged = 0;
                ru.ius.david.cgs.game.CityManager cm = gm == null ? null : gm.getCityManager();
                if (cm != null) for (int i = 0; i < cm.getCityCount(); i++) if (cm.isBesieged(i)) besieged++;
                Log.info("Film debug: units " + units + ", army flags setting " + ru.ius.david.cgs.game.SettingsManager.getInstance().showArmyFlags()
                        + ", classic combat " + (gm != null && gm.isClassicCombat()) + ", besieged cities " + besieged
                        + ", map " + lastMapMs + " ms");
            }
        } catch (Throwable t) {
            extrasFailed = true; // keep filming the map without them
            Log.error("Film: unit flags / city bars could not be drawn; filming without them", t);
        } finally {
            if (batch != null && batch.isDrawing()) batch.end();
        }
    }

    // ---- player state that is hidden for the film and put back ----

    private static final class Saved {
        MapModeManager.MapMode mode;
        boolean growth, deposits, colonies, disableHighlight, onlySelected, brush;
        int selectTarget, rallyOwner;
        Object rally;
        double fxClock;
        float fxStep;
        Map<Marker, float[]> markerXY;
        int[] ints = new int[MAP_INTS.length];
        int[] divisions, strokeX, strokeY;
        float[] colors, polygonColors;
    }

    private static Saved neutralise(MapScreen map) {
        Saved s = new Saved();
        MapModeManager modes = MapModeManager.getInstance();
        s.mode = modes.getMapMode();
        s.growth = modes.isGrowthPixelMapEnabled();
        s.deposits = modes.isDepositPixelMapEnabled();
        s.colonies = modes.isColonizationOverlayEnabled();
        s.selectTarget = modes.getCountrySelectTarget();
        modes.setMapMode(MapModeManager.MapMode.POLITICAL);
        modes.setColonizationOverlayEnabled(false);
        modes.setCountrySelectTarget(-1);
        s.disableHighlight = swapBool(modes, "disableBorderHighlight", true);
        s.onlySelected = swapBool(modes, "onlySelectedCountry", false);
        for (int i = 0; i < MAP_INTS.length; i++) s.ints[i] = swapInt(map, MAP_INTS[i], MAP_INTS[i].equals("planAimStage") ? 0 : -1);
        s.brush = swapBool(map, "defenceBrushDrawing", false);
        s.divisions = take(map, "selectedDivisions");
        s.strokeX = take(map, "defenceStrokeX");
        s.strokeY = take(map, "defenceStrokeY");
        s.colors = snapColours(map);
        s.polygonColors = polygonColours(map, null);
        try {
            // The unit effects clock advances by the frame time on every call; keep the player's pace.
            s.fxClock = field(map, "unitFxClock").getDouble(map);
            s.fxStep = field(map, "unitFxStep").getFloat(map);
            s.rallyOwner = field(map, "playerColorKey").getInt(map);
            s.rally = rallyPoints(map).remove(s.rallyOwner);
        } catch (ReflectiveOperationException | RuntimeException e) {
            s.rally = null;
        }
        s.markerXY = placeArmyFlags();
        return s;
    }

    private static void restore(MapScreen map, Saved s) {
        MapModeManager modes = MapModeManager.getInstance();
        if (s.mode != null) modes.setMapMode(s.mode);
        modes.setGrowthPixelMapEnabled(s.growth);
        modes.setDepositPixelMapEnabled(s.deposits);
        modes.setColonizationOverlayEnabled(s.colonies);
        modes.setCountrySelectTarget(s.selectTarget);
        swapBool(modes, "disableBorderHighlight", s.disableHighlight);
        swapBool(modes, "onlySelectedCountry", s.onlySelected);
        for (int i = 0; i < MAP_INTS.length; i++) swapInt(map, MAP_INTS[i], s.ints[i]);
        swapBool(map, "defenceBrushDrawing", s.brush);
        give(map, "selectedDivisions", s.divisions);
        give(map, "defenceStrokeX", s.strokeX);
        give(map, "defenceStrokeY", s.strokeY);
        restoreColours(map, s.colors);
        polygonColours(map, s.polygonColors);
        try {
            field(map, "unitFxClock").setDouble(map, s.fxClock);
            field(map, "unitFxStep").setFloat(map, s.fxStep);
            if (s.rally != null) rallyPoints(map).put(s.rallyOwner, s.rally);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // fields missing in another game version
        }
        restoreArmyFlags(s.markerXY);
    }

    /**
     * Classic combat army flags are drawn where each front marker's troop motion stands. That motion
     * only moves while the player's own view draws the troops there (not when zoomed out, and it is
     * frozen during the film pass), so the film puts every flag on the marker itself, which the game
     * moves smoothly along the front.
     * <p>
     * The game also fades out flags that crowd each other at the current zoom (thin, thinShown and
     * thinHold on each marker, eased over time). The player's camera and the film's would fight over
     * that state and the film's flags kept fading in and out, so the film keeps its own copy.
     * Returns the player's values to put back.
     */
    private static Map<Marker, float[]> placeArmyFlags() {
        java.util.List<Marker> markers = frontMarkers();
        if (markers == null) return null;
        Map<Marker, float[]> saved = new java.util.IdentityHashMap<>();
        for (Marker m : markers) {
            float mx = m.motion != null ? m.motion.x : 0f, my = m.motion != null ? m.motion.y : 0f;
            saved.put(m, new float[]{mx, my, m.thin, m.thinShown ? 1f : 0f, thinHold(m)});
            if (m.motion != null) {
                m.motion.x = m.x;
                m.motion.y = m.y;
            }
            float[] film = FILM_THIN.get(m);
            m.thin = film != null ? film[0] : 1f;
            m.thinShown = film == null || film[1] > 0f;
            setThinHold(m, film != null ? film[2] : 0f);
        }
        return saved;
    }

    private static void restoreArmyFlags(Map<Marker, float[]> saved) {
        if (saved == null) return;
        for (Map.Entry<Marker, float[]> e : saved.entrySet()) {
            Marker m = e.getKey();
            float[] v = e.getValue();
            FILM_THIN.put(m, new float[]{m.thin, m.thinShown ? 1f : 0f, thinHold(m)});
            if (m.motion != null) {
                m.motion.x = v[0];
                m.motion.y = v[1];
            }
            m.thin = v[2];
            m.thinShown = v[3] > 0f;
            setThinHold(m, v[4]);
        }
    }

    /** The film's flag thinning per marker; markers that left the front drop out by themselves. */
    private static final Map<Marker, float[]> FILM_THIN = new java.util.WeakHashMap<>();
    private static Field thinHoldField;
    private static boolean thinHoldMissing;

    private static float thinHold(Marker m) {
        Field f = thinHoldField();
        try {
            return f == null ? 0f : f.getFloat(m);
        } catch (IllegalAccessException e) {
            return 0f;
        }
    }

    private static void setThinHold(Marker m, float v) {
        Field f = thinHoldField();
        try {
            if (f != null) f.setFloat(m, v);
        } catch (IllegalAccessException ignored) {
            // thinning eases a little differently then
        }
    }

    private static Field thinHoldField() {
        if (thinHoldField == null && !thinHoldMissing) {
            try {
                thinHoldField = Marker.class.getDeclaredField("thinHold");
                thinHoldField.setAccessible(true);
            } catch (ReflectiveOperationException | RuntimeException e) {
                thinHoldMissing = true;
            }
        }
        return thinHoldField;
    }

    private static java.util.List<Marker> frontMarkers() {
        GameManager gm = GameManager.getInstance();
        ru.ius.david.cgs.WarManager wm = gm == null ? null : gm.getWarManager();
        ru.ius.david.cgs.units.FrontlineDecor decor = wm == null ? null : wm.getFrontlineDecor();
        return decor == null ? null : decor.getMarkers();
    }

    /** WarManager's rally points by country colour key (the player's is drawn as a marker). */
    @SuppressWarnings("unchecked")
    private static Map<Integer, Object> rallyPoints(MapScreen map) throws ReflectiveOperationException {
        Object wm = field(map, "warManager").get(map);
        if (wm == null) return new HashMap<>();
        return (Map<Integer, Object>) field(wm, "rallyPoints").get(wm);
    }

    /**
     * The mesh fill keeps a fading colour per country polygon. With {@code restore} null this saves
     * them (the film pass then makes them political at once); otherwise it puts the saved values back,
     * so the player's view does not fade.
     */
    private static float[] polygonColours(MapScreen map, float[] restore) {
        try {
            Object tr = field(map, "territoryRenderer").get(map);
            if (tr == null) return null;
            @SuppressWarnings("unchecked")
            java.util.List<ru.ius.david.cgs.CountryPolygonExtractor.CountryPolygon> polys =
                    (java.util.List<ru.ius.david.cgs.CountryPolygonExtractor.CountryPolygon>) field(tr, "polygons").get(tr);
            if (polys == null) return null;
            float[] out = restore != null ? restore : new float[polys.size() * 4];
            for (int i = 0; i < polys.size() && i * 4 + 3 < out.length; i++) {
                Color c = polys.get(i).currentColor;
                if (c == null) continue;
                if (restore == null) {
                    out[i * 4] = c.r;
                    out[i * 4 + 1] = c.g;
                    out[i * 4 + 2] = c.b;
                    out[i * 4 + 3] = c.a;
                } else {
                    c.set(out[i * 4], out[i * 4 + 1], out[i * 4 + 2], out[i * 4 + 3]);
                }
            }
            return out;
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    /**
     * Country colours fade towards the current map mode by the frame's time step, and the film pass
     * runs with a step of 0, so they would stay in the player's mode. Save them, let the renderer jump
     * straight to the political colours, and give the old values back afterwards.
     */
    private static float[] snapColours(MapScreen map) {
        try {
            Object tr = field(map, "territoryRenderer").get(map);
            if (tr == null) return null;
            Color[] cur = (Color[]) field(tr, "countryCurrentColors").get(tr);
            if (cur == null) return null;
            float[] copy = new float[cur.length * 4];
            for (int i = 0; i < cur.length; i++) {
                if (cur[i] == null) continue;
                copy[i * 4] = cur[i].r;
                copy[i * 4 + 1] = cur[i].g;
                copy[i * 4 + 2] = cur[i].b;
                copy[i * 4 + 3] = cur[i].a;
            }
            if (updateLut == null) updateLut = method(tr, "updateLutColors", float.class);
            updateLut.invoke(tr, 1000f);
            if (debug) Log.info("Film debug: colours snapped for " + cur.length + " countries");
            return copy;
        } catch (ReflectiveOperationException e) {
            if (!snapFailed) {
                snapFailed = true;
                Log.error("Film: could not switch the colours to the political map", e);
            }
            return null;
        }
    }

    private static void restoreColours(MapScreen map, float[] copy) {
        if (copy == null) return;
        try {
            Object tr = field(map, "territoryRenderer").get(map);
            Color[] cur = (Color[]) field(tr, "countryCurrentColors").get(tr);
            for (int i = 0; i < cur.length && i * 4 + 3 < copy.length; i++) {
                if (cur[i] != null) cur[i].set(copy[i * 4], copy[i * 4 + 1], copy[i * 4 + 2], copy[i * 4 + 3]);
            }
        } catch (ReflectiveOperationException ignored) {
            // the next frame's fade corrects it
        }
    }

    // ---- reflection helpers ----

    private static Field field(Object o, String name) throws NoSuchFieldException {
        String key = o.getClass().getName() + "#" + name;
        Field f = FIELDS.get(key);
        if (f == null) {
            f = o.getClass().getDeclaredField(name);
            f.setAccessible(true);
            FIELDS.put(key, f);
        }
        return f;
    }

    private static Method method(Object o, String name, Class<?>... args) throws NoSuchMethodException {
        Method m = o.getClass().getDeclaredMethod(name, args);
        m.setAccessible(true);
        return m;
    }

    private static int swapInt(Object o, String name, int value) {
        try {
            Field f = field(o, name);
            int old = f.getInt(o);
            f.setInt(o, value);
            return old;
        } catch (ReflectiveOperationException e) {
            return value;
        }
    }

    private static boolean swapBool(Object o, String name, boolean value) {
        try {
            Field f = field(o, name);
            boolean old = f.getBoolean(o);
            f.setBoolean(o, value);
            return old;
        } catch (ReflectiveOperationException e) {
            return value;
        }
    }

    /** Empties an IntArray field and returns its contents (null if missing). */
    private static int[] take(Object o, String name) {
        try {
            IntArray a = (IntArray) field(o, name).get(o);
            if (a == null) return null;
            int[] copy = a.toArray();
            a.clear();
            return copy;
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    private static void give(Object o, String name, int[] values) {
        if (values == null) return;
        try {
            IntArray a = (IntArray) field(o, name).get(o);
            if (a != null) {
                a.clear();
                a.addAll(values);
            }
        } catch (ReflectiveOperationException ignored) {
            // field missing
        }
    }
}
