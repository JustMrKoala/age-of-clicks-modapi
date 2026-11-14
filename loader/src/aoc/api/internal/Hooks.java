package aoc.api.internal;

import com.badlogic.gdx.InputProcessor;

/** Static entry points called from the bytecode the loader inserts into the game. */
public final class Hooks {
    private static volatile Thread renderThread;
    private static final RootInput ROOT = new RootInput();

    private Hooks() {}

    /** The game's render (GL) thread, known after the first frame. */
    public static Thread renderThread() { return renderThread; }

    /** Start of {@code cgsGame.render()}. */
    public static void beforeRender() {
        if (renderThread != null) ModManager.beforeFrame();
    }

    /** End of {@code cgsGame.render()}. */
    public static void afterRender() {
        renderThread = Thread.currentThread();
        ModManager.onFrame();
    }

    /**
     * Buttons mods added with {@code Ui.addMainMenuButton}, in the order they came. The first ones get
     * a main menu row under MODS; the main menu holds at most {@link #MENU_ROWS} loader rows (every
     * extra row pushes the game's own rows, EXIT last, off smaller screens), so the rest sit at the top
     * of the Code Mods window. Code Mods itself is in the game's MODS screen ({@code ModsScreenButton}).
     */
    static final java.util.List<MainMenuInjector.Row> MOD_ROWS = new java.util.concurrent.CopyOnWriteArrayList<>();
    static final int MENU_ROWS = 2;

    /** How many of {@link #MOD_ROWS} get a main menu row (MULTIPLAYER, when enabled, takes one slot). */
    static int modRowsInMenu() {
        return Math.min(MOD_ROWS.size(), MENU_ROWS - (LoaderMod.experimentalMultiplayer() ? 1 : 0));
    }

    /** End of {@code MainMenuScreen.buildMenu()}: mods' rows, then the multiplayer window if enabled. */
    public static void afterMainMenuBuilt(Object screen) {
        java.util.List<MainMenuInjector.Row> rows = new java.util.ArrayList<>();
        for (MainMenuInjector.Row r : MOD_ROWS.subList(0, modRowsInMenu())) {
            rows.add(new MainMenuInjector.Row(r.label().toUpperCase(java.util.Locale.ROOT), r.onClick()));
        }
        if (LoaderMod.experimentalMultiplayer()) {
            rows.add(new MainMenuInjector.Row("MULTIPLAYER", ModManager::openMultiplayerTest));
        }
        MainMenuInjector.inject(screen, rows);
    }

    /** A mod's launcher: a main menu row while there is room, else a button at the top of Code Mods. */
    public static void addMainMenuRow(String label, Runnable onClick) {
        MOD_ROWS.add(new MainMenuInjector.Row(label, onClick));
    }

    // ---- map camera (aoc.api.MapCamera) ----

    private static volatile float zoomOutFactor = 1f, edgeMargin = 0f;
    private static java.lang.reflect.Field mapMaxZoom, mapCamera;

    /** New limits from {@code MapCamera}; applied to an open map at once. Render thread. */
    public static void setMapCamera(float zoomOut, float margin) {
        zoomOutFactor = zoomOut;
        edgeMargin = margin;
        // Before the first frame (a mod's onLoad) no map exists, and loading MapScreen that early is unsafe.
        if (renderThread == null) return;
        ru.ius.david.cgs.MapScreen map = ru.ius.david.cgs.MapScreen.getActiveInstance();
        if (map == null) return;
        try {
            java.lang.reflect.Method m = map.getClass().getDeclaredMethod("calculateZoomThresholds");
            m.setAccessible(true);
            m.invoke(map);
        } catch (ReflectiveOperationException e) {
            Log.error("Could not apply the new zoom limit", e);
        }
    }

