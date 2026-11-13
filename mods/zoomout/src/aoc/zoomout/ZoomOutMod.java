package aoc.zoomout;

import aoc.api.AocMod;
import aoc.api.ModContext;
import aoc.api.settings.Setting;

/**
 * Lets the player zoom further out of the map and move the map's edges away from the screen
 * border, so regions at the edge (under the game's panels) can be reached and clicked.
 * Both limits are settings in main menu > MODS > CODE MODS > Zoom Out.
 */
public final class ZoomOutMod implements AocMod {
    private Setting<Double> zoomOut;
    private Setting<Double> edgeMargin;

    @Override public String id() { return "zoomout"; }
    @Override public String name() { return "Zoom Out"; }
    @Override public String version() { return "1.0.0"; }
    @Override public String author() { return "Age of Clicks Mod API"; }

    @Override
    public String description() {
        return "Zoom further out of the map, and scroll past its edges so border regions are reachable.";
    }

    @Override
    public void onLoad(ModContext ctx) {
        zoomOut = ctx.settings().decimal("zoom_out", "Zoom-out limit", 3.0, 1.0, 10.0,
                "How much further than the game allows you can zoom out. 1 = the game's own limit.");
        edgeMargin = ctx.settings().decimal("edge_margin", "Scroll past the edges", 0.5, 0.0, 1.0,
                "How far you can scroll beyond the map's edges, as a share of the screen. "
                        + "0 = the game's default, 0.5 = an edge can reach the middle of the screen.");
        ctx.settings().onChange(() -> apply(ctx));
    }

    @Override
    public void onGameReady(ModContext ctx) {
        // Applied here, not in onLoad: the camera limits need the game's map classes.
        apply(ctx);
    }

    private void apply(ModContext ctx) {
        ctx.camera().setZoomOutFactor(zoomOut.get().floatValue());
        ctx.camera().setEdgeMargin(edgeMargin.get().floatValue());
        ctx.log("zoom-out x" + zoomOut.get() + ", edge margin " + edgeMargin.get());
    }
}
