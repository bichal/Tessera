package net.bichal.mods.tessera.compress.backend;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Minimal self-test of the bundled native encoder, run by {@code ./gradlew test}.
 * It checks these things, in order:
 * <ol>
 *   <li>the library loads, which runs the ABI check of the static initializer;</li>
 *   <li>the ABI it reports is the one this build expects;</li>
 *   <li>a deterministic image survives encode and decode: the output size is
 *       exactly the block count, and the decoded image stays close to the source
 *       (PSNR). The size is not a multiple of 4, so partial edge blocks are cropped.</li>
 *   <li>the same for ASTC at 4x4 and 6x6, decoded by astc-encoder's own decoder (not Tessera's code),
 *       with a translucent image too.</li>
 * </ol>
 * Skipped when no library is staged for this platform, unless
 * {@code -Dtessera.requireNative=true} (the release gate), which turns the skip into a failure.
 */
class NativeBridgeSelfTest {

    private static final int WIDTH = 67, HEIGHT = 37;
    private static final int QUALITY = 4;
    private static final double MIN_PSNR_DB = 30.0;
    private static final String RESOURCE = platformResource();

    @Test
    void libraryLoadsAndReportsTheExpectedAbi() {
        assumeNativeStaged();
        assertEquals(JniCompressionBackend.EXPECTED_ABI_VERSION, JniCompressionBackend.abiVersion());
    }

    @Test
    void encodeThenDecodeStaysCloseToTheSource() {
        assumeNativeStaged();
        ByteBuffer source = gradient(WIDTH, HEIGHT);

        ByteBuffer bc7 = new JniCompressionBackend().compressBc7(source, WIDTH, HEIGHT, QUALITY);
        assertNotNull(bc7, "the encoder rejected the input");
        int blocks = ((WIDTH + 3) / 4) * ((HEIGHT + 3) / 4);
        assertEquals(blocks * 16, bc7.remaining(), "BC7 output is 16 bytes per 4x4 block");

        ByteBuffer decoded = JniCompressionBackend.decompressBc7(bc7, WIDTH, HEIGHT);
        assertNotNull(decoded, "the decoder rejected the blocks");
        assertEquals(WIDTH * HEIGHT * 4, decoded.remaining(), "decoded size is width * height RGBA8");

        double psnr = psnr(source, decoded);
        assertTrue(psnr >= MIN_PSNR_DB, "round-trip PSNR " + psnr + " dB, expected at least " + MIN_PSNR_DB);
    }

    @Test
    void astcRoundTripsAtBothBlockSizes() {
        assumeNativeStaged();
        JniCompressionBackend backend = new JniCompressionBackend();
        assertTrue(backend.supportsAstc());
        for (int[] block : new int[][]{{4, 4, 30}, {6, 6, 28}}) {
            ByteBuffer source = gradient(WIDTH, HEIGHT);
            ByteBuffer astc = backend.compressAstc(source, WIDTH, HEIGHT, block[0], block[1], QUALITY, true);
            assertNotNull(astc, "astc-encoder rejected the " + block[0] + "x" + block[1] + " input");
            int blocks = ((WIDTH + block[0] - 1) / block[0]) * ((HEIGHT + block[1] - 1) / block[1]);
            assertEquals(blocks * 16, astc.remaining(), "ASTC is 16 bytes per block");

            ByteBuffer decoded = JniCompressionBackend.decompressAstc(astc, WIDTH, HEIGHT, block[0], block[1]);
            assertNotNull(decoded);
            double psnr = psnr(source, decoded);
            System.out.printf("[consumption] ASTC %dx%d %dx%d gradient: %d bytes (%.2f bits/texel), PSNR %.1f dB%n",
                    block[0], block[1], WIDTH, HEIGHT, astc.remaining(), astc.remaining() * 8.0 / (WIDTH * HEIGHT), psnr);
            assertTrue(psnr >= block[2], block[0] + "x" + block[1] + " PSNR " + psnr + " dB");
        }
    }

    @Test
    void astcKeepsAlpha() {
        assumeNativeStaged();
        JniCompressionBackend backend = new JniCompressionBackend();
        ByteBuffer source = ByteBuffer.allocateDirect(WIDTH * HEIGHT * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                boolean hole = (x / 8 + y / 8) % 3 == 0;
                source.put((byte) (hole ? 0 : 40 + x * 2)).put((byte) (hole ? 0 : 200 - y)).put((byte) (hole ? 0 : 90));
                source.put((byte) (hole ? 0 : 255));
            }
        }
        source.flip();
        ByteBuffer astc = backend.compressAstc(source, WIDTH, HEIGHT, 4, 4, QUALITY, true);
        ByteBuffer decoded = JniCompressionBackend.decompressAstc(astc, WIDTH, HEIGHT, 4, 4);
        assertNotNull(decoded);
        int wrong = 0;
        for (int i = 0; i < WIDTH * HEIGHT; i++) {
            int expected = source.get(i * 4 + 3) & 0xFF;
            int actual = decoded.get(i * 4 + 3) & 0xFF;
            if (Math.abs(expected - actual) > 8) {
                wrong++;
            }
        }
        assertEquals(0, wrong, "texels whose alpha is off by more than 8");
    }

    private static void assumeNativeStaged() {
        boolean staged = RESOURCE != null
                && NativeBridgeSelfTest.class.getClassLoader().getResource(RESOURCE) != null;
        if (!staged && Boolean.getBoolean("tessera.requireNative")) {
            fail("no tessera_bridge staged for this platform (" + RESOURCE + "), and tessera.requireNative is set");
        }
        assumeTrue(staged, "no tessera_bridge staged for this platform; run the native build first");
    }

    /** Where the bridge is staged on the classpath, or {@code null} on platforms without one. */
    private static String platformResource() {
        String os = System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT);
        String arch = System.getProperty("os.arch").toLowerCase(java.util.Locale.ROOT);
        boolean x64 = arch.equals("amd64") || arch.equals("x86_64");
        if (os.contains("win") && x64) {
            return "natives/windows-x86_64/tessera_bridge.dll";
        }
        if (os.contains("linux") && x64) {
            return "natives/linux-x86_64/libtessera_bridge.so";
        }
        if (os.contains("linux") && (arch.equals("aarch64") || arch.equals("arm64"))) {
            return "natives/linux-aarch64/libtessera_bridge.so";
        }
        if (os.contains("mac")) {
            return "natives/macos-" + (x64 ? "x86_64" : "aarch64") + "/libtessera_bridge.dylib";
        }
        return null;
    }

    /** Smooth gradient with a non-trivial blue channel; alpha is opaque. */
    private static ByteBuffer gradient(int width, int height) {
        ByteBuffer buf = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                buf.put((byte) (x * 255 / (width - 1)));
                buf.put((byte) (y * 255 / (height - 1)));
                buf.put((byte) ((x + y) & 0xFF));
                buf.put((byte) 0xFF);
            }
        }
        buf.flip();
        return buf;
    }

    private static double psnr(ByteBuffer a, ByteBuffer b) {
        int n = a.remaining();
        double squared = 0;
        for (int i = 0; i < n; i++) {
            int d = (a.get(a.position() + i) & 0xFF) - (b.get(b.position() + i) & 0xFF);
            squared += d * d;
        }
        double mse = squared / n;
        return mse == 0 ? Double.POSITIVE_INFINITY : 10 * Math.log10(255.0 * 255.0 / mse);
    }
}
