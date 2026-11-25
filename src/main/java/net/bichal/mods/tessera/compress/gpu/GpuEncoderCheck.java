package net.bichal.mods.tessera.compress.gpu;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * The startup check a GPU encoder has to pass before it encodes any atlas: it encodes a fixed test
 * image (smooth gradients, noisy tiles and cutout holes, with partial edge blocks), the CPU decodes the
 * result, and it must come back the right size, close to the source and with every cutout texel on
 * the same side of the alpha threshold. A wrong block layout, a broken mode or a driver that
 * miscompiles the shader fails here, not in front of the player.
 */
public final class GpuEncoderCheck {

    /** Decodes BC7 blocks to RGBA8 on the CPU ({@code null} if it rejects them). */
    @FunctionalInterface
    public interface Bc7Decoder {
        ByteBuffer decode(ByteBuffer blocks, int width, int height);
    }

    public record Result(boolean passed, String detail, double psnr, long nanos) {
    }

    /** Odd sizes on purpose: partial blocks on both edges. */
    static final int WIDTH = 70, HEIGHT = 42;
    static final double MIN_PSNR_DB = 30.0;
    /** Minecraft's cutout threshold (alpha < 0.1 is discarded). */
    static final int CUTOUT_ALPHA = 26;
    static final int QUALITY = 4;

    private GpuEncoderCheck() {
    }

    public static Result run(GpuBc7Encoder encoder, Bc7Decoder decoder) {
        ByteBuffer source = testImage();
        long start = System.nanoTime();
        ByteBuffer blocks;
        try {
            blocks = encoder.encode(source.duplicate(), WIDTH, HEIGHT, QUALITY);
        } catch (Throwable t) {
            return fail("the encoder threw " + t, start);
        }
        long nanos = System.nanoTime() - start;
        if (blocks == null) {
            return fail("the encoder returned nothing", start);
        }
        int expected = ((WIDTH + 3) / 4) * ((HEIGHT + 3) / 4) * 16;
        if (blocks.remaining() != expected) {
            return fail("the encoder returned " + blocks.remaining() + " bytes, expected " + expected, start);
        }
        ByteBuffer decoded;
        try {
            decoded = decoder.decode(blocks.duplicate(), WIDTH, HEIGHT);
        } catch (Throwable t) {
            return fail("the CPU decoder threw " + t, start);
        }
        if (decoded == null || decoded.remaining() < WIDTH * HEIGHT * 4) {
            return fail("the CPU decoder rejected the blocks", start);
        }
        double squared = 0;
        int flipped = 0;
        for (int i = 0; i < WIDTH * HEIGHT * 4; i++) {
            int a = source.get(source.position() + i) & 0xFF;
            int b = decoded.get(decoded.position() + i) & 0xFF;
            squared += (a - b) * (double) (a - b);
            if ((i & 3) == 3 && (a >= CUTOUT_ALPHA) != (b >= CUTOUT_ALPHA)) {
                flipped++;
            }
        }
        double mse = squared / (WIDTH * HEIGHT * 4.0);
        double psnr = mse == 0 ? Double.POSITIVE_INFINITY : 10 * Math.log10(255.0 * 255.0 / mse);
        if (flipped > 0) {
            return new Result(false, flipped + " texels changed side of the cutout threshold", psnr, nanos);
        }
        if (!(psnr >= MIN_PSNR_DB)) {
            return new Result(false, String.format("PSNR %.1f dB, below %.0f dB", psnr, MIN_PSNR_DB), psnr, nanos);
        }
        return new Result(true, String.format("PSNR %.1f dB", psnr), psnr, nanos);
    }

    private static Result fail(String detail, long start) {
        return new Result(false, detail, Double.NaN, System.nanoTime() - start);
    }

    /** Gradients in the top half, noisy 16x16 tiles with fully transparent holes in the bottom half. */
    static ByteBuffer testImage() {
        ByteBuffer buf = ByteBuffer.allocateDirect(WIDTH * HEIGHT * 4).order(ByteOrder.LITTLE_ENDIAN);
        long seed = 7;
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                seed = seed * 6364136223846793005L + 1442695040888963407L;
                int noise = (int) (seed >>> 61);
                if (y < HEIGHT / 2) {
                    buf.put((byte) (x * 255 / (WIDTH - 1)));
                    buf.put((byte) (y * 255 / (HEIGHT / 2 - 1)));
                    buf.put((byte) ((x * 3 + y * 5) & 0xFF));
                    buf.put((byte) 0xFF);
                } else {
                    boolean hole = ((x * 7 + y * 3) % 5) < 2 && (x / 8 + y / 8) % 2 == 0;
                    buf.put((byte) (hole ? 0 : 80 + (x % 16) * 6 + noise));
                    buf.put((byte) (hole ? 0 : 140 - (y % 16) * 4 + noise));
                    buf.put((byte) (hole ? 0 : 30 + noise * 5));
                    buf.put((byte) (hole ? 0 : 0xFF));
                }
            }
        }
        buf.flip();
        return buf;
    }
}
