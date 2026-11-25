package net.bichal.mods.tessera.atlas;

import net.bichal.mods.tessera.util.ParallelWork;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;

/**
 * Sprite-family grouping and mip generation for atlas assembly. Plain per-pixel
 * arithmetic (box-filter averaging, an 8x8 DCT) with no OS or GPU dependency,
 * so it stays in Java.
 *
 * <p>{@link #downsampleBoxFilter} averages whole texels in integer registers,
 * two channels per pass (see {@link #average4}), which needs no vector API and
 * so runs as fast on every JVM.
 */
public final class SpriteFamilyAnalyzer {

    private static final int CHANNELS = 4;
    private static final int DCT_SIZE = 8;
    private static final double[][] COSINE_TABLE = buildCosineTable();

    private SpriteFamilyAnalyzer() {
    }

    // ----------------------------------------------------------------
    // Box-filter downsampling
    // ----------------------------------------------------------------

    /** 2x box-filter downsample, edge-clamped. Returns {pixels, width, height} packed as a record. */
    public record DownsampleResult(byte[] pixels, int width, int height) {
    }

    public static DownsampleResult downsampleBoxFilter(byte[] pixels, int width, int height) {
        int w = Math.max(width, 1);
        int h = Math.max(height, 1);
        int outW = Math.max(w / 2, 1);
        int outH = Math.max(h / 2, 1);
        byte[] output = new byte[outW * outH * CHANNELS];

        // Output rows are independent: large levels are filtered in parallel bands.
        if ((long) outW * outH < PARALLEL_TEXELS) {
            downsampleRows(pixels, w, h, outW, output, 0, outH);
        } else {
            int bands = (outH + BAND_ROWS - 1) / BAND_ROWS;
            ParallelWork.forEach(bands, band -> downsampleRows(pixels, w, h, outW, output,
                    band * BAND_ROWS, Math.min(outH, (band + 1) * BAND_ROWS)));
        }

        return new DownsampleResult(output, outW, outH);
    }

    /** Output levels with at least this many texels are filtered in parallel bands of rows. */
    private static final int PARALLEL_TEXELS = 256 * 256;
    /** Output rows per parallel band. */
    private static final int BAND_ROWS = 64;

