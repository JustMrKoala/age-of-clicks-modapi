package aoc.replay;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL21;

import java.nio.ByteBuffer;

/**
 * Reads film frames back from the graphics card without waiting for it. A plain glReadPixels makes
 * the game's thread wait until the card has finished everything drawn so far (several ms per film
 * frame); here each frame is read into one of a few pixel buffers and copied out a couple of frames
 * later, when the card is long done with it. Render thread only.
 */
final class AsyncReadback {
    private static final int RING = 3;
    private final int[] buffers = new int[RING];
    private final int w, h, size;
    private int started, taken;

    AsyncReadback(int w, int h) {
        this.w = w;
        this.h = h;
        this.size = w * h * 4;
        for (int i = 0; i < RING; i++) {
            buffers[i] = GL15.glGenBuffers();
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, buffers[i]);
            GL15.glBufferData(GL21.GL_PIXEL_PACK_BUFFER, size, GL15.GL_STREAM_READ);
        }
        GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
    }

    /** Frames read but not yet taken. */
    int pending() { return started - taken; }

    /** True when a new frame needs the oldest one taken first. */
    boolean full() { return pending() >= RING; }

    /** Starts reading the screen as it is now. */
    void start() {
        GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, buffers[started % RING]);
        GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
        GL11.glReadPixels(0, 0, w, h, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, 0L);
        GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
        started++;
    }

    /** Copies the oldest pending frame into {@code dst} (bottom-up RGBA, like glReadPixels). */
    void take(byte[] dst) {
        GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, buffers[taken % RING]);
        ByteBuffer mapped = GL15.glMapBuffer(GL21.GL_PIXEL_PACK_BUFFER, GL15.GL_READ_ONLY, size, null);
        if (mapped != null) {
            mapped.get(dst, 0, size);
            GL15.glUnmapBuffer(GL21.GL_PIXEL_PACK_BUFFER);
        }
        GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
        taken++;
    }

    void dispose() {
        for (int b : buffers) GL15.glDeleteBuffers(b);
    }
}
