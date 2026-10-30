package net.bichal.mods.tessera.compress.software;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.stream.IntStream;

/**
 * Pure-Java ASTC LDR block encoder, for the 4x4 and 6x6 footprints. It is what ASTC falls back to
 * where the native astc-encoder bridge cannot be loaded, above all Android launchers, whose
 * bionic libc cannot load the bundled glibc library.
 *
 * <p>Every block is one partition with a single colour-endpoint pair and a weight grid, in the
 * few layouts whose integer sequences are plain bits (no trits or quints, so each field is
 * written as it is):
 * <ul>
 *   <li>a block whose texels are all the same colour is a void-extent block (exact);</li>
 *   <li>an opaque block is LDR RGB (CEM 8) with 8-bit endpoints and a 4x4 grid of 3-bit weights
 *       (4x4 blocks) or a 5x5 grid of 2-bit weights (6x6 blocks);</li>
 *   <li>a block with any transparency is LDR RGBA (CEM 12) with 8-bit endpoints and a 4x4 grid of
 *       2-bit weights.</li>
 * </ul>
 * Endpoints come from the principal axis of the block's colours, then a least-squares refit of
 * both ends for the weights chosen. Weights of a grid smaller than the block are solved for
 * through the same bilinear infill the decoder applies. This is a fixed-effort encoder, well below
 * astc-encoder's quality (one colour line per block, 4 or 8 weight levels), and the {@code quality}
 * preset is ignored. It therefore estimates the error of what it wrote and refuses an image whose
 * PSNR would be visibly poor ({@link #MIN_PSNR_4X4}, {@link #MIN_PSNR_6X6}): that atlas stays RGBA8888
 * rather than ship damaged textures. Pixel art with several colours per block, mostly in textures
 * with cut-out transparency, is what it declines.
 */
public final class SoftwareAstcEncoder {

    private static final int CEM_RGB = 8;
    private static final int CEM_RGBA = 12;
    /** Weight levels as the decoder unquantizes them (0..64): 2 bits, then 3 bits. */
    private static final int[] LEVELS_2BIT = {0, 21, 43, 64};
    private static final int[] LEVELS_3BIT = {0, 9, 18, 27, 37, 46, 55, 64};

    /** Lowest estimated PSNR (dB) of an image this encoder will return; below it, {@code encode} returns null. */
    public static final double MIN_PSNR_4X4 = 27.0;
    public static final double MIN_PSNR_6X6 = 24.0;

    private SoftwareAstcEncoder() {
    }

    public static boolean supports(int blockWidth, int blockHeight) {
        return blockWidth == blockHeight && (blockWidth == 4 || blockWidth == 6);
    }

    public static ByteBuffer encode(ByteBuffer rgba8, int width, int height, int blockWidth, int blockHeight) {
        return encode(rgba8, width, height, blockWidth, blockHeight, true);
    }