    /** End of {@code MapScreen.calculateZoomThresholds()}: raises the zoom-out cap ({@code maxZoom}). */
    public static void afterZoomThresholds(Object map) {
        if (zoomOutFactor == 1f || ModManager.multiplayerActive()) return;
        try {
            if (mapMaxZoom == null) {
                mapMaxZoom = map.getClass().getDeclaredField("maxZoom");
                mapMaxZoom.setAccessible(true);
            }
            mapMaxZoom.setFloat(map, mapMaxZoom.getFloat(map) * zoomOutFactor);
        } catch (ReflectiveOperationException e) {
            Log.error("Could not raise the zoom-out limit", e);
        }
    }

    /**
     * Replaces {@code MathUtils.clamp} in {@code MapScreen.updateCameraBounds()}, which keeps the
     * view inside the map. Widens the range by {@code edgeMargin} of the view, so the map's edges
     * can be brought towards the middle of the screen.
     */
    public static float clampCamera(float value, float min, float max) {
        float m = edgeMargin;
        if (m > 0f && !ModManager.multiplayerActive()) {
            float view = viewSize();
            min -= view * m;
            max += view * m;
        }
        return value < min ? min : (value > max ? max : value);
    }

    /**
     * Draws the game's own map (no interface) as seen from another camera, into the bound
     * framebuffer at the window's size. {@code x}/{@code y} are map pixels (y down),
     * {@code visibleHeight} is how many map pixels fit vertically. The player's camera is restored.
     */
    public static boolean renderMapView(float x, float y, float visibleHeight) { return renderMapView(x, y, visibleHeight, false); }

    /**
     * {@code clean}: a neutral documentary view instead of the player's (see {@link FilmView}): political
     * colours, no selection, hover, overlays or half-drawn arrows, unit flags with troop counts but no
     * troop sprites, besieged cities' garrison bars. Everything is put back afterwards.
     */
    public static boolean renderMapView(float x, float y, float visibleHeight, boolean clean) {
        ru.ius.david.cgs.MapScreen map = ru.ius.david.cgs.MapScreen.getActiveInstance();
        com.badlogic.gdx.graphics.OrthographicCamera cam = camera();
        if (map == null || cam == null || cam.viewportHeight <= 0) return false;
        return FilmView.render(map, cam, x, y, visibleHeight, clean);
    }

    /** Milliseconds the last film pass spent on the map and on flags/garrisons. */
    public static float[] filmPassTimes() { return new float[]{FilmView.lastMapMs, FilmView.lastExtrasMs}; }

    /** A mod's self-test turns on extra logging of the film pass. */
    public static void setFilmDebug(boolean on) { FilmView.debug = on; }

    /** Replaces the country colour fade in TerritoryRenderer.renderLayers: instant during a film pass. */
    public static com.badlogic.gdx.graphics.Color filmLerp(com.badlogic.gdx.graphics.Color self, com.badlogic.gdx.graphics.Color target, float t) {
        return self.lerp(target, FilmView.active ? 1f : t);
    }

    /** True during a clean film pass; checked at the start of methods the loader switches off for it. */
    public static boolean filmPass() { return FilmView.active; }

    /** Current camera zoom of the open map (larger = further out), 0 without a map. */
    public static float mapZoom() {
        com.badlogic.gdx.graphics.OrthographicCamera cam = camera();
        return cam == null ? 0f : cam.zoom;
    }

    /** The smaller side of the visible map area, in map units. */
    private static float viewSize() {
        com.badlogic.gdx.graphics.OrthographicCamera cam = camera();
        return cam == null ? 0f : Math.min(cam.viewportWidth, cam.viewportHeight) * cam.zoom;
    }

    private static java.lang.reflect.Field mapWorldBounds;
    private static final com.badlogic.gdx.math.Vector3 PROJECTED = new com.badlogic.gdx.math.Vector3();

