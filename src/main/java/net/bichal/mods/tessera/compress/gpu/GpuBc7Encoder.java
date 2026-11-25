package net.bichal.mods.tessera.compress.gpu;

import java.nio.ByteBuffer;

/**
 * A BC7 encoder that runs on the GPU. Called on the render thread only: it owns the GL context, and
 * must leave every piece of GL state it touches as it found it ({@code GlStateManager} and other mods
 * keep their own copy of that state).
 */
public interface GpuBc7Encoder {

    /** Short name for logs and F3. */
    String name();

    /**
     * Encodes tightly packed RGBA8 texels (from the buffer's position) into BC7 blocks, row-major,
     * 16 bytes per 4x4 block, partial edge blocks padded by repeating the last row and column.
     *
     * @return a direct little-endian buffer of {@code ceil(w/4) * ceil(h/4) * 16} bytes, or {@code null}
     *         when the encode failed (the caller encodes on the CPU instead)
     */
    ByteBuffer encode(ByteBuffer rgba8, int width, int height, int qualityPreset);
}
