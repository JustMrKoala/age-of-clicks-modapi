package aoc.api.ui;

import com.badlogic.gdx.graphics.g2d.SpriteBatch;

import java.util.List;

/** Shows a column taller than the space it gets, scrolled with the mouse wheel. */
public class ScrollPanel extends Widget<ScrollPanel> {
    private final Panel content;
    private float scroll;
    private float contentHeight;

    public ScrollPanel(Panel content) { this.content = content; }

    public Panel content() { return content; }

    public void scrollToTop() { scroll = 0; }

    @Override
    public List<Widget<?>> children() { return List.of(content); }

    @Override
    protected float naturalHeight(float width) { return Theme.s(200); }

    @Override
    public void layout(float x, float y, float w, float h) {
        super.layout(x, y, w, h);
        float barW = Theme.s(10);
        contentHeight = content.preferredHeight(w - barW);
        scroll = Math.max(0, Math.min(scroll, Math.max(0, contentHeight - h)));
        content.layout(x, y - scroll, w - barW, Math.max(h, contentHeight));
    }

    @Override
    public boolean contains(float px, float py) { return super.contains(px, py); }

    @Override
    public void draw(SpriteBatch b, float delta) {
        layout(x, y, w, h);
        ui.clip(x, y, w, h, b);
        content.draw(b, delta);
        ui.unclip(b);
        if (contentHeight > h) {
            float barW = Theme.s(6);
            float thumbH = Math.max(Theme.s(24), h * h / contentHeight);
            float thumbY = y + (h - thumbH) * (scroll / (contentHeight - h));
            Theme.rect(b, x + w - barW, y, barW, h, Theme.rowAlt());
            Theme.rect(b, x + w - barW, thumbY, barW, thumbH, Theme.tertiary());
        }
    }

    @Override
    public boolean scrolled(float amount, float mx, float my) {
        if (contentHeight <= h) return false;
        scroll += amount * Theme.s(40);
        return true;
    }
}
