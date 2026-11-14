package aoc.api.internal;

import com.badlogic.gdx.Game;
import com.badlogic.gdx.Gdx;
import ru.ius.david.cgs.CFG;
import ru.ius.david.cgs.MapScreen;
import ru.ius.david.cgs.elements.MenuElement;
import ru.ius.david.cgs.menus.CGSInGame;
import ru.ius.david.cgs.menus.SliderMenu;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Where the game's own interface is on screen this frame, so mod widgets, notices and windows can
 * keep off it: the top bar, the dock at the bottom, every visible HUD element, open side panels and
 * dialogs. Everything is in y-down window pixels, like the game's UI. Render thread only.
 */
public final class GameHud {
    /** A rectangle the game (or an earlier mod widget) occupies; {@code what} names it for the log. */
    public record Block(float x, float y, float w, float h, String what) {
        boolean hits(float rx, float ry, float rw, float rh, float gap) {
            return rx < x + w + gap && rx + rw > x - gap && ry < y + h + gap && ry + rh > y - gap;
        }
    }

    /**
     * One frame's layout. {@code inGame}: the map with its HUD is on screen. {@code hidden}: the player
     * switched the interface off. {@code modal}: a dialog or a full-screen menu is open. {@code top} and
     * {@code bottom}: the band between the top bar and the dock. {@code noticeY}: where notices may start.
     */
    public record Layout(boolean inGame, boolean hidden, boolean modal, float top, float bottom, float noticeY,
                         List<Block> blocks) {}

    /** Start of {@code Block.what} for the game's open panels (economy, diplomacy, army, ...). */
    static final String PANEL = "panel ";
    private static long frame = Long.MIN_VALUE;
    private static Layout current;
    private static float noticeCursor;
    private static Field[] mapFields, hudDialogs;
    private static Field dockX, dockW;
    private static final Map<Class<?>, Method> VISIBLE = new HashMap<>();
    private static boolean failed;

    private GameHud() {}

    /** The layout of the frame being drawn (computed once per frame). */
    public static Layout layout() {
        long now = Gdx.graphics.getFrameId();
        if (current == null || now != frame) {
            frame = now;
            float sw = Gdx.graphics.getWidth(), sh = Gdx.graphics.getHeight();
            Layout l = null;
            if (!failed) {
                try {
                    l = measure(sw, sh);
                } catch (Throwable t) {
                    failed = true;
                    Log.error("Could not read the game's HUD layout; mod widgets use the plain screen", t);
                }
            }
            current = l != null ? l : new Layout(false, false, false, 0, sh, CFG.s(90), new ArrayList<>());
            noticeCursor = current.noticeY();
        }
        return current;
    }

    /** Marks a rectangle as taken for the rest of this frame, so later mod widgets go around it. */
    public static void claim(float x, float y, float w, float h, String what) {
        layout().blocks().add(new Block(x, y, w, h, what));
    }

    /**
     * Finds room for a {@code w x h} widget starting at {@code x, y}, stepping past everything in the
     * way ({@code down}: towards the bottom of the screen, else upwards). Returns the free y, or NaN when
     * there is none between the top bar and the dock (or a dialog is open): the widget is then left out.
     */
    public static float place(float x, float y, float w, float h, boolean down) {
        Layout l = layout();
        if (!l.inGame()) return y;
        if (l.hidden() || l.modal()) return Float.NaN;
        float gap = CFG.s(6);
        for (int tries = 0; tries < 64; tries++) {
            Block hit = null;
            for (Block b : l.blocks()) {
                if (b.hits(x, y, w, h, gap - 0.5f)) {
                    hit = b;
                    break;
                }
            }
            if (hit == null) return y;
            y = down ? hit.y() + hit.h() + gap : hit.y() - gap - h;
            if (y < 0 || y + h > Gdx.graphics.getHeight()) return Float.NaN;
        }
        return Float.NaN;
    }

    /**
     * A new x for a widget whose column is taken by an open game panel: just past the panel, towards
     * the middle of the screen ({@code fromLeft}: the widget hangs from the left edge). NaN if none is in the way.
     */
    public static float sideStep(float x, float w, boolean fromLeft) {
        float gap = CFG.s(6), out = Float.NaN;
        for (Block b : layout().blocks()) {
            if (!b.what().startsWith(PANEL) || x >= b.x() + b.w() + gap || x + w <= b.x() - gap) continue;
            float moved = fromLeft ? b.x() + b.w() + gap : b.x() - gap - w;
            if (Float.isNaN(out) || (fromLeft ? moved > out : moved < out)) out = moved;
        }
        return out;
    }

    /**
     * The widest stretch of screen width without an open game panel, as {@code {left, right}}: where a
     * new mod window is centred, so it opens next to the game's panel instead of on it.
     */
    public static float[] openWidth() {
        float sw = Gdx.graphics.getWidth(), left = 0, right = sw;
        for (Block b : layout().blocks()) {
            if (!b.what().startsWith(PANEL)) continue;
            if (b.x() + b.w() / 2 < sw / 2) left = Math.max(left, b.x() + b.w());
            else right = Math.min(right, b.x());
        }
        return new float[]{left, Math.max(left, right)};
    }

