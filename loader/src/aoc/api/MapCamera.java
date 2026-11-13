package aoc.api;

import aoc.api.internal.Hooks;

/**
 * Limits of the game map's camera: how far the player can zoom out, and how far the view may go
 * past the map's edges. The values are shared by all mods (the last call wins) and, like every
 * mod, have no effect in multiplayer. Call the setters on the render thread (mod callbacks, click
 * handlers, settings listeners); an open map picks up the change at once.
 *
 * <pre>{@code
 * ctx.camera().setZoomOutFactor(3f);   // three times the game's own zoom-out limit
 * ctx.camera().setEdgeMargin(0.5f);    // edges can reach the middle of the screen
 * }</pre>
 */
public final class MapCamera {
    private static float zoomOutFactor = 1f;
    private static float edgeMargin = 0f;

    /** Multiplies the game's maximum zoom-out; 1 is the game's default, up to 10. */
    public void setZoomOutFactor(float factor) {
        zoomOutFactor = Math.max(1f, Math.min(10f, factor));
        Hooks.setMapCamera(zoomOutFactor, edgeMargin);
    }

    public float zoomOutFactor() { return zoomOutFactor; }

    /**
     * How far the camera may move past the map's edges, as a share of the visible area: 0 is the
     * game's default (edges stay at the screen border), 0.5 lets an edge reach the middle, up to 1.
     */
    public void setEdgeMargin(float fraction) {
        edgeMargin = Math.max(0f, Math.min(1f, fraction));
        Hooks.setMapCamera(zoomOutFactor, edgeMargin);
    }

    public float edgeMargin() { return edgeMargin; }

    /** The map camera's current zoom (larger = further out); 0 when no map is open. */
    public float zoom() { return Hooks.mapZoom(); }

    /**
     * Draws the game's own map, without its interface, as seen from another camera: centre
     * {@code x}/{@code y} in map pixels (y down, like the ownership grid), {@code visibleHeight}
     * map pixels from top to bottom of the window. Use it in {@link AocMod#beforeFrame}: the
     * game then draws its normal frame over it, so the player never sees it, and you can read
     * the pixels in between (a cinematic camera, minimaps, screenshots). Returns false when no
     * map is open. The player's own camera is left as it was.
     */
    public boolean renderMapView(float x, float y, float visibleHeight) { return Hooks.renderMapView(x, y, visibleHeight); }

    /**
     * Like {@link #renderMapView}, but as a neutral documentary map rather than the player's view:
     * the political map mode (every country in its own colour), no selected country, city or
     * units, no hover highlight, no data overlays. The player's settings are restored afterwards.
     */
    public boolean renderCleanMapView(float x, float y, float visibleHeight) { return Hooks.renderMapView(x, y, visibleHeight, true); }
}
