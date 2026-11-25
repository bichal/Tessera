package net.bichal.mods.tessera.compress.gpu;

import net.bichal.mods.tessera.compress.backend.TesseraRuntime;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The startup check a GPU encoder must pass, exercised with stand-ins: the native CPU encoder as a
 * correct "GPU" encoder, and encoders broken in the ways a miscompiled shader or a wrong block layout
 * would be. The decoder is the native one, as in the game, so the test needs the bridge staged.
 */
class GpuEncoderCheckTest {

    private static final GpuEncoderCheck.Bc7Decoder DECODER = TesseraRuntime::decodeBc7;

    @Test
    void aCorrectEncoderPasses() {
        assumeNative();
        GpuEncoderCheck.Result result = GpuEncoderCheck.run(
                encoder((rgba, w, h, q) -> TesseraRuntime.backend().compressBc7(rgba, w, h, q)), DECODER);
        assertTrue(result.passed(), result.detail());
    }

    @Test
    void anEncoderThatDropsTheCutoutsFails() {
        assumeNative();
        GpuEncoderCheck.Result result = GpuEncoderCheck.run(encoder((rgba, w, h, q) -> {
            ByteBuffer opaque = ByteBuffer.allocateDirect(rgba.remaining()).order(ByteOrder.LITTLE_ENDIAN);
            opaque.put(rgba.duplicate()).flip();
            for (int i = 3; i < opaque.limit(); i += 4) {
                opaque.put(i, (byte) 0xFF);
            }
            return TesseraRuntime.backend().compressBc7(opaque, w, h, q);
        }), DECODER);
        assertFalse(result.passed());
        assertTrue(result.detail().contains("cutout"), result.detail());
    }

    @Test
    void transposedBlocksFail() {
        assumeNative();
        GpuEncoderCheck.Result result = GpuEncoderCheck.run(encoder((rgba, w, h, q) -> {
            ByteBuffer blocks = TesseraRuntime.backend().compressBc7(rgba, w, h, q);
            int bw = (w + 3) / 4, bh = (h + 3) / 4;
            ByteBuffer swapped = ByteBuffer.allocateDirect(blocks.remaining()).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < bw * bh; i++) {
                int src = (bw * bh - 1 - i) * 16; // blocks in reverse order: a wrong layout
                for (int b = 0; b < 16; b++) {
                    swapped.put(blocks.get(blocks.position() + src + b));
                }
            }
            return swapped.flip();
        }), DECODER);
        assertFalse(result.passed());
    }

    @Test
    void wrongSizeNullAndThrowingEncodersFail() {
        assumeNative();
        assertFalse(GpuEncoderCheck.run(encoder((rgba, w, h, q) ->
                ByteBuffer.allocateDirect(16).order(ByteOrder.LITTLE_ENDIAN)), DECODER).passed());
        assertFalse(GpuEncoderCheck.run(encoder((rgba, w, h, q) -> null), DECODER).passed());
        GpuEncoderCheck.Result threw = GpuEncoderCheck.run(encoder((rgba, w, h, q) -> {
            throw new IllegalStateException("shader link failed");
        }), DECODER);
        assertFalse(threw.passed());
        assertTrue(threw.detail().contains("shader link failed"), threw.detail());
    }

    private interface Encode {
        ByteBuffer encode(ByteBuffer rgba, int width, int height, int quality);
    }

    private static GpuBc7Encoder encoder(Encode encode) {
        return new GpuBc7Encoder() {
            @Override
            public String name() {
                return "test";
            }

            @Override
            public ByteBuffer encode(ByteBuffer rgba8, int width, int height, int qualityPreset) {
                return encode.encode(rgba8, width, height, qualityPreset);
            }
        };
    }

    private static void assumeNative() {
        assumeTrue(TesseraRuntime.isNativeActive(), "no tessera_bridge staged for this platform");
    }
}