    /**
     * Where a map point (map pixels, y down, like the ownership grid) is on screen under the player's
     * camera: {@code out} gets x and y in y-down window pixels. False when no map is open. Render thread.
     */
    public static boolean mapToScreen(float mapX, float mapY, float[] out) {
        ru.ius.david.cgs.MapScreen map = ru.ius.david.cgs.MapScreen.getActiveInstance();
        com.badlogic.gdx.graphics.OrthographicCamera cam = camera();
        if (map == null || cam == null) return false;
        try {
            if (mapWorldBounds == null) {
                mapWorldBounds = map.getClass().getDeclaredField("worldBounds");
                mapWorldBounds.setAccessible(true);
            }
            float worldH = ((com.badlogic.gdx.math.Rectangle) mapWorldBounds.get(map)).height;
            cam.project(PROJECTED.set(mapX, worldH - mapY, 0));
            out[0] = PROJECTED.x;
            out[1] = com.badlogic.gdx.Gdx.graphics.getHeight() - PROJECTED.y;
            return true;
        } catch (ReflectiveOperationException e) {
            return false;
        }
    }

    static com.badlogic.gdx.graphics.OrthographicCamera camera() {
        ru.ius.david.cgs.MapScreen map = ru.ius.david.cgs.MapScreen.getActiveInstance();
        if (map == null) return null;
        try {
            if (mapCamera == null) {
                mapCamera = map.getClass().getDeclaredField("camera");
                mapCamera.setAccessible(true);
            }
            return (com.badlogic.gdx.graphics.OrthographicCamera) mapCamera.get(map);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    // ---- multiplayer ----

    private static volatile int clientTcpPort = 54555, clientUdpPort = 54777;

    /** Ports the game client connects to next; central-server lobbies each have their own. */
    public static void setClientPorts(int tcp, int udp) {
        clientTcpPort = tcp;
        clientUdpPort = udp;
    }

    public static int clientTcpPort() { return clientTcpPort; }

    public static int clientUdpPort() { return clientUdpPort; }

    /** Replaces {@code SteamManager.isAvailable()} inside the multiplayer lobby. */
    public static boolean lobbySteamAvailable() {
        return LoaderMod.steamNetworkingAllowed() && ru.ius.david.cgs.steam.SteamManager.isAvailable();
    }

    /** Replaces calls to {@code cgsGame.joinSteamLobbyDirectly(id)} (Steam invites). */
    public static void joinSteamLobby(Object game, Object steamId) {
        if (!LoaderMod.steamNetworkingAllowed()) {
            Log.info("Ignored a Steam lobby invite: Steam networking is off (Code Mods > Age of Clicks Mod Loader)");
            return;
        }
        invoke(game, "joinSteamLobbyDirectly", steamId);
    }

    /** Replaces calls to {@code MultiplayerLobbyScreen.joinLobbyDirectly(id)} (Steam invites). */
    public static void joinLobbyDirectly(Object lobby, Object steamId) {
        if (!LoaderMod.steamNetworkingAllowed()) {
            Log.info("Ignored a Steam lobby join: Steam networking is off (Code Mods > Age of Clicks Mod Loader)");
            return;
        }
        invoke(lobby, "joinLobbyDirectly", steamId);
    }

    private static void invoke(Object target, String method, Object steamId) {
        try {
            for (java.lang.reflect.Method m : target.getClass().getMethods()) {
                if (m.getName().equals(method) && m.getParameterCount() == 1) {
                    m.invoke(target, steamId);
                    return;
                }
            }
        } catch (Throwable t) {
            Log.error("Steam lobby join failed", t);
        }
    }

    /** Start of {@code DefaultLwjgl3Input.setInputProcessor(p)}. */
    public static InputProcessor wrapInput(InputProcessor p) {
        if (p instanceof RootInput) return p;
        ROOT.delegate = p;
        return ROOT;
    }

    /** Return value of {@code DefaultLwjgl3Input.getInputProcessor()}. */
    public static InputProcessor unwrapInput(InputProcessor p) {
        return p instanceof RootInput ? ((RootInput) p).delegate : p;
    }
}
