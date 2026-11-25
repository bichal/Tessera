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
 *   <li>every BC7 preset on an image large enough to be split over threads, with cutout texels:
 *       PSNR and cutout coverage survive, and two runs give the same blocks;</li>
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
    void everyBc7PresetKeepsCutouts() {
        assumeNativeStaged();
        int width = 258, height = 130; // 2145 blocks, above the native threading threshold; partial edges
        ByteBuffer source = cutoutPattern(width, height);
        byte[] first = null;
        for (int preset = 0; preset <= 7; preset++) {
            ByteBuffer bc7 = new JniCompressionBackend().compressBc7(source, width, height, preset);
            assertNotNull(bc7, "preset " + preset + " rejected the input");
            ByteBuffer decoded = JniCompressionBackend.decompressBc7(bc7, width, height);
            assertNotNull(decoded, "preset " + preset + " produced blocks the decoder rejects");
            double psnr = psnr(source, decoded);
            int flipped = 0, opaqueChanged = 0;
            for (int i = 3; i < width * height * 4; i += 4) {
                int a = source.get(i) & 0xFF, b = decoded.get(i) & 0xFF;
                if ((a >= 26) != (b >= 26)) {
                    flipped++;
                }
                if (a == 255 && b != 255) {
                    opaqueChanged++;
                }
            }
            System.out.printf("[bc7] preset %d: PSNR %.2f dB, cutout flips %d, opaque alpha changed %d%n",
                    preset, psnr, flipped, opaqueChanged);
            assertTrue(psnr >= MIN_PSNR_DB, "preset " + preset + " PSNR " + psnr);
            assertEquals(0, flipped, "preset " + preset + ": texels that changed side of the cutout threshold");
            if (preset == 0) {
                first = new byte[bc7.remaining()];
                bc7.duplicate().get(first);
                // Same input again: the threaded split must give byte-identical blocks.
                ByteBuffer again = new JniCompressionBackend().compressBc7(source, width, height, 0);
                byte[] second = new byte[again.remaining()];
                again.duplicate().get(second);
                assertTrue(java.util.Arrays.equals(first, second), "BC7 output differs between two runs");
            }
        }
        ByteBuffer bc1 = new JniCompressionBackend().compressBc1(gradient(width, height), width, height, QUALITY);
        assertNotNull(bc1, "BC1 rejected the input");
        assertEquals(((width + 3) / 4) * ((height + 3) / 4) * 8, bc1.remaining(), "BC1 is 8 bytes per block");
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

    /** Noisy opaque tiles with fully transparent holes, like a cutout block atlas. */
    private static ByteBuffer cutoutPattern(int width, int height) {
        ByteBuffer buf = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.LITTLE_ENDIAN);
        long seed = 42;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                seed = seed * 6364136223846793005L + 1442695040888963407L;
                int noise = (int) (seed >>> 61); // 0-7, texture-like grain
                boolean hole = ((x / 16 + y / 16) % 3 == 0) && ((x * 7 + y * 3) % 5 < 2);
                buf.put((byte) (hole ? 0 : 60 + (x % 16) * 8 + noise));
                buf.put((byte) (hole ? 0 : 120 + (y % 16) * 6 - noise));
                buf.put((byte) (hole ? 0 : 40 + noise * 4));
                buf.put((byte) (hole ? 0 : 0xFF));
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