    /**
     * @param guardQuality refuse (return null) an image whose estimated PSNR is below the minimum; the
     *                     mip levels past the first are blurred and sampled from afar, so they skip it
     */
    public static ByteBuffer encode(ByteBuffer rgba8, int width, int height, int blockWidth, int blockHeight,
                                    boolean guardQuality) {
        if (!supports(blockWidth, blockHeight)) {
            return null;
        }
        int blocksX = (width + blockWidth - 1) / blockWidth;
        int blocksY = (height + blockHeight - 1) / blockHeight;
        ByteBuffer src = rgba8.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        int base = src.position();
        byte[] out = new byte[blocksX * blocksY * 16];
        java.util.concurrent.atomic.DoubleAdder squaredError = new java.util.concurrent.atomic.DoubleAdder();
        java.util.concurrent.atomic.LongAdder samples = new java.util.concurrent.atomic.LongAdder();

        // Block rows are independent: spread them over the common pool.
        IntStream.range(0, blocksY).parallel().forEach(by -> {
            Encoder encoder = new Encoder(blockWidth, blockHeight);
            long[] block = new long[2];
            for (int bx = 0; bx < blocksX; bx++) {
                encoder.load(src, base, width, height, bx, by);
                encoder.encode(block);
                int at = (by * blocksX + bx) * 16;
                for (int i = 0; i < 8; i++) {
                    out[at + i] = (byte) (block[0] >>> (8 * i));
                    out[at + 8 + i] = (byte) (block[1] >>> (8 * i));
                }
            }
            squaredError.add(encoder.squaredError);
            samples.add(encoder.samples);
        });
        double mse = samples.sum() == 0 ? 0 : squaredError.sum() / samples.sum();
        double psnr = mse == 0 ? Double.POSITIVE_INFINITY : 10 * Math.log10(255.0 * 255.0 / mse);
        if (guardQuality && psnr < (blockWidth == 4 ? MIN_PSNR_4X4 : MIN_PSNR_6X6)) {
            return null;
        }
        return ByteBuffer.allocateDirect(out.length).order(ByteOrder.LITTLE_ENDIAN).put(out).flip();
    }

    /** One worker's scratch space and the per-footprint tables. */
    private static final class Encoder {

        private final int blockWidth;
        private final int blockHeight;
        private final int texels;
        /** RGBA of each texel, 0..255. */
        private final int[] px;
        private final double[] t;
        private final int[] w;
        // Weight grids: {4x4 grid, and for 6x6 blocks the 5x5 grid}, each as bilinear infill.
        /** Error of what this worker wrote against the visible texels (squared, per sample), and the samples. */
        double squaredError;
        long samples;
        private final Infill grid4;
        private final Infill grid5;

        Encoder(int blockWidth, int blockHeight) {
            this.blockWidth = blockWidth;
            this.blockHeight = blockHeight;
            this.texels = blockWidth * blockHeight;
            this.px = new int[texels * 4];
            this.t = new double[texels];
            this.w = new int[texels];
            this.grid4 = new Infill(blockWidth, blockHeight, 4, 4);
            this.grid5 = blockWidth == 6 ? new Infill(blockWidth, blockHeight, 5, 5) : null;
        }

        void load(ByteBuffer src, int base, int width, int height, int bx, int by) {
            for (int y = 0; y < blockHeight; y++) {
                int sy = Math.min(height - 1, by * blockHeight + y);
                for (int x = 0; x < blockWidth; x++) {
                    int sx = Math.min(width - 1, bx * blockWidth + x);
                    int at = base + (sy * width + sx) * 4;
                    int i = (y * blockWidth + x) * 4;
                    px[i] = src.get(at) & 0xFF;
                    px[i + 1] = src.get(at + 1) & 0xFF;
                    px[i + 2] = src.get(at + 2) & 0xFF;
                    px[i + 3] = src.get(at + 3) & 0xFF;
                }
            }
        }

        void encode(long[] block) {
            boolean uniform = true;
            boolean opaque = true;
            for (int i = 0; i < texels; i++) {
                for (int c = 0; c < 4; c++) {
                    uniform &= px[i * 4 + c] == px[c];
                }
                opaque &= px[i * 4 + 3] == 255;
            }
            if (uniform) {
                voidExtent(block, px[0], px[1], px[2], px[3]);
                if (px[3] != 0) {
                    samples += texels; // exact, and part of a visible sprite; empty atlas space is not counted
                }
                return;
            }
            int channels = opaque ? 3 : 4;
            if (opaque && blockWidth == 4) {
                colorBlock(block, grid4, LEVELS_3BIT, 3, channels);
            } else if (opaque) {
                colorBlock(block, grid5, LEVELS_2BIT, 2, channels);
            } else {
                colorBlock(block, grid4, LEVELS_2BIT, 2, channels);
            }
        }

