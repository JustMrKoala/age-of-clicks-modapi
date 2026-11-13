package aoc.api.ui;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.utils.Align;

import java.util.ArrayList;
import java.util.List;

/** Chat-style log: speech bubbles left and right, notices in the middle, newest at the bottom. */
public class MessageList extends Widget<MessageList> {
    public enum Side { LEFT, RIGHT, NOTICE }

    public record Message(Side side, String author, String text, Color accent) {}

    private final List<Message> messages = new ArrayList<>();
    private String typing;
    private float scroll;
    private float time;

    public synchronized MessageList add(Side side, String author, String text) { return add(side, author, text, null); }

    public synchronized MessageList add(Side side, String author, String text, Color accent) {
        messages.add(new Message(side, author, text, accent));
        scroll = 0;
        return this;
    }

    public synchronized void clear() { messages.clear(); }

    public synchronized List<Message> messages() { return new ArrayList<>(messages); }

    /** Shows "<who> is writing..." at the bottom; null hides it. */
    public void typing(String who) { typing = who; }

    @Override
    protected float naturalHeight(float width) { return Theme.s(200); }

    @Override
    public void draw(SpriteBatch b, float delta) {
        time += delta;
        Theme.box(b, (int) x, (int) y, (int) w, (int) h, Theme.surfaceLow(), Theme.separator());
        List<Message> list = messages();
        if (typing != null) list.add(new Message(Side.NOTICE, null, typing + " is writing" + ".".repeat(1 + (int) (time * 2) % 3), null));
        float pad = Theme.s(10), bubblePad = Theme.s(8), gap = Theme.s(10);
        float maxBubble = (w - 2 * pad) * 0.8f;
        float authorH = Theme.lineHeight(Theme.Font.SMALL);
        float[] heights = new float[list.size()];
        float total = pad;
        for (int i = 0; i < list.size(); i++) {
            Message m = list.get(i);
            heights[i] = m.side() == Side.NOTICE
                    ? Theme.wrappedHeight(Theme.Font.SMALL, m.text(), w - 2 * pad)
                    : authorH + Theme.wrappedHeight(Theme.Font.MAIN, m.text(), maxBubble - 2 * bubblePad) + 2 * bubblePad;
            total += heights[i] + gap;
        }
        scroll = Math.max(0, Math.min(scroll, Math.max(0, total - h)));
        ui.clip(x + 1, y + 1, w - 2, h - 2, b);
        float bottom = y + h - pad + scroll;
        for (int i = list.size() - 1; i >= 0; i--) {
            Message m = list.get(i);
            float top = bottom - heights[i];
            if (bottom < y) break;
            if (top < y + h) drawMessage(b, m, top, heights[i], pad, bubblePad, maxBubble, authorH);
            bottom = top - gap;
        }
        ui.unclip(b);
    }

    private void drawMessage(SpriteBatch b, Message m, float top, float height, float pad, float bubblePad, float maxBubble, float authorH) {
        if (m.side() == Side.NOTICE) {
            Theme.wrapped(b, Theme.Font.SMALL, m.text(), x + pad, top, w - 2 * pad, Align.center,
                    m.accent() != null ? m.accent() : Theme.textMuted());
            return;
        }
        boolean right = m.side() == Side.RIGHT;
        float textW = Math.min(Theme.textWidth(Theme.Font.MAIN, m.text()), maxBubble - 2 * bubblePad);
        float bw = textW + 2 * bubblePad;
        float bx = right ? x + w - pad - bw : x + pad;
        if (m.author() != null) {
            float aw = Theme.textWidth(Theme.Font.SMALL, m.author());
            Theme.text(b, Theme.Font.SMALL, m.author(), right ? x + w - pad - aw : x + pad, top, Theme.textMuted());
        }
        float by = top + authorH;
        float bh = height - authorH;
        Color fill = right ? Theme.surfaceHigh() : Theme.buttonBg();
        Theme.box(b, (int) bx, (int) by, (int) bw, (int) bh, fill, m.accent() != null ? m.accent() : Theme.separator());
        if (!right) Theme.rect(b, bx, by, Theme.s(3), bh, m.accent() != null ? m.accent() : Theme.red());
        Theme.wrapped(b, Theme.Font.MAIN, m.text(), bx + bubblePad, by + bubblePad, textW, Align.left, Theme.textDark());
    }

    @Override
    public boolean scrolled(float amount, float mx, float my) {
        scroll -= amount * Theme.s(40);
        return true;
    }
}