    private static final VarHandle INT_LE = MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.LITTLE_ENDIAN);

    private static void downsampleRows(byte[] pixels, int w, int h, int outW, byte[] output, int firstRow, int endRow) {
        if (pixels.length < w * h * CHANNELS) {
            downsampleRowsShort(pixels, w, h, outW, output, firstRow, endRow);
            return;
        }
        for (int outY = firstRow; outY < endRow; outY++) {
            int row0 = Math.min(outY * 2, h - 1) * w;
            int row1 = Math.min(outY * 2 + 1, h - 1) * w;
            int out = outY * outW * CHANNELS;
            for (int outX = 0; outX < outW; outX++, out += CHANNELS) {
                int x0 = Math.min(outX * 2, w - 1);
                int x1 = Math.min(outX * 2 + 1, w - 1);
                INT_LE.set(output, out, average4(
                        (int) INT_LE.get(pixels, (row0 + x0) * CHANNELS), (int) INT_LE.get(pixels, (row0 + x1) * CHANNELS),
                        (int) INT_LE.get(pixels, (row1 + x0) * CHANNELS), (int) INT_LE.get(pixels, (row1 + x1) * CHANNELS)));
            }
        }
    }

    /**
     * Per channel, {@code (sum + 2) / 4} of four RGBA8 texels packed as little-endian ints: the box
     * filter with the Rust original's rounding. Two channels share each pass, one per 16-bit lane
     * (a lane sums to at most 1022, so it never carries into the next).
     */
    static int average4(int a, int b, int c, int d) {
        int even = (a & 0x00FF00FF) + (b & 0x00FF00FF) + (c & 0x00FF00FF) + (d & 0x00FF00FF) + 0x00020002;
        int odd = ((a >>> 8) & 0x00FF00FF) + ((b >>> 8) & 0x00FF00FF) + ((c >>> 8) & 0x00FF00FF)
                + ((d >>> 8) & 0x00FF00FF) + 0x00020002;
        return ((even >>> 2) & 0x00FF00FF) | (((odd >>> 2) & 0x00FF00FF) << 8);
    }

    /** {@link #downsampleRows} for a buffer shorter than its size says: missing bytes read as 0. */
    private static void downsampleRowsShort(byte[] pixels, int w, int h, int outW, byte[] output, int firstRow, int endRow) {
        for (int outY = firstRow; outY < endRow; outY++) {
            int y0 = Math.min(outY * 2, h - 1);
            int y1 = Math.min(outY * 2 + 1, h - 1);
            for (int outX = 0; outX < outW; outX++) {
                int x0 = Math.min(outX * 2, w - 1);
                int x1 = Math.min(outX * 2 + 1, w - 1);
                for (int c = 0; c < CHANNELS; c++) {
                    int sum = byteAt(pixels, (y0 * w + x0) * CHANNELS + c) + byteAt(pixels, (y0 * w + x1) * CHANNELS + c)
                            + byteAt(pixels, (y1 * w + x0) * CHANNELS + c) + byteAt(pixels, (y1 * w + x1) * CHANNELS + c);
                    output[(outY * outW + outX) * CHANNELS + c] = (byte) ((sum + 2) >> 2);
                }
            }
        }
    }

    private static int byteAt(byte[] pixels, int index) {
        return index < pixels.length ? pixels[index] & 0xFF : 0;
    }

    /** Repeated {@link #downsampleBoxFilter} down to a 1x1 level or {@code maxLevel} steps, whichever comes first. */
    public static List<DownsampleResult> buildMipChain(byte[] pixels, int width, int height, int maxLevel) {
        List<DownsampleResult> chain = new ArrayList<>(maxLevel + 1);
        chain.add(new DownsampleResult(pixels, width, height));
        for (int i = 0; i < maxLevel; i++) {
            DownsampleResult prev = chain.get(chain.size() - 1);
            if (prev.width() <= 1 && prev.height() <= 1) {
                break;
            }
            chain.add(downsampleBoxFilter(prev.pixels(), prev.width(), prev.height()));
        }
        return chain;
    }

    // ----------------------------------------------------------------
    // Perceptual fingerprint (8x8 DCT, pHash-style) + family grouping
    // ----------------------------------------------------------------

    private static double[][] buildCosineTable() {
        double[][] table = new double[DCT_SIZE][DCT_SIZE];
        for (int x = 0; x < DCT_SIZE; x++) {
            for (int u = 0; u < DCT_SIZE; u++) {
                table[x][u] = Math.cos(((2.0 * x + 1.0) * u * Math.PI) / (2.0 * DCT_SIZE));
            }
        }
        return table;
    }

    private static double[][] downsampleToLuminance(byte[] rgba8, int srcOffset, int width, int height, int stride) {
        double[][] luminance = new double[DCT_SIZE][DCT_SIZE];
        for (int by = 0; by < DCT_SIZE; by++) {
            int startY = (by * height) / DCT_SIZE;
            int endY = Math.max(((by + 1) * height) / DCT_SIZE, startY + 1);
            for (int bx = 0; bx < DCT_SIZE; bx++) {
                int startX = (bx * width) / DCT_SIZE;
                int endX = Math.max(((bx + 1) * width) / DCT_SIZE, startX + 1);

                double sum = 0.0;
                int samples = 0;
                for (int y = startY; y < Math.min(endY, height); y++) {
                    for (int x = startX; x < Math.min(endX, width); x++) {
                        int offset = srcOffset + (y * stride + x) * CHANNELS;
                        if (offset + 3 >= rgba8.length) {
                            continue;
                        }
                        int r = rgba8[offset] & 0xFF;
                        int g = rgba8[offset + 1] & 0xFF;
                        int b = rgba8[offset + 2] & 0xFF;
                        sum += 0.299 * r + 0.587 * g + 0.114 * b;
                        samples++;
                    }
                }
                luminance[by][bx] = samples == 0 ? 0.0 : sum / samples;
            }
        }
        return luminance;
    }

    private static double[][] forwardDct(double[][] block) {
        double[][] result = new double[DCT_SIZE][DCT_SIZE];
        for (int u = 0; u < DCT_SIZE; u++) {
            for (int v = 0; v < DCT_SIZE; v++) {
                double sum = 0.0;
                for (int x = 0; x < DCT_SIZE; x++) {
                    for (int y = 0; y < DCT_SIZE; y++) {
                        sum += block[y][x] * COSINE_TABLE[x][u] * COSINE_TABLE[y][v];
                    }
                }
                double cu = u == 0 ? 1.0 / Math.sqrt(2) : 1.0;
                double cv = v == 0 ? 1.0 / Math.sqrt(2) : 1.0;
                result[v][u] = 0.25 * cu * cv * sum;
            }
        }
        return result;
    }

    private static double median(double[] values) {
        double[] sorted = values.clone();
        Arrays.sort(sorted);
        int mid = sorted.length / 2;
        return sorted.length % 2 == 0 ? (sorted[mid - 1] + sorted[mid]) / 2.0 : sorted[mid];
    }

    /** 64-bit perceptual fingerprint of one sprite region, for {@link #groupBySimilarity}. */
    public static long fingerprint(byte[] rgba8, int srcOffset, int width, int height) {
        return fingerprint(rgba8, srcOffset, width, height, width);
    }

    /**
     * {@link #fingerprint(byte[], int, int, int)} of a region inside a larger image whose rows are
     * {@code stride} texels apart, such as a sprite inside its assembled atlas.
     */
    public static long fingerprint(byte[] rgba8, int srcOffset, int width, int height, int stride) {
        double[][] luminance = downsampleToLuminance(rgba8, srcOffset, width, height, stride);
        double[][] dct = forwardDct(luminance);

        double[] lowFrequency = new double[DCT_SIZE * DCT_SIZE - 1];
        int idx = 0;
        for (int y = 0; y < DCT_SIZE; y++) {
            for (int x = 0; x < DCT_SIZE; x++) {
                if (x == 0 && y == 0) {
                    continue;
                }
                lowFrequency[idx++] = dct[y][x];
            }
        }

        double med = median(lowFrequency);
        long fp = 0L;
        int bit = 0;
        for (int y = 0; y < DCT_SIZE; y++) {
            for (int x = 0; x < DCT_SIZE; x++) {
                if (x == 0 && y == 0) {
                    continue;
                }
                if (dct[y][x] > med) {
                    fp |= 1L << bit;
                }
                bit++;
            }
        }
        return fp;
    }

    public record Family(int representativeIndex, List<Integer> memberIndices) {
    }

    /**
     * Groups sprites into families by Hamming distance between fingerprints,
     * restricted to same-tint pairs -- a tinted and an untinted sprite never
     * share a family regardless of visual similarity, matching the original
     * Rust behavior exactly (tint changes a sprite's actual rendered color
     * per-instance, so grouping across the tint boundary would defeat the
     * whole point of family-based atlas deduplication).
     */
    public static List<Family> groupBySimilarity(long[] fingerprints, boolean[] tinted, int maxHammingDistance) {
        int count = fingerprints.length;
        boolean[] visited = new boolean[count];
        List<Family> families = new ArrayList<>();

        for (int i = 0; i < count; i++) {
            if (visited[i]) {
                continue;
            }
            visited[i] = true;
            List<Integer> members = new ArrayList<>();
            Deque<Integer> frontier = new ArrayDeque<>();
            frontier.push(i);

            while (!frontier.isEmpty()) {
                int current = frontier.pop();
                for (int j = 0; j < count; j++) {
                    if (visited[j] || tinted[j] != tinted[current]) {
                        continue;
                    }
                    int hamming = Long.bitCount(fingerprints[current] ^ fingerprints[j]);
                    if (hamming <= maxHammingDistance) {
                        visited[j] = true;
                        members.add(j);
                        frontier.push(j);
                    }
                }
            }
            families.add(new Family(i, members));
        }
        return families;
    }
}