        /** A single-colour block: bits 8:0 = 0x1FC, reserved 11:10 set, all extents "unknown", then RGBA as UNORM16. */
        private static void voidExtent(long[] block, int r, int g, int b, int a) {
            long lo = 0x1FCL | (3L << 10);
            lo |= 0x1FFFL << 12 | 0x1FFFL << 25 | 0x1FFFL << 38 | 0x1FFFL << 51;
            block[0] = lo;
            block[1] = (long) (r * 257) | (long) (g * 257) << 16 | (long) (b * 257) << 32 | (long) (a * 257) << 48;
        }

        private void colorBlock(long[] block, Infill grid, int[] levels, int weightBits, int channels) {
            // Principal axis of the colours (power iteration on the covariance).
            double[] mean = new double[4];
            for (int i = 0; i < texels; i++) {
                for (int c = 0; c < channels; c++) {
                    mean[c] += px[i * 4 + c];
                }
            }
            for (int c = 0; c < channels; c++) {
                mean[c] /= texels;
            }
            double[][] cov = new double[4][4];
            for (int i = 0; i < texels; i++) {
                for (int a = 0; a < channels; a++) {
                    double da = px[i * 4 + a] - mean[a];
                    for (int b = a; b < channels; b++) {
                        cov[a][b] += da * (px[i * 4 + b] - mean[b]);
                    }
                }
            }
            double[] axis = {1, 1, 1, channels == 4 ? 1 : 0};
            for (int iteration = 0; iteration < 6; iteration++) {
                double[] next = new double[4];
                for (int a = 0; a < channels; a++) {
                    for (int b = 0; b < channels; b++) {
                        next[a] += (a <= b ? cov[a][b] : cov[b][a]) * axis[b];
                    }
                }
                double length = Math.sqrt(next[0] * next[0] + next[1] * next[1] + next[2] * next[2] + next[3] * next[3]);
                if (length < 1e-9) {
                    break;
                }
                for (int c = 0; c < channels; c++) {
                    axis[c] = next[c] / length;
                }
            }

            // Three candidate endpoint pairs, each refined and scored; the best one is written.
            //   1. the extremes of the colours along their principal axis,
            //   2. the corners of their bounding box, oriented along that axis,
            //   3. the two colours farthest apart (exact for blocks of two flat colours).
            int[][] candidates = new int[6][4];
            double lo = Double.MAX_VALUE;
            double hi = -Double.MAX_VALUE;
            int[] min = {255, 255, 255, 255};
            int[] max = {0, 0, 0, 0};
            for (int i = 0; i < texels; i++) {
                double d = 0;
                for (int c = 0; c < channels; c++) {
                    d += (px[i * 4 + c] - mean[c]) * axis[c];
                    min[c] = Math.min(min[c], px[i * 4 + c]);
                    max[c] = Math.max(max[c], px[i * 4 + c]);
                }
                lo = Math.min(lo, d);
                hi = Math.max(hi, d);
            }
            for (int pair = 0; pair < 3; pair++) {
                candidates[pair * 2][3] = 255;
                candidates[pair * 2 + 1][3] = 255;
            }
            for (int c = 0; c < channels; c++) {
                candidates[0][c] = clamp8(mean[c] + axis[c] * lo);
                candidates[1][c] = clamp8(mean[c] + axis[c] * hi);
                candidates[2][c] = axis[c] >= 0 ? min[c] : max[c];
                candidates[3][c] = axis[c] >= 0 ? max[c] : min[c];
            }
            int far0 = 0, far1 = 0;
            long farthest = -1;
            for (int i = 0; i < texels; i++) {
                for (int j = i + 1; j < texels; j++) {
                    long dist = 0;
                    for (int c = 0; c < channels; c++) {
                        long d = px[i * 4 + c] - px[j * 4 + c];
                        dist += d * d;
                    }
                    if (dist > farthest) {
                        farthest = dist;
                        far0 = i;
                        far1 = j;
                    }
                }
            }
            for (int c = 0; c < channels; c++) {
                candidates[4][c] = px[far0 * 4 + c];
                candidates[5][c] = px[far1 * 4 + c];
            }

            int[] e0 = new int[4];
            int[] e1 = new int[4];
            int[] best0 = new int[4];
            int[] best1 = new int[4];
            long bestError = Long.MAX_VALUE;
            for (int pair = 0; pair < 3; pair++) {
                System.arraycopy(candidates[pair * 2], 0, e0, 0, 4);
                System.arraycopy(candidates[pair * 2 + 1], 0, e1, 0, 4);
                // Weights for the endpoints, then endpoints refitted to those weights.
                for (int round = 0; round < 2; round++) {
                    chooseWeights(grid, levels, e0, e1, channels);
                    refit(grid, e0, e1, channels);
                }
                chooseWeights(grid, levels, e0, e1, channels);
                long error = error(e0, e1, channels);
                if (error < bestError) {
                    bestError = error;
                    System.arraycopy(e0, 0, best0, 0, 4);
                    System.arraycopy(e1, 0, best1, 0, 4);
                }
            }
            e0 = best0;
            e1 = best1;
            chooseWeights(grid, levels, e0, e1, channels);
            addVisibleError(e0, e1, channels);

            // The decoder reads (v1 >= v0 summed over RGB) as "no blue contraction": order the pair that way.
            int sum0 = e0[0] + e0[1] + e0[2];
            int sum1 = e1[0] + e1[1] + e1[2];
            if (sum0 > sum1) {
                int[] swap = e0;
                e0 = e1;
                e1 = swap;
                int top = levels.length - 1;
                for (int i = 0; i < grid.gridSize; i++) {
                    gridWeights[i] = top - gridWeights[i];
                }
            }
            write(block, grid, weightBits, channels, e0, e1);
        }

