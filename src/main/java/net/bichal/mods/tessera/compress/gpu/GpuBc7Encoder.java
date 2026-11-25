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

    /**
     * Starts the same encode spread over several calls of {@link Job#advance}, so the render thread never
     * waits for the GPU: each call collects the slices that finished and dispatches more. The texels are
     * read as the slices go, so the buffer must stay untouched until the job is done or released.
     *
     * @return the job, or {@code null} when it cannot start or this encoder has no stepped mode (the caller
     *         encodes on the CPU instead)
     */
    default Job begin(ByteBuffer rgba8, int width, int height, int qualityPreset) {
        return null;
    }

    /** An encode in flight. Every method runs on the render thread. */
    interface Job {

        /**
         * Reads back the slices the GPU finished and dispatches the next ones, without waiting.
         *
         * @return {@code true} once every block is back in {@link #blocks()}
         * @throws IllegalStateException when GL reported an error
         */
        boolean advance();

        /** Blocks read back so far. */
        int blocksBack();

        /** The blocks, complete once {@link #advance()} returned {@code true}. */
        ByteBuffer blocks();

        /** Frees the GL objects; the job cannot be advanced after this. Safe to call twice. */
        void release();
    }
}
