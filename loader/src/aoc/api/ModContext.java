package aoc.api;

import aoc.api.actions.Actions;
import aoc.api.internal.Log;
import aoc.api.settings.ModSettings;
import aoc.api.ui.Ui;
import aoc.api.world.World;

import java.io.File;

/** What the loader hands to each mod. */
public final class ModContext {
    private final String modId;
    private final File gameDir;
    private final File dataDir;
    private final GameApi game;
    private final World world;
    private final Actions actions;
    private final Ui ui = new Ui();
    private final ModSettings settings;
    private final MapCamera camera = new MapCamera();
    private Overlay overlay;

    public ModContext(String modId, String modName, File gameDir, GameApi game, World world, Actions actions) {
        this.modId = modId;
        this.gameDir = gameDir;
        this.dataDir = new File(new File(gameDir, "mods"), modId);
        this.game = game;
        this.world = world;
        this.actions = actions;
        this.settings = new ModSettings(new File(dataDir, "config.properties"), modName);
    }

    public String modId() { return modId; }

    /** The Age of Clicks install folder. */
    public File gameDir() { return gameDir; }

    /** {@code <game>/mods/<mod id>/}, created on first use. Config and saves go here. */
    public File dataDir() {
        if (!dataDir.isDirectory()) dataDir.mkdirs();
        return dataDir;
    }

    /** Quick reads (player, countries, date) and the game's JSON reports. */
    public GameApi game() { return game; }

    /** Cities, territory, governments, events: direct world access (render thread only). */
    public World world() { return world; }

    /** Every action a country can take, by name; shared by all mods, extensible. */
    public Actions actions() { return actions; }

    /** This mod's windows and HUD widgets, drawn in the game's own style. */
    public Ui ui() { return ui; }

    /** Settings the player edits in main menu > MODS > Code Mods; stored in {@code mods/<id>/config.properties}. */
    public ModSettings settings() { return settings; }

    /** The map camera's limits: zoom-out cap and how far the view may go past the edges. */
    public MapCamera camera() { return camera; }

    /** Low-level drawing (y-up pixels); available from {@link AocMod#onGameReady} on. */
    public Overlay overlay() { return overlay; }

    public void attachOverlay(Overlay overlay) { this.overlay = overlay; }

    public void log(String message) { Log.info("[" + modId + "] " + message); }

    public void error(String message, Throwable t) { Log.error("[" + modId + "] " + message, t); }
}