        /** Squared error of the block decoded from the endpoints and the weights now in {@link #w}. */
        private long error(int[] e0, int[] e1, int channels) {
            long sum = 0;
            for (int i = 0; i < texels; i++) {
                for (int c = 0; c < channels; c++) {
                    int decoded = (e0[c] * (64 - w[i]) + e1[c] * w[i] + 32) >> 6;
                    long d = px[i * 4 + c] - decoded;
                    sum += d * d;
                }
            }
            return sum;
        }

        /**
         * Adds the block's error over the texels that can be seen to {@link #squaredError}, one sample
         * per visible texel (a texel with alpha 0 does not count, as in the self-test's measure):
         * the mean over all channels of the squared difference with what the decoder will produce.
         */
        private void addVisibleError(int[] e0, int[] e1, int channels) {
            for (int i = 0; i < texels; i++) {
                if (channels == 4 && px[i * 4 + 3] == 0) {
                    continue;
                }
                double texel = 0;
                for (int c = 0; c < 4; c++) {
                    int decoded = c < channels
                            ? (e0[c] * (64 - w[i]) + e1[c] * w[i] + 32) >> 6
                            : 255;
                    double d = px[i * 4 + c] - decoded;
                    texel += d * d;
                }
                squaredError += texel / 4;
                samples++;
            }
        }

        /** Chosen level index of each grid weight, after {@link #chooseWeights}. */
        private int[] gridWeights = new int[25];

        /** Picks a grid weight (as an index into {@code levels}) for the endpoint pair. */
        private void chooseWeights(Infill grid, int[] levels, int[] e0, int[] e1, int channels) {
            // The ideal weight of each texel, 0..64: its projection on the endpoint line.
            double[] direction = new double[4];
            double length2 = 0;
            for (int c = 0; c < channels; c++) {
                direction[c] = e1[c] - e0[c];
                length2 += direction[c] * direction[c];
            }
            double[] ideal = new double[texels];
            for (int i = 0; i < texels; i++) {
                double d = 0;
                for (int c = 0; c < channels; c++) {
                    d += (px[i * 4 + c] - e0[c]) * direction[c];
                }
                ideal[i] = length2 < 1e-9 ? 0 : Math.max(0, Math.min(64, 64 * d / length2));
            }
            double[] gridValues = grid.solve(ideal);
            int top = levels.length - 1;
            for (int g = 0; g < grid.gridSize; g++) {
                int best = 0;
                for (int l = 1; l <= top; l++) {
                    if (Math.abs(levels[l] - gridValues[g]) < Math.abs(levels[best] - gridValues[g])) {
                        best = l;
                    }
                }
                gridWeights[g] = best;
            }
            grid.expand(gridWeights, levels, w);
        }

