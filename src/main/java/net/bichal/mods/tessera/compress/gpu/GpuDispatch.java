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
import java.util.concurrent.locks.LockSupport;

/**
 * Runs a BC7 encode on the GPU for any thread. The GL context belongs to the render thread, so a
 * compression thread hands the work to it through Minecraft's own task queue (drained every frame,
 * the loading screen included) in short steps: start the job, then advance it once every couple of
 * milliseconds, each step reading back the slices that finished and queueing more without waiting on
 * the GPU, so frames keep coming while it works. The render thread never waits on compression jobs,
 * so this cannot deadlock. One image is on the GPU at a time: while it is busy, the other compression
 * threads encode on the CPU, so both work at once. Every result is spot-checked on the CPU before it
 * is used: a sample of its blocks is decoded by the native decoder and must stay close to the source,
 * with no cutout texel flipped. Anything wrong (an exception, no progress for {@link #TIMEOUT_SECONDS},
 * a failed spot check) returns {@code null}, and the caller encodes on the CPU; an exception or a
 * timeout also turns the GPU path off for the session.
 */
public final class GpuDispatch {

    private static final Logger LOGGER = LoggerFactory.getLogger("Tessera/GpuDispatch");
    private static final long TIMEOUT_SECONDS = 30;
    /** Blocks decoded on the CPU per encode to check it. */
    private static final int SPOT_BLOCKS = 256;
    /** Well below any real BC7 encode of game textures: this catches broken output, not quality. */
    private static final double SPOT_MIN_PSNR = 24.0;
    private static final int CUTOUT_ALPHA = 26;
    /** Pause between steps of a job, so steps land on separate frames instead of spinning one task drain. */
    private static final long STEP_NANOS = 2_000_000L;
    /** Images on the GPU at once; the rest go to the CPU meanwhile. */
    private static final int MAX_JOBS = 1;
    private static final java.util.concurrent.atomic.AtomicInteger JOBS = new java.util.concurrent.atomic.AtomicInteger();

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
            int jobs = JOBS.get();
            if (jobs >= MAX_JOBS || !JOBS.compareAndSet(jobs, jobs + 1)) {
                return null; // the GPU is busy with another image: this one is faster on the CPU meanwhile
            }
            try {
                blocks = runInSteps(minecraft, encoder, input, width, height, qualityPreset);
            } finally {
                JOBS.decrementAndGet();
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

    /** The encode as a job advanced from this thread, a step per frame; {@code null} on any failure. */
    private static ByteBuffer runInSteps(Minecraft minecraft, GpuBc7Encoder encoder, ByteBuffer input,
                                         int width, int height, int quality) {
        GpuBc7Encoder.Job job = onRenderThread(minecraft, () -> encoder.begin(input.duplicate(), width, height, quality));
        if (job == null) {
            return null;
        }
        try {
            long lastProgress = System.nanoTime();
            int lastBack = 0;
            while (true) {
                Boolean finished = onRenderThread(minecraft, job::advance);
                if (finished == null) {
                    return null;
                }
                if (finished) {
                    return job.blocks();
                }
                long now = System.nanoTime();
                // The future's completion publishes the render thread's writes, the count included.
                if (job.blocksBack() != lastBack) {
                    lastBack = job.blocksBack();
                    lastProgress = now;
                } else if (now - lastProgress > TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS)) {
                    GpuCompression.disable("a GPU encode made no progress for " + TIMEOUT_SECONDS + " s");
                    return null;
                }
                LockSupport.parkNanos(STEP_NANOS);
                if (Thread.currentThread().isInterrupted()) {
                    return null;
                }
            }
        } finally {
            minecraft.execute(job::release);
        }
    }

    /** Runs {@code step} on the render thread and waits for it; {@code null} if it threw or timed out. */
    private static <T> T onRenderThread(Minecraft minecraft, java.util.function.Supplier<T> step) {
        CompletableFuture<T> future = CompletableFuture.supplyAsync(() -> {
            try {
                return step.get();
            } catch (Throwable t) {
                GpuCompression.disable("the GPU encoder threw " + t);
                return null;
            }
        }, minecraft);
        try {
            return future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            GpuCompression.disable("the render thread did not run a GPU encode step within " + TIMEOUT_SECONDS + " s");
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            return null;
        }
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
