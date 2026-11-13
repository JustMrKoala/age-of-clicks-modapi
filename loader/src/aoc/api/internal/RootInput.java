package aoc.api.internal;

import com.badlogic.gdx.InputProcessor;

import java.util.function.Predicate;

/** Gives every mod's input handler first refusal, then passes the event to the game. */
final class RootInput implements InputProcessor {
    volatile InputProcessor delegate;

    private boolean dispatch(Predicate<InputProcessor> event) {
        for (InputProcessor mod : ModManager.inputs()) {
            try {
                if (event.test(mod)) return true;
            } catch (Throwable t) {
                Log.error("Mod input handler failed", t);
            }
        }
        InputProcessor game = delegate;
        return game != null && event.test(game);
    }

    @Override public boolean keyDown(int keycode) { return dispatch(p -> p.keyDown(keycode)); }
    @Override public boolean keyUp(int keycode) { return dispatch(p -> p.keyUp(keycode)); }
    @Override public boolean keyTyped(char c) { return dispatch(p -> p.keyTyped(c)); }
    @Override public boolean touchDown(int x, int y, int pointer, int button) { return dispatch(p -> p.touchDown(x, y, pointer, button)); }
    @Override public boolean touchUp(int x, int y, int pointer, int button) { return dispatch(p -> p.touchUp(x, y, pointer, button)); }
    @Override public boolean touchCancelled(int x, int y, int pointer, int button) { return dispatch(p -> p.touchCancelled(x, y, pointer, button)); }
    @Override public boolean touchDragged(int x, int y, int pointer) { return dispatch(p -> p.touchDragged(x, y, pointer)); }
    @Override public boolean mouseMoved(int x, int y) { return dispatch(p -> p.mouseMoved(x, y)); }
    @Override public boolean scrolled(float dx, float dy) { return dispatch(p -> p.scrolled(dx, dy)); }
}
