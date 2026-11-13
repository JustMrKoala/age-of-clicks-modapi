package aoc.api.ui;

import com.badlogic.gdx.graphics.g2d.SpriteBatch;

/** The game's thin divider line; also usable as an empty spacer with {@link #spacer()}. */
public class Separator extends Widget<Separator> {
    private final boolean line;

    public Separator() { this(true); }

    private Separator(boolean line) { this.line = line; }

    /** Invisible gap; give it {@code flex(1)} to push siblings apart. */
    public static Separator spacer() { return new Separator(false); }

    @Override
    protected float naturalHeight(float width) { return Theme.s(line ? 8 : 0); }

    @Override
    protected float naturalWidth() { return Theme.s(line ? 8 : 0); }

    @Override
    public void draw(SpriteBatch b, float delta) {
        if (line) Theme.separator(b, (int) x, (int) (y + h / 2), (int) w);
    }
}
