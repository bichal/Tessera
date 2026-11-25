package net.bichal.mods.tessera.compress;

import net.bichal.mods.tessera.compat.TesseraCompat;
import net.bichal.mods.tessera.compress.gpu.GpuCapabilities;
import net.bichal.mods.tessera.vram.VramBudgetEngine;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL42C;
import org.lwjgl.opengl.GL43C;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Experimental (with {@code gpuCompression}): keeps the compressed blocks of animation frames in
 * VRAM, so drawing a frame the GPU has already seen is a GPU-side {@code glCopyImageSubData} into the
 * atlas instead of a {@code glCompressedTexSubImage2D} that sends the blocks from the CPU again.
 *
 * <p>Frames are found by the identity of their block buffer: the uploader's caches never change a
 * buffer once it is made (a better encode replaces it with a new one), so a buffer always holds the
 * same blocks. A frame is put in the bank the first time it is drawn, in pages of
 * {@value #PAGE_SIZE}x{@value #PAGE_SIZE} texels of the atlas's own format, filled shelf by shelf.
 * When the pages reach {@link #MAX_BYTES}, or one more page would take Tessera past its VRAM budget
 * (the atlases count first), the bank starts over with the pages it has (whatever is in the atlas
 * already stays there); a bank that keeps filling up is more uploads, not fewer, and turns itself
 * off, as does one the budget leaves no page at all.
 *
 * <p>Render thread only. Regions must be whole 4x4 blocks, which the copy requires.
 */
final class GpuFrameBank {

    private static final Logger LOGGER = LoggerFactory.getLogger("Tessera/FrameBank");

    static final int PAGE_SIZE = 2048;
    /** VRAM the pages of one atlas may take. */
    static final long MAX_BYTES = 64L << 20;
    /** Times a bank may fill up and start over before it gives up. */
    private static final int MAX_RESTARTS = 4;

    private static long totalBytes;

    private final String atlasName;
    private final CompressionPipeline.Target target;
    private final long pageBytes;
    private final List<Integer> pages = new ArrayList<>();
    private final Map<ByteBuffer, Long> slots = new IdentityHashMap<>();
    /** Shelves of the page being filled: {y, height, next free x}. */
    private final List<int[]> shelves = new ArrayList<>();
    private int page = -1;
    private int top;
    private int restarts;
    private boolean disabled;

    private GpuFrameBank(String atlasName, CompressionPipeline.Target target) {
        this.atlasName = atlasName;
        this.target = target;
        this.pageBytes = (long) (PAGE_SIZE / target.blockWidth()) * (PAGE_SIZE / target.blockHeight())
                * target.bytesPerBlock();
    }

    /** A bank for an atlas, or {@code null} where this context cannot copy compressed images (render thread). */
    static GpuFrameBank create(String atlasName, CompressionPipeline.Target target) {
        if (target.blockWidth() != 4 || target.blockHeight() != 4 || !GpuCapabilities.detect().supported()) {
            return null;
        }
        var caps = GL.getCapabilities();
        if (!(caps.OpenGL42 || caps.GL_ARB_texture_storage)) {
            return null;
        }
        return new GpuFrameBank(atlasName, target);
    }

    /** VRAM held by every bank (render thread). */
    static long totalBytes() {
        return totalBytes;
    }

    /**
     * Writes {@code blocks} over a region of {@code level} of the atlas {@code atlasId} (bound, with
     * the unpack state reset) through the bank. Returns {@code false} when the bank cannot take it,
     * and the caller uploads the blocks itself.
     */
    boolean copy(int atlasId, int level, int x, int y, int w, int h, ByteBuffer blocks) {
        if (disabled || ((x | y | w | h) & 3) != 0) {
            return false;
        }
        Long slot = slots.get(blocks);
        if (slot == null) {
            slot = fill(atlasId, w, h, blocks);
            if (slot == null) {
                return false;
            }
        }
        long s = slot;
        GL43C.glCopyImageSubData(pages.get((int) (s >>> 48)), GL11.GL_TEXTURE_2D, 0,
                (int) (s >>> 24) & 0xFFFFFF, (int) s & 0xFFFFFF, 0,
                atlasId, GL11.GL_TEXTURE_2D, level, x, y, 0, w, h, 1);
        if (net.bichal.mods.tessera.selftest.TesseraSelfTest.ENABLED) {
            CompressedAnimationUploader.EncodeStats.bankCopies++;
        }
        return true;
    }

    /** Puts a new frame in the bank (rebinding the atlas after), or {@code null} if it does not fit. */
    private Long fill(int atlasId, int w, int h, ByteBuffer blocks) {
        int[] position = allocate(w, h);
        if (position == null) {
            return null;
        }
        TesseraCompat.bindTexture(pages.get(position[0]));
        GL13.glCompressedTexSubImage2D(GL11.GL_TEXTURE_2D, 0, position[1], position[2], w, h,
                target.glFormat(), blocks.duplicate());
        TesseraCompat.bindTexture(atlasId);
        long slot = ((long) position[0] << 48) | ((long) position[1] << 24) | position[2];
        slots.put(blocks, slot);
        if (net.bichal.mods.tessera.selftest.TesseraSelfTest.ENABLED) {
            CompressedAnimationUploader.EncodeStats.bankFills++;
        }
        return slot;
    }

    /** {page, x, y} for a w x h region, starting over or giving up when the pages are full. */
    private int[] allocate(int w, int h) {
        if (w > PAGE_SIZE || h > PAGE_SIZE) {
            return null;
        }
        int[] position = place(w, h);
        if (position != null) {
            return position;
        }
        boolean budget = VramBudgetEngine.withinBudget(totalBytes + pageBytes);
        if (pages.size() * pageBytes + pageBytes <= MAX_BYTES && budget) {
            int id = GL11.glGenTextures();
            TesseraCompat.bindTexture(id);
            GL42C.glTexStorage2D(GL11.GL_TEXTURE_2D, 1, target.glFormat(), PAGE_SIZE, PAGE_SIZE);
            pages.add(id);
            totalBytes += pageBytes;
        } else if (pages.isEmpty()) {
            LOGGER.info("Frame bank for {} turned off: the {} MB VRAM budget has no room for it", atlasName,
                    VramBudgetEngine.getEffectiveBudgetMb());
            disabled = true;
            return null;
        } else if (++restarts > MAX_RESTARTS) {
            LOGGER.info("Frame bank for {} turned off: its frames do not fit in {} MB", atlasName,
                    MAX_BYTES >> 20);
            release();
            disabled = true;
            return null;
        } else {
            slots.clear(); // start over: the frames already copied stay in the atlas
        }
        page = page + 1 < pages.size() ? page + 1 : 0;
        shelves.clear();
        top = 0;
        return place(w, h);
    }

    /** A place on the page being filled, on a shelf of the same height or a new one. */
    private int[] place(int w, int h) {
        if (page < 0) {
            return null;
        }
        for (int[] shelf : shelves) {
            if (shelf[1] == h && shelf[2] + w <= PAGE_SIZE) {
                int x = shelf[2];
                shelf[2] += w;
                return new int[]{page, x, shelf[0]};
            }
        }
        if (top + h > PAGE_SIZE) {
            return null;
        }
        shelves.add(new int[]{top, h, w});
        int y = top;
        top += h;
        return new int[]{page, 0, y};
    }

    /** Deletes the pages (render thread). */
    void release() {
        for (int id : pages) {
            GL11.glDeleteTextures(id);
        }
        totalBytes -= pages.size() * pageBytes;
        pages.clear();
        slots.clear();
        shelves.clear();
        page = -1;
        top = 0;
    }
}
