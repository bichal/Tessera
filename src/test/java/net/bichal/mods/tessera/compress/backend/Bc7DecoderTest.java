package net.bichal.mods.tessera.compress.backend;

import net.bichal.mods.tessera.compress.software.Bc7Decoder;
import net.bichal.mods.tessera.compress.software.ExactTransparency;
import net.bichal.mods.tessera.compress.software.SoftwareBc7Encoder;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The pure-Java BC7 decoder against the native one (bc7enc's), and the exact-transparency fix-up:
 * transparent texels decode as 0 and the blocks it writes decode the same in both decoders.
 */
class Bc7DecoderTest {

    @Test
    void randomBlocksOfEveryModeDecodeLikeTheNativeDecoder() {
        assumeNative();
        Random random = new Random(7);
        int count = 4096;
        ByteBuffer blocks = ByteBuffer.allocateDirect(count * 16).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < count; i++) {
            long lo = random.nextLong();
            int mode = i % 8;
            lo = (lo & ~((1L << (mode + 1)) - 1)) | (1L << mode); // mode bits: `mode` zeros, then a one
            blocks.putLong(lo).putLong(random.nextLong());
        }
        blocks.flip();
        assertSameAsNative(blocks, count * 4, 4);
    }

    @Test
    void nativelyEncodedImagesDecodeLikeTheNativeDecoder() {
        assumeNative();
        int width = 64, height = 48;
        ByteBuffer image = noisyCutout(width, height, 3);
        for (int preset = 0; preset <= 4; preset += 2) {
            ByteBuffer blocks = new JniCompressionBackend().compressBc7(image, width, height, preset);
            assertNotNull(blocks);
            assertSameAsNative(blocks, width, height);
        }
    }

    @Test
    void transparentTexelsComeBackAsExactlyZero() {
        int width = 64, height = 64;
        ByteBuffer image = noisyCutout(width, height, 11);
        ExactTransparency.clearHidden(image, width, height);
        ByteBuffer blocks = TesseraRuntime.isNativeActive()
                ? TesseraRuntime.backend().compressBc7(image, width, height, 2)
                : SoftwareBc7Encoder.encode(image, width, height);
        ByteBuffer writable = ByteBuffer.allocateDirect(blocks.remaining()).order(ByteOrder.LITTLE_ENDIAN);
        writable.put(blocks.duplicate()).flip();

        double before = psnr(image, Bc7Decoder.decode(writable, width, height));
        ExactTransparency.fixBc7(writable, image, width, height);
        assertEquals(0, ExactTransparency.maxHiddenColour(writable, image, width, height));
        ByteBuffer decoded = Bc7Decoder.decode(writable, width, height);
        for (int i = 0; i < width * height; i++) {
            if (image.get(i * 4 + 3) == 0) {
                assertEquals(0, decoded.getInt(i * 4), "texel " + i);
            }
        }
        // Noise in every block is the worst case for a line from black: the loss stays small.
        double after = psnr(image, decoded);
        assertTrue(after > before - 2, "visible texels " + before + " dB before the fix-up, " + after + " after");
        if (staged()) {
            assertSameAsNative(writable, width, height);
        }
    }

    private static void assertSameAsNative(ByteBuffer blocks, int width, int height) {
        ByteBuffer expected = JniCompressionBackend.decompressBc7(blocks, width, height);
        assertNotNull(expected);
        ByteBuffer actual = Bc7Decoder.decode(blocks, width, height);
        for (int i = 0; i < width * height; i++) {
            int block = (i / width / 4) * ((width + 3) / 4) + (i % width) / 4;
            assertEquals(Integer.toHexString(expected.getInt(i * 4)), Integer.toHexString(actual.getInt(i * 4)),
                    "texel " + i + " (block " + block + ", mode "
                            + Long.numberOfTrailingZeros(blocks.getLong(blocks.position() + block * 16)) + ")");
        }
    }

    /** Few-colour tiles with transparent holes and some translucent texels, like GUI sprites. */
    private static ByteBuffer noisyCutout(int width, int height, long seed) {
        Random random = new Random(seed);
        int[] palette = new int[6];
        for (int i = 0; i < palette.length; i++) {
            palette[i] = random.nextInt() & 0xFFFFFF;
        }
        ByteBuffer buf = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < width * height; i++) {
            int roll = random.nextInt(10);
            int rgb = random.nextInt() & 0xFFFFFF;
            int alpha = roll < 4 ? 0 : roll < 5 ? 128 : 255;
            buf.putInt(i * 4, (roll < 8 ? palette[(i / 3) % palette.length] : rgb) | alpha << 24);
        }
        return buf;
    }

    private static double psnr(ByteBuffer source, ByteBuffer decoded) {
        double sum = 0;
        int n = 0;
        for (int i = 0; i < source.capacity() / 4; i++) {
            if (source.get(i * 4 + 3) == 0) {
                continue;
            }
            for (int c = 0; c < 4; c++) {
                double d = (source.get(i * 4 + c) & 0xFF) - (decoded.get(i * 4 + c) & 0xFF);
                sum += d * d;
                n++;
            }
        }
        double mse = sum / n;
        return mse == 0 ? 99 : 10 * Math.log10(255 * 255 / mse);
    }

    private static boolean staged() {
        String os = System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT);
        String arch = System.getProperty("os.arch").toLowerCase(java.util.Locale.ROOT);
        String resource = os.contains("linux") && (arch.equals("amd64") || arch.equals("x86_64"))
                ? "natives/linux-x86_64/libtessera_bridge.so"
                : os.contains("win") ? "natives/windows-x86_64/tessera_bridge.dll" : null;
        return resource != null && Bc7DecoderTest.class.getClassLoader().getResource(resource) != null;
    }

    private static void assumeNative() {
        assumeTrue(staged(), "no tessera_bridge staged for this platform");
    }
}