        /** Least-squares endpoints for the weights in {@link #w}, per channel. */
        private void refit(Infill grid, int[] e0, int[] e1, int channels) {
            double a = 0, b = 0, d = 0; // normal equations of c0*(1-x) + c1*x, x = weight / 64
            for (int i = 0; i < texels; i++) {
                double x = w[i] / 64.0;
                a += (1 - x) * (1 - x);
                b += (1 - x) * x;
                d += x * x;
            }
            double det = a * d - b * b;
            if (det < 1e-6) {
                return;
            }
            for (int c = 0; c < channels; c++) {
                double p = 0, q = 0;
                for (int i = 0; i < texels; i++) {
                    double x = w[i] / 64.0;
                    p += (1 - x) * px[i * 4 + c];
                    q += x * px[i * 4 + c];
                }
                e0[c] = clamp8((d * p - b * q) / det);
                e1[c] = clamp8((a * q - b * p) / det);
            }
        }

        private void write(long[] block, Infill grid, int weightBits, int channels, int[] e0, int[] e1) {
            // Block mode: weight grid and precision, one plane, low precision range.
            int rangeCode = weightBits == 3 ? 7 : 4;                 // R: 3 bits -> 0..7, 2 bits -> 0..3
            int widthBits = grid.gridWidth - 4;                      // B: width = B + 4
            int heightBits = grid.gridHeight - 2;                    // A: height = A + 2
            int mode = (rangeCode >> 1 & 1) | (rangeCode >> 2 & 1) << 1   // R1 -> bit 0, R2 -> bit 1
                    | (rangeCode & 1) << 4                                 // R0 -> bit 4
                    | heightBits << 5 | widthBits << 7;
            long lo = mode;
            // One partition (bits 12:11 = 0), colour endpoint mode in bits 16:13.
            lo |= (long) (channels == 3 ? CEM_RGB : CEM_RGBA) << 13;
            // Endpoint values from bit 17 up, 8 bits each: r0 r1 g0 g1 b0 b1 (a0 a1).
            long hi = 0;
            int bit = 17;
            for (int c = 0; c < channels; c++) {
                for (int end = 0; end < 2; end++) {
                    int value = end == 0 ? e0[c] : e1[c];
                    if (bit < 64) {
                        lo |= (long) value << bit;
                        if (bit + 8 > 64) {
                            hi |= (long) value >>> (64 - bit);
                        }
                    } else {
                        hi |= (long) value << (bit - 64);
                    }
                    bit += 8;
                }
            }
            // Weights from the top: bit i of the weight stream is block bit 127 - i.
            int streamBit = 0;
            for (int g = 0; g < grid.gridSize; g++) {
                for (int k = 0; k < weightBits; k++) {
                    if ((gridWeights[g] >> k & 1) != 0) {
                        int at = 127 - streamBit;
                        if (at >= 64) {
                            hi |= 1L << (at - 64);
                        } else {
                            lo |= 1L << at;
                        }
                    }
                    streamBit++;
                }
            }
            block[0] = lo;
            block[1] = hi;
        }

        private static int clamp8(double v) {
            return (int) Math.max(0, Math.min(255, Math.round(v)));
        }
    }

