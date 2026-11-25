package net.bichal.mods.tessera.compress.gpu;

import net.bichal.mods.tessera.compress.backend.TesseraRuntime;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** The per-image spot check GPU results go through before they are used, on CPU-encoded stand-ins. */
class GpuDispatchSpotCheckTest {

    private static final int W = 256, H = 128;

    @Test
    void correctBlocksPass() {
        assumeTrue(TesseraRuntime.isNativeActive(), "no tessera_bridge staged for this platform");
        ByteBuffer image = GpuEncoderCheck.testImageOf(W, H);
        ByteBuffer blocks = TesseraRuntime.backend().compressBc7(image.duplicate(), W, H, 0);
        assertNull(GpuDispatch.spotCheck(image, W, H, blocks));
    }

    @Test
    void blocksThatLoseTheCutoutsAreRejected() {
        assumeTrue(TesseraRuntime.isNativeActive(), "no tessera_bridge staged for this platform");
        ByteBuffer image = GpuEncoderCheck.testImageOf(W, H);
        ByteBuffer opaque = ByteBuffer.allocateDirect(image.remaining()).order(ByteOrder.LITTLE_ENDIAN);
        opaque.put(image.duplicate()).flip();
        for (int i = 3; i < opaque.limit(); i += 4) {
            opaque.put(i, (byte) 0xFF);
        }
        ByteBuffer blocks = TesseraRuntime.backend().compressBc7(opaque, W, H, 0);
        assertNotNull(GpuDispatch.spotCheck(image, W, H, blocks));
    }

    @Test
    void shuffledBlocksAreRejected() {
        assumeTrue(TesseraRuntime.isNativeActive(), "no tessera_bridge staged for this platform");
        ByteBuffer image = GpuEncoderCheck.testImageOf(W, H);
        ByteBuffer blocks = TesseraRuntime.backend().compressBc7(image.duplicate(), W, H, 0);
        int count = blocks.remaining() / 16;
        ByteBuffer reversed = ByteBuffer.allocateDirect(blocks.remaining()).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < count; i++) {
            reversed.put(i * 16, blocks, blocks.position() + (count - 1 - i) * 16, 16);
        }
        assertNotNull(GpuDispatch.spotCheck(image, W, H, reversed));
    }
}
