package aoc.api.internal;

import aoc.api.AocMod;
import aoc.api.GameApi;
import aoc.api.ModContext;
import aoc.api.Overlay;
import aoc.api.actions.Actions;
import aoc.api.ui.Ui;
import aoc.api.world.World;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.InputProcessor;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.ServiceLoader;
import java.util.concurrent.CopyOnWriteArrayList;

/** Finds mods in {@code <game>/mods/*.jar} and drives their callbacks. */
public final class ModManager {
    /** A mod whose callbacks throw this many times is switched off. */
    private static final int MAX_ERRORS = 20;

    private static final class Loaded {
        final AocMod mod;
        final ModContext ctx;
        /** The jar the mod came from; null for the loader's own entry. */
        final File jar;
        int errors;

        Loaded(AocMod mod, ModContext ctx, File jar) {
            this.mod = mod;
            this.ctx = ctx;
            this.jar = jar;
        }
    }

    private static File gameDir;
    private static final GameApi GAME = new GameApi();
    private static final World WORLD = new World();
    private static final Actions ACTIONS = new Actions(GAME, WORLD);
    private static final List<Loaded> MODS = new CopyOnWriteArrayList<>();
    private static final List<InputProcessor> INPUTS = new CopyOnWriteArrayList<>();
    private static Overlay overlay;
    private static boolean ready;
    /** The loader's own windows (Code Mods); drawn above every mod. */
    private static final Ui SYSTEM_UI = new Ui();
    private static CodeModsScreen codeMods;
    private static ModBrowser modBrowser;
    private static UiSelfTest uiSelfTest;
    private static MultiplayerTestWindow multiplayerTest;
    /** Developer test of the online start flow; null unless mods/aoc-loader/mptest.properties exists. */
    private static MultiplayerSelfTest mpSelfTest;
    private static aoc.api.settings.ModSettings loaderSettings;

    private ModManager() {}

    public static void init(File dir) {
        gameDir = dir;
        Log.init(dir);
        Log.info("Age of Clicks mod loader " + Version.LOADER + " starting in " + dir);
    }

    public static void discoverMods() {
        try {
            LoaderMod loader = new LoaderMod();
            ModContext ctx = new ModContext(loader.id(), loader.name(), gameDir, GAME, WORLD, ACTIONS);
            loader.onLoad(ctx);
            loaderSettings = ctx.settings();
            MODS.add(new Loaded(loader, ctx, null));
        } catch (Throwable t) {
            Log.error("Loader settings failed", t);
        }
        // Updates and removals the player asked for in the Mod Browser, while the jars are still free.
        ModCatalog.applyPending(new File(gameDir, "mods"));
        try {
            Improvements improvements = new Improvements();
            ModContext ctx = new ModContext(improvements.id(), improvements.name(), gameDir, GAME, WORLD, ACTIONS);
            improvements.onLoad(ctx);
            MODS.add(new Loaded(improvements, ctx, null));
        } catch (Throwable t) {
            Log.error("Game Improvements failed to load", t);
        }
        File[] jars = new File(gameDir, "mods").listFiles((d, n) -> n.toLowerCase().endsWith(".jar"));
        if (jars == null || jars.length == 0) {
            Log.info("No mods found in " + new File(gameDir, "mods"));
            return;
        }
        Arrays.sort(jars);
        for (File jar : jars) {
            try {
                URLClassLoader cl = new URLClassLoader(new URL[]{jar.toURI().toURL()}, ModManager.class.getClassLoader());
                for (AocMod mod : ServiceLoader.load(AocMod.class, cl)) {
                    ModContext ctx = new ModContext(mod.id(), mod.name(), gameDir, GAME, WORLD, ACTIONS);
                    mod.onLoad(ctx);
                    MODS.add(new Loaded(mod, ctx, jar));
                    Log.info("Loaded mod '" + mod.id() + "' from " + jar.getName());
                }
            } catch (Throwable t) {
                Log.error("Could not load " + jar.getName(), t);
            }
        }
    }

    /** During multiplayer only the loader's own menus get input; mods are paused. */
    static List<InputProcessor> inputs() { return multiplayer ? List.of(SYSTEM_UI) : INPUTS; }

    private static volatile boolean multiplayer;

    /**
     * True while a multiplayer game or lobby is active. Code mods are switched off then, so
     * nobody can use them for an advantage over other players.
     */
    public static boolean multiplayerActive() {
        try {
            ru.ius.david.cgs.game.GameManager gm = ru.ius.david.cgs.game.GameManager.getInstance();
            if (gm != null && gm.isNetworkMode()) return true;
            Object app = Gdx.app == null ? null : Gdx.app.getApplicationListener();
            if (app instanceof ru.ius.david.cgs.cgsGame g) {
                ru.ius.david.cgs.multiplayer.MultiplayerManager mp = g.getActiveMultiplayerManager();
                // Set only once a match starts; before that the lobby screen owns the session.
                if (mp == null && g.getScreen() instanceof ru.ius.david.cgs.MultiplayerLobbyScreen lobby) mp = lobby.getMultiplayerManager();
                return mp != null && mp.isMultiplayer();
            }
        } catch (Throwable ignored) {
            // treat as single player
        }
        return false;
    }