    /** Top of the next mod notice this frame; {@code height} is reserved under it. */
    public static float nextNotice(float height) {
        layout();
        float y = noticeCursor;
        noticeCursor += height + CFG.s(8);
        return y;
    }

    private static Layout measure(float sw, float sh) throws ReflectiveOperationException {
        if (Gdx.app == null || !(Gdx.app.getApplicationListener() instanceof Game g)) return null;
        if (!(g.getScreen() instanceof MapScreen map)) return null;
        CGSInGame hud = map.getInGameHud();
        if (hud == null) return null;
        fields();
        List<Block> blocks = new ArrayList<>();
        float top = CFG.topBarBottom(), bottom = sh;
        boolean modal = false;
        blocks.add(new Block(0, 0, sw, top, "top bar"));
        float noticeY = top + CFG.s(16);
        if (hud.getVisible()) {
            bottom = Math.min(sh, hud.getDockTopY());
            float dx = dockX.getInt(hud), dw = dockW.getInt(hud);
            if (dw <= 0) {
                dx = 0;
                dw = sw;
            }
            blocks.add(new Block(dx, bottom, dw, sh - bottom, "dock"));
            addElements(blocks, hud.getMenuElements(), sw, sh, "hud");
            noticeY = hud.getNoticeTopY(true);
            List<MenuElement> notices = hud.getTopLayerElements();
            if (notices != null) {
                for (MenuElement e : notices) {
                    if (e != null && e.getVisible() && e.getWidth() > 0) noticeY = Math.max(noticeY, e.getPosY() + e.getHeight() + CFG.s(8));
                }
                addElements(blocks, notices, sw, sh, "notice");
            }
            for (Field f : hudDialogs) modal |= showing(f.get(hud));
        }
        for (Field f : mapFields) {
            Object o = f.get(map);
            if (o == null || o == hud) continue;
            if (o instanceof SliderMenu m) {
                if (!m.getVisible() || !m.isOnScreen()) continue;
                float w = m.getWidth(), h = m.getHeight();
                if (m.isModalOverlay() || w * h >= 0.55f * sw * sh) modal = true;
                else if (w > 0 && h > 0) blocks.add(new Block(m.getPosX(), m.getPosY(), w, h, PANEL + f.getName()));
            } else if (f.getType().getSimpleName().endsWith("Dialog") || f.getType().getSimpleName().endsWith("MinigameUI")) {
                modal |= showing(o);
            }
        }
        return new Layout(true, CFG.uiHidden, modal, top, bottom, noticeY, blocks);
    }

    /** Visible elements as blocks, except the invisible screen-wide click catchers. */
    private static void addElements(List<Block> out, List<MenuElement> elements, float sw, float sh, String what) {
        if (elements == null) return;
        for (int i = 0; i < elements.size(); i++) {
            MenuElement e = elements.get(i);
            if (e == null || !e.getVisible()) continue;
            float w = e.getWidth(), h = e.getHeight();
            if (w <= 0 || h <= 0 || w * h >= 0.4f * sw * sh) continue;
            out.add(new Block(e.getPosX(), e.getPosY(), w, h, what + " " + i));
        }
    }

    /** True when a dialog-like object answers yes to isVisible / isShowing / isActive. */
    private static boolean showing(Object o) {
        if (o == null) return false;
        Method m = VISIBLE.computeIfAbsent(o.getClass(), c -> {
            for (String name : new String[]{"isVisible", "isShowing", "isActive", "isOpen"}) {
                try {
                    Method x = c.getMethod(name);
                    if (x.getReturnType() == boolean.class) return x;
                } catch (NoSuchMethodException ignored) {
                    // try the next name
                }
            }
            return null;
        });
        try {
            return m != null && (boolean) m.invoke(o);
        } catch (ReflectiveOperationException e) {
            return false;
        }
    }

    private static void fields() throws NoSuchFieldException {
        if (mapFields != null) return;
        dockX = CGSInGame.class.getDeclaredField("dockX");
        dockW = CGSInGame.class.getDeclaredField("dockW");
        dockX.setAccessible(true);
        dockW.setAccessible(true);
        hudDialogs = uiFields(CGSInGame.class, true);
        mapFields = uiFields(MapScreen.class, false);
    }

    /** The game's menu, panel and dialog fields of a class. */
    private static Field[] uiFields(Class<?> owner, boolean dialogsOnly) {
        List<Field> out = new ArrayList<>();
        for (Field f : owner.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive()) continue;
            String type = f.getType().getName();
            if (!type.startsWith("ru.ius.david.cgs.menus.") && !type.startsWith("ru.ius.david.cgs.ui.")) continue;
            if (dialogsOnly && !type.endsWith("Dialog")) continue;
            f.setAccessible(true);
            out.add(f);
        }
        return out.toArray(new Field[0]);
    }
}
