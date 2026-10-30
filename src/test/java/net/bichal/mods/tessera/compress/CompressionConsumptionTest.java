package net.bichal.mods.tessera.compress;

import net.bichal.mods.tessera.atlas.AtlasCompressionDriver;
import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Memory and time the encoders consume on a synthetic atlas: the GPU footprint of each
 * format against RGBA8888, and the heap the calling thread allocates during one encode (worker threads are not counted). The bounds are loose
 * enough for a shared CI machine; the figures are printed for the build log.
 */
class CompressionConsumptionTest {

    private static final int SIZE = 1024;
    private static final int QUALITY = 3;

    @Test
    void bc1UsesAnEighthOfRgbaMemory() {
        Measured m = measure(false, true);
        assertEquals((long) SIZE * SIZE * 4 / 8, m.blocks.remaining());
        report("BC1 opaque", m);
        assertTrue(m.allocatedBytes < 8L * SIZE * SIZE * 4, "heap per encode: " + m.allocatedBytes);
    }

    @Test
    void bc7UsesAQuarterOfRgbaMemory() {
        Measured m = measure(true, false);
        assertEquals((long) SIZE * SIZE * 4 / 4, m.blocks.remaining());
        report("BC7 alpha", m);
        assertTrue(m.allocatedBytes < 8L * SIZE * SIZE * 4, "heap per encode: " + m.allocatedBytes);
    }

    @Test
    void fullMipChainEstimateMatchesTheFormats() {
        long rgba = (long) SIZE * SIZE * 4 * 4 / 3;
        long bc7 = AtlasCompressionDriver.estimateFullMipChainBytes(SIZE, SIZE, CompressionPipeline.Target.BC7, 10);
        long bc1 = AtlasCompressionDriver.estimateFullMipChainBytes(SIZE, SIZE, CompressionPipeline.Target.BC1, 10);
        assertTrue(bc7 <= rgba / 4 + 1024 && bc7 >= rgba / 4 - 1024, "BC7 chain " + bc7);
        assertTrue(bc1 <= rgba / 8 + 1024 && bc1 >= rgba / 8 - 1024, "BC1 chain " + bc1);
        System.out.printf("[consumption] %dx%d full chain: RGBA8888 %.2f MiB, BC7 %.2f MiB (-%.0f%%), BC1 %.2f MiB (-%.0f%%)%n",
                SIZE, SIZE, mib(rgba), mib(bc7), 100.0 * (rgba - bc7) / rgba, mib(bc1), 100.0 * (rgba - bc1) / rgba);
    }

    private record Measured(ByteBuffer blocks, long allocatedBytes, long millis) {
    }

    private static Measured measure(boolean alpha, boolean opaque) {
        ByteBuffer rgba = atlas(alpha);
        CompressionPipeline.Target target = alpha ? CompressionPipeline.Target.BC7 : CompressionPipeline.Target.BC1;
        // Warm-up: class loading and JIT must not count as the encoder's own allocation.
        CompressionPipeline.compressBlocking("warmup", rgba.duplicate(), SIZE, SIZE, target, QUALITY, 0);
        var threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long tid = Thread.currentThread().threadId();
        long before = threads.getThreadAllocatedBytes(tid);
        long start = System.nanoTime();
        ByteBuffer blocks = CompressionPipeline.compressBlocking("test", rgba.duplicate(), SIZE, SIZE, target, QUALITY, 0);
        long millis = (System.nanoTime() - start) / 1_000_000;
        long allocated = threads.getThreadAllocatedBytes(tid) - before;
        assertNotNull(blocks);
        return new Measured(blocks, allocated, millis);
    }

    private static void report(String label, Measured m) {
        System.out.printf("[consumption] %s %dx%d: %.2f MiB blocks (RGBA8888 %.2f MiB), %.1f MiB heap allocated, %d ms%n",
                label, SIZE, SIZE, mib(m.blocks.remaining()), mib((long) SIZE * SIZE * 4), mib(m.allocatedBytes), m.millis);
    }

    private static double mib(long bytes) {
        return bytes / (1024.0 * 1024.0);
    }

    /** Tiles of smooth gradients with noise, like block textures; with alpha, a cut-out disc per tile. */
    private static ByteBuffer atlas(boolean alpha) {
        ByteBuffer buffer = ByteBuffer.allocateDirect(SIZE * SIZE * 4).order(ByteOrder.LITTLE_ENDIAN);
        Random random = new Random(42);
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                int tile = (x / 16) * 31 + (y / 16) * 17;
                int r = (tile * 7 + x % 16 * 8 + random.nextInt(12)) & 0xFF;
                int g = (tile * 13 + y % 16 * 8 + random.nextInt(12)) & 0xFF;
                int b = (tile * 29 + (x + y) % 16 * 4 + random.nextInt(12)) & 0xFF;
                int dx = x % 16 - 8;
                int dy = y % 16 - 8;
                int a = !alpha || dx * dx + dy * dy < 49 ? 255 : 0;
                buffer.putInt(r | g << 8 | b << 16 | a << 24);
            }
        }
        return buffer.flip();
    }
}
