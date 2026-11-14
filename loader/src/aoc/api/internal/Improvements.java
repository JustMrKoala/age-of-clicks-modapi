package aoc.api.internal;

import aoc.api.AocMod;
import aoc.api.ModContext;
import aoc.api.settings.Setting;

/**
 * Game Improvements: small improvements to the game itself that ship inside the loader. Each one
 * has its own switch in main menu > MODS > CODE MODS > Game Improvements. Like every code mod it
 * is paused in multiplayer.
 */
final class Improvements implements AocMod {
    private Setting<Boolean> frontNumbers, zoomOut;
    private Setting<Double> zoomLimit, edgeMargin;
    private FrontNumbers numbers;

    @Override public String id() { return "improvements"; }

    @Override public String name() { return "Game Improvements"; }

    @Override public String version() { return Version.LOADER; }

    @Override public String author() { return "Age of Clicks Mod API"; }

    @Override
    public String description() {
        return "Improvements to the game itself, built into the mod loader. Switch each one on or off below.";
    }

    @Override
    public void onLoad(ModContext ctx) {
        frontNumbers = ctx.settings().toggle("front_numbers", "Army numbers on the fronts", true,
                "At war, each side's army on a front is written on its own side of the line (classic combat).");
        zoomOut = ctx.settings().toggle("zoom_out", "Zoom further out", true,
                "Zoom further out of the map than the game allows, and scroll past its edges so border regions are reachable.");
        zoomLimit = ctx.settings().decimal("zoom_out_limit", "Zoom-out limit", 3.0, 1.0, 10.0,
                "How much further than the game allows you can zoom out. 1 = the game's own limit.");
        edgeMargin = ctx.settings().decimal("edge_margin", "Scroll past the edges", 0.5, 0.0, 1.0,
                "How far you can scroll beyond the map's edges, as a share of the screen. "
                        + "0 = the game's default, 0.5 = an edge can reach the middle of the screen.");
        ctx.settings().onChange(() -> applyZoom(ctx));
    }

    @Override
    public void onGameReady(ModContext ctx) {
        // Applied here, not in onLoad: the camera limits need the game's map classes.
        applyZoom(ctx);
    }

    private void applyZoom(ModContext ctx) {
        boolean on = zoomOut.get();
        ctx.camera().setZoomOutFactor(on ? zoomLimit.get().floatValue() : 1f);
        ctx.camera().setEdgeMargin(on ? edgeMargin.get().floatValue() : 0f);
        ctx.log(on ? "zoom-out x" + zoomLimit.get() + ", edge margin " + edgeMargin.get() : "zoom-out off");
    }

    @Override
    public void render(ModContext ctx, float delta) {
        if (!frontNumbers.get()) return;
        if (numbers == null) numbers = new FrontNumbers();
        numbers.render(delta);
    }
}