    static aoc.api.settings.ModSettings loaderSettings() { return loaderSettings; }

    static MultiplayerTestWindow multiplayerWindow() { return multiplayerTest; }

    /** Opens main menu > MULTIPLAYER (TEST). */
    static void openMultiplayerTest() {
        try {
            if (multiplayerTest == null) multiplayerTest = new MultiplayerTestWindow(SYSTEM_UI);
            multiplayerTest.open();
        } catch (Throwable t) {
            Log.error("Multiplayer test window failed", t);
        }
    }

    /** Opens the Mod Browser (Code Mods > Browse mods): the catalog of mods to install. */
    static void openModBrowser() {
        try {
            if (modBrowser == null) {
                modBrowser = new ModBrowser(SYSTEM_UI, new File(gameDir, "mods"), id -> {
                    for (Loaded m : MODS) if (m.mod.id().equals(id)) return new ModBrowser.Installed(m.mod.version(), m.jar);
                    return null;
                }, LoaderMod::modCatalog);
            }
            modBrowser.open();
        } catch (Throwable t) {
            Log.error("Mod Browser failed", t);
        }
    }

    static Ui systemUi() { return SYSTEM_UI; }

    static void closeModBrowser() {
        if (modBrowser != null) modBrowser.close();
    }

    /** Opens main menu > MODS > Code Mods. */
    static void openCodeMods() {
        if (codeMods == null) {
            codeMods = new CodeModsScreen(SYSTEM_UI, () -> {
                List<CodeModsScreen.Entry> out = new ArrayList<>();
                for (Loaded m : MODS) out.add(new CodeModsScreen.Entry(m.mod, m.ctx));
                return out;
            }, new File(gameDir, "mods"));
        }
        try {
            codeMods.open();
        } catch (Throwable t) {
            Log.error("Code Mods screen failed", t);
        }
    }

    /** Start of the game's frame: mods may draw off-screen work that the game then paints over. */
    static void beforeFrame() {
        if (!ready) return;
        ModsScreenButton.beforeFrame();
        if (multiplayer) return;
        float delta = Gdx.graphics.getDeltaTime();
        for (Loaded m : new ArrayList<>(MODS)) guard(m, () -> m.mod.beforeFrame(m.ctx, delta));
    }

    static void onFrame() {
        if (!ready) {
            ready = true;
            INPUTS.add(SYSTEM_UI);
            mpSelfTest = MultiplayerSelfTest.load(gameDir);
            uiSelfTest = UiSelfTest.load(gameDir);
            ModsScreenButton.install(SYSTEM_UI, ModManager::openCodeMods);
            try {
                overlay = new Overlay();
            } catch (Throwable t) {
                Log.error("Overlay could not start; mods are disabled", t);
                MODS.clear();
                return;
            }
            for (Loaded m : new ArrayList<>(MODS)) {
                m.ctx.attachOverlay(overlay);
                guard(m, () -> m.mod.onGameReady(m.ctx));
            }
            // Windows get input before raw handlers, so a click on a panel never reaches the map.
            for (Loaded m : MODS) INPUTS.add(m.ctx.ui());
            for (Loaded m : MODS) {
                InputProcessor in = m.mod.input();
                if (in != null) INPUTS.add(in);
            }
        }
        float delta = Gdx.graphics.getDeltaTime();
        if (multiplayerTest != null) multiplayerTest.tick(delta);
        if (mpSelfTest != null) mpSelfTest.tick(delta);
        if (uiSelfTest != null) uiSelfTest.tick(delta);
        boolean mp = multiplayerActive();
        if (mp != multiplayer) {
            multiplayer = mp;
            Log.info(mp ? "Multiplayer: code mods paused" : "Single player: code mods active again");
            if (mp) SYSTEM_UI.toast("Multiplayer: code mods are switched off for a fair game", 6);
        }
        if (mp) {
            renderSystemUi(delta);
            return;
        }
        for (Loaded m : MODS) {
            try {
                guard(m, () -> m.mod.render(m.ctx, delta));
            } finally {
                overlay.end();
            }
        }
        for (Loaded m : MODS) guard(m, () -> m.ctx.ui().render(delta));
        renderSystemUi(delta);
    }

    private static void renderSystemUi(float delta) {
        try {
            SYSTEM_UI.render(delta);
        } catch (Throwable t) {
            Log.error("Loader UI failed", t);
        }
        if (mpSelfTest != null) mpSelfTest.afterUi();
        if (uiSelfTest != null) uiSelfTest.afterUi();
    }

    private static void guard(Loaded m, Runnable call) {
        try {
            call.run();
        } catch (Throwable t) {
            m.errors++;
            if (m.errors <= 3) Log.error("Mod '" + m.mod.id() + "' failed", t);
            if (m.errors == MAX_ERRORS) {
                Log.info("Mod '" + m.mod.id() + "' failed " + MAX_ERRORS + " times and is now disabled");
                MODS.remove(m);
                INPUTS.remove(m.ctx.ui());
                INPUTS.remove(m.mod.input());
            }
        }
    }
}
