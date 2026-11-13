package aoc.api;

import com.badlogic.gdx.InputProcessor;

/**
 * A mod for Age of Clicks.
 *
 * <p>Ship it as a jar in {@code <game>/mods/} with a
 * {@code META-INF/services/aoc.api.AocMod} file naming the implementing class.
 * Every callback except {@link #onLoad} runs on the game's render thread.
 */
public interface AocMod {
    /** Short unique id, also the name of the mod's data folder in {@code mods/}. */
    String id();

    /** Name shown in the Code Mods screen. */
    default String name() { return id(); }

    default String version() { return ""; }

    default String author() { return ""; }

    /** One or two sentences for the Code Mods screen. */
    default String description() { return ""; }

    /** Called once while the game starts, before any window exists. No libGDX calls here. */
    default void onLoad(ModContext ctx) {}

    /** Called once on the first rendered frame: GL, fonts and input are available. */
    default void onGameReady(ModContext ctx) {}

    /**
     * Called before the game draws each frame. Whatever you draw to the screen here is painted
     * over by the game's frame, so use it for off-screen work such as
     * {@link MapCamera#renderMapView} followed by reading the pixels.
     */
    default void beforeFrame(ModContext ctx, float delta) {}

    /** Called after the game has drawn each frame; draw overlays here. */
    default void render(ModContext ctx, float delta) {}

    /**
     * Input handler that sees events before the game. Return {@code true} from a
     * method to consume the event. May return {@code null}.
     */
    default InputProcessor input() { return null; }
}