    /**
     * A weight grid of {@code gridWidth x gridHeight} spread over a block, as the decoder infills
     * it: each texel takes a bilinear mix of four grid weights (in sixteenths).
     */
    private static final class Infill {

        final int gridWidth;
        final int gridHeight;
        final int gridSize;
        private final int texels;
        /** For each texel, its four grid weights' indices and their sixteenths. */
        private final int[][] index;
        private final int[][] factor;

        Infill(int blockWidth, int blockHeight, int gridWidth, int gridHeight) {
            this.gridWidth = gridWidth;
            this.gridHeight = gridHeight;
            this.gridSize = gridWidth * gridHeight;
            this.texels = blockWidth * blockHeight;
            this.index = new int[texels][4];
            this.factor = new int[texels][4];
            int ds = (1024 + blockWidth / 2) / (blockWidth - 1);
            int dt = (1024 + blockHeight / 2) / (blockHeight - 1);
            for (int y = 0; y < blockHeight; y++) {
                for (int x = 0; x < blockWidth; x++) {
                    int gs = (ds * x * (gridWidth - 1) + 32) >> 6;
                    int gt = (dt * y * (gridHeight - 1) + 32) >> 6;
                    int js = gs >> 4, fs = gs & 15;
                    int jt = gt >> 4, ft = gt & 15;
                    int w11 = (fs * ft + 8) >> 4;
                    int w10 = ft - w11;
                    int w01 = fs - w11;
                    int w00 = 16 - fs - ft + w11;
                    int i = y * blockWidth + x;
                    int v0 = js + jt * gridWidth;
                    index[i] = new int[]{v0, v0 + 1, v0 + gridWidth, v0 + gridWidth + 1};
                    factor[i] = new int[]{w00, w01, w10, w11};
                }
            }
        }

        /** The grid weights (continuous, 0..64) whose infill best matches {@code ideal} (least squares). */
        double[] solve(double[] ideal) {
            double[] value = new double[gridSize];
            // Start from the ideal at the texel nearest each grid point, then Gauss-Seidel on the normal equations.
            double[] sum = new double[gridSize];
            double[] count = new double[gridSize];
            for (int i = 0; i < texels; i++) {
                for (int k = 0; k < 4; k++) {
                    if (index[i][k] < gridSize && factor[i][k] > 0) {
                        sum[index[i][k]] += ideal[i] * factor[i][k];
                        count[index[i][k]] += factor[i][k];
                    }
                }
            }
            for (int g = 0; g < gridSize; g++) {
                value[g] = count[g] > 0 ? sum[g] / count[g] : 32;
            }
            for (int pass = 0; pass < 4; pass++) {
                for (int g = 0; g < gridSize; g++) {
                    double numerator = 0;
                    double denominator = 0;
                    for (int i = 0; i < texels; i++) {
                        for (int k = 0; k < 4; k++) {
                            if (index[i][k] != g || factor[i][k] == 0) {
                                continue;
                            }
                            double f = factor[i][k] / 16.0;
                            double others = 0;
                            for (int m = 0; m < 4; m++) {
                                if (m != k && index[i][m] < gridSize) {
                                    others += factor[i][m] / 16.0 * value[index[i][m]];
                                }
                            }
                            numerator += f * (ideal[i] - others);
                            denominator += f * f;
                        }
                    }
                    if (denominator > 0) {
                        value[g] = Math.max(0, Math.min(64, numerator / denominator));
                    }
                }
            }
            return value;
        }

        /** The weight of every texel (0..64) for grid weights given as indices into {@code levels}. */
        void expand(int[] gridWeights, int[] levels, int[] out) {
            for (int i = 0; i < texels; i++) {
                int sum = 0;
                for (int k = 0; k < 4; k++) {
                    int at = index[i][k];
                    int v = at < gridSize ? levels[gridWeights[at]] : 0;
                    sum += v * factor[i][k];
                }
                out[i] = (sum + 8) >> 4;
            }
        }
    }
}
