package net.bichal.mods.tessera.compress.backend;

import net.bichal.mods.tessera.compress.software.SoftwareAstcEncoder;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The pure-Java ASTC encoder against astc-encoder's own decoder (from the bundled native bridge, so
 * the bitstream is judged by an implementation Tessera did not write). Skipped where no bridge is staged.
 */
class SoftwareAstcEncoderTest {

    private static final int WIDTH = 67, HEIGHT = 37;

    @Test
    void smoothOpaqueImagesDecodeCloseAtBothBlockSizes() {
        assumeDecoder();
        for (int[] block : new int[][]{{4, 40}, {6, 36}}) {
            ByteBuffer source = image((x, y, c) -> switch (c) {
                case 0 -> x * 255 / (WIDTH - 1);
                case 1 -> y * 255 / (HEIGHT - 1);
                case 2 -> (x + y) * 2 & 0xFF;
                default -> 255;
            });
            check(source, block[0], block[1], "smooth");
        }
    }

    @Test
    void texturedOpaqueImageStaysRecognisable() {
        assumeDecoder();
        Random random = new Random(3);
        ByteBuffer source = image((x, y, c) -> c == 3 ? 255 : 90 + (x / 4 + y / 4 * 3) % 5 * 20 + random.nextInt(18) + c * 12);
        check(source, 4, 28, "noisy 4x4 tiles");
        check(source, 6, 24, "noisy 6x6");
    }

    @Test
    void transparencyIsKept() {
        assumeDecoder();
        ByteBuffer source = image((x, y, c) -> {
            boolean hole = (x / 8 + y / 8) % 3 == 0;
            return c == 3 ? (hole ? 0 : 255) : hole ? 0 : 40 + x * 2 + c * 30;
        });
        ByteBuffer decoded = decode(SoftwareAstcEncoder.encode(source, WIDTH, HEIGHT, 4, 4), 4);
        int wrong = 0;
        for (int i = 0; i < WIDTH * HEIGHT; i++) {
            if (Math.abs((source.get(i * 4 + 3) & 0xFF) - (decoded.get(i * 4 + 3) & 0xFF)) > 40) {
                wrong++;
            }
        }
        System.out.printf("[consumption] Java ASTC 4x4 translucent: %d of %d texels with alpha off by more than 40%n",
                wrong, WIDTH * HEIGHT);
        // Blocks that mix hole and solid texels share two alpha endpoints, so some edge texels are soft.
        assertTrue(wrong < WIDTH * HEIGHT / 12, wrong + " texels");
        check(source, 4, 22, "translucent");
    }

    @Test
    void flatBlocksAreExact() {
        assumeDecoder();
        ByteBuffer source = image((x, y, c) -> switch (c) {
            case 0 -> 12;
            case 1 -> 200;
            case 2 -> 77;
            default -> 255;
        });
        for (int size : new int[]{4, 6}) {
            ByteBuffer decoded = decode(SoftwareAstcEncoder.encode(source, WIDTH, HEIGHT, size, size), size);
            for (int i = 0; i < WIDTH * HEIGHT * 4; i++) {
                assertEquals(source.get(i), decoded.get(i), "texel byte " + i + " at " + size + "x" + size);
            }
        }
    }

    @Test
    void declinesAnImageItCannotRepresentWell() {
        // Independent random colours per texel: one colour line per block cannot hold them.
        Random random = new Random(11);
        ByteBuffer noise = image((x, y, c) -> c == 3 ? 255 : random.nextInt(256));
        assertEquals(null, SoftwareAstcEncoder.encode(noise, WIDTH, HEIGHT, 4, 4));
        assertEquals(null, SoftwareAstcEncoder.encode(noise, WIDTH, HEIGHT, 6, 6));
    }

    @Test
    void sizeIsSixteenBytesPerBlockAndOtherFootprintsAreRefused() {
        ByteBuffer source = image((x, y, c) -> 100);
        assertEquals(((WIDTH + 3) / 4) * ((HEIGHT + 3) / 4) * 16, SoftwareAstcEncoder.encode(source, WIDTH, HEIGHT, 4, 4).remaining());
        assertEquals(((WIDTH + 5) / 6) * ((HEIGHT + 5) / 6) * 16, SoftwareAstcEncoder.encode(source, WIDTH, HEIGHT, 6, 6).remaining());
        assertTrue(!SoftwareAstcEncoder.supports(8, 8));
    }

    private static void check(ByteBuffer source, int block, double minPsnr, String what) {
        ByteBuffer blocks = SoftwareAstcEncoder.encode(source, WIDTH, HEIGHT, block, block);
        assertNotNull(blocks);
        double psnr = psnr(source, decode(blocks, block));
        System.out.printf("[consumption] Java ASTC %dx%d %s: %.1f dB (astc-encoder decode)%n", block, block, what, psnr);
        assertTrue(psnr >= minPsnr, what + " " + block + "x" + block + ": " + psnr + " dB, expected " + minPsnr);
    }

    private static ByteBuffer decode(ByteBuffer blocks, int block) {
        ByteBuffer decoded = JniCompressionBackend.decompressAstc(blocks, WIDTH, HEIGHT, block, block);
        assertNotNull(decoded, "astc-encoder rejected the blocks");
        return decoded;
    }

    private interface Texel {
        int at(int x, int y, int channel);
    }

    private static ByteBuffer image(Texel texel) {
        ByteBuffer buffer = ByteBuffer.allocateDirect(WIDTH * HEIGHT * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                for (int c = 0; c < 4; c++) {
                    buffer.put((byte) Math.max(0, Math.min(255, texel.at(x, y, c))));
                }
            }
        }
        return buffer.flip();
    }

    private static double psnr(ByteBuffer a, ByteBuffer b) {
        double squared = 0;
        int n = WIDTH * HEIGHT * 4;
        for (int i = 0; i < n; i++) {
            double d = (a.get(i) & 0xFF) - (b.get(i) & 0xFF);
            squared += d * d;
        }
        double mse = squared / n;
        return mse == 0 ? 99 : 10 * Math.log10(255.0 * 255.0 / mse);
    }

    private static void assumeDecoder() {
        try {
            JniCompressionBackend.abiVersion();
        } catch (Throwable t) {
            if (Boolean.getBoolean("tessera.requireNative")) {
                fail("no tessera_bridge to decode with: " + t);
            }
            assumeTrue(false, "no tessera_bridge staged: " + t);
        }
    }
}
