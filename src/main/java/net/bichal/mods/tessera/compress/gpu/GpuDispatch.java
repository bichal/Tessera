package net.bichal.mods.tessera.compress.gpu;

import net.bichal.mods.tessera.compress.backend.TesseraRuntime;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Runs a BC7 encode on the GPU for any thread. The GL context belongs to the render thread, so a
 * compression thread hands the encode to it through Minecraft's own task queue (drained every frame,
 * the loading screen included) and waits; the render thread never waits on compression jobs, so this
 * cannot deadlock. Every result is spot-checked on the CPU before it is used: a sample of its blocks
 * is decoded by the native decoder and must stay close to the source, with no cutout texel flipped.
 * Anything wrong (an exception, a timeout, a failed spot check) returns {@code null}, and the caller
 * encodes on the CPU; an exception or a timeout also turns the GPU path off for the session.
 */
public final class GpuDispatch {

    private static final Logger LOGGER = LoggerFactory.getLogger("Tessera/GpuDispatch");
    private static final long TIMEOUT_SECONDS = 30;
    /** Blocks decoded on the CPU per encode to check it. */
    private static final int SPOT_BLOCKS = 256;
    /** Well below any real BC7 encode of game textures: this catches broken output, not quality. */
    private static final double SPOT_MIN_PSNR = 24.0;
    private static final int CUTOUT_ALPHA = 26;

    /** Images encoded on the GPU, and GPU results sent back to the CPU by the spot check (for the self-test). */
    private static final java.util.concurrent.atomic.AtomicLong ENCODED = new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong REJECTED = new java.util.concurrent.atomic.AtomicLong();

    private GpuDispatch() {
    }

    public static long encoded() {
        return ENCODED.get();
    }

    public static long rejected() {
        return REJECTED.get();
    }

    /** BC7 blocks encoded on the GPU, or {@code null} to encode on the CPU. Any thread. */
    public static ByteBuffer encodeBc7(ByteBuffer rgba8, int width, int height, int qualityPreset) {
        GpuBc7Encoder encoder = GpuCompression.activeEncoder();
        if (encoder == null) {
            return null;
        }
        ByteBuffer input = rgba8.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        ByteBuffer blocks;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.isSameThread()) {
            blocks = run(encoder, input, width, height, qualityPreset);
        } else {
            CompletableFuture<ByteBuffer> job = CompletableFuture.supplyAsync(
                    () -> run(encoder, input, width, height, qualityPreset), minecraft);
            try {
                blocks = job.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                GpuCompression.disable("the render thread did not run a GPU encode within " + TIMEOUT_SECONDS + " s");
                return null;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            } catch (Exception e) {
                return null;
            }
        }
        if (blocks == null) {
            return null;
        }
        String problem = spotCheck(input, width, height, blocks);
        if (problem != null) {
            LOGGER.warn("GPU encode of a {}x{} image failed its spot check ({}); encoding it on the CPU", width, height, problem);
            REJECTED.incrementAndGet();
            return null;
        }
        ENCODED.incrementAndGet();
        return blocks;
    }

    private static ByteBuffer run(GpuBc7Encoder encoder, ByteBuffer rgba, int width, int height, int quality) {
        try {
            return encoder.encode(rgba.duplicate(), width, height, quality);
        } catch (Throwable t) {
            GpuCompression.disable("the GPU encoder threw " + t);
            return null;
        }
    }

    /** {@code null} if a sample of the blocks decodes close to the source, else what is wrong. */
    static String spotCheck(ByteBuffer rgba, int width, int height, ByteBuffer blocks) {
        int blocksX = (width + 3) / 4;
        int fullX = width / 4, fullY = height / 4;
        int full = fullX * fullY;
        if (full == 0) {
            return null; // smaller than a block: nothing to sample
        }
        int count = Math.min(SPOT_BLOCKS, full);
        // The sampled blocks side by side: a 4*count x 4 image and its blocks.
        ByteBuffer source = ByteBuffer.allocateDirect(count * 64).order(ByteOrder.LITTLE_ENDIAN);
        ByteBuffer sample = ByteBuffer.allocateDirect(count * 16).order(ByteOrder.LITTLE_ENDIAN);
        int base = rgba.position();
        for (int i = 0; i < count; i++) {
            int b = (int) ((long) i * full / count);
            int bx = b % fullX, by = b / fullX;
            for (int y = 0; y < 4; y++) {
                for (int x = 0; x < 4; x++) {
                    int src = base + ((by * 4 + y) * width + bx * 4 + x) * 4;
                    source.putInt((y * count * 4 + i * 4 + x) * 4, rgba.getInt(src));
                }
            }
            sample.put(i * 16, blocks, blocks.position() + (by * blocksX + bx) * 16, 16);
        }
        ByteBuffer decoded = TesseraRuntime.decodeBc7(sample, count * 4, 4);
        if (decoded == null) {
            return TesseraRuntime.isNativeActive() ? "the decoder rejected its blocks" : null;
        }
        double squared = 0;
        long visible = 0;
        int flipped = 0;
        for (int t = 0; t < count * 16; t++) {
            int s = source.getInt(t * 4), d = decoded.getInt(decoded.position() + t * 4);
            int sa = s >>> 24, da = d >>> 24;
            if ((sa >= CUTOUT_ALPHA) != (da >= CUTOUT_ALPHA)) {
                flipped++;
            }
            if (sa > 0) {
                for (int shift = 0; shift < 24; shift += 8) {
                    int e = ((s >>> shift) & 0xFF) - ((d >>> shift) & 0xFF);
                    squared += e * e;
                }
                visible++;
            }
        }
        if (flipped > 0) {
            return flipped + " sampled texels changed side of the cutout threshold";
        }
        if (visible > 0) {
            double mse = squared / (visible * 3.0);
            double psnr = mse == 0 ? Double.POSITIVE_INFINITY : 10 * Math.log10(255.0 * 255.0 / mse);
            if (psnr < SPOT_MIN_PSNR) {
                return String.format("sampled PSNR %.1f dB", psnr);
            }
        }
        return null;
    }
}
