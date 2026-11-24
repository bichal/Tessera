package net.bichal.mods.tessera.compress.software;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * BC1 with one-bit alpha ({@code GL_COMPRESSED_RGBA_S3TC_DXT1_EXT}): 4 bits per texel for images
 * whose texels are all either fully opaque or fully transparent, half of what BC7 takes.
 *
 * <p>Built on an opaque BC1 encode (native or Java) of the same image. Blocks without
 * transparent texels keep that encoding, unless it is in 3-colour mode and uses index 3 (black),
 * which this format decodes as transparent: those are refitted in 4-colour mode. Blocks with
 * transparent texels are refitted in 3-colour mode over their visible texels only, with index 3
 * (transparent black) for the rest, so alpha comes back exactly.
 *
 * <p>The refit projects the visible texels on their principal axis, takes the extremes as
 * endpoints, then refines them once by least squares on the chosen indices. It only runs on the
 * blocks that need it (sprite edges), so the cost is a small fraction of the full encode.
 */
public final class Bc1PunchThrough {

    /** Texels with alpha below this are transparent (the input is expected to be 0 or 255 only). */
    private static final int ALPHA_THRESHOLD = 128;
    private static final int BYTES_PER_BLOCK = 8;

    private Bc1PunchThrough() {
    }

    /**
     * The encoded image and the PSNR of its visible texels' colour against the input
     * ({@link Double#POSITIVE_INFINITY} when exact).
     */
    public record Result(ByteBuffer blocks, double visiblePsnr) {
    }

    /**
     * @param rgba8        the image, tightly packed RGBA8, read from index 0
     * @param opaqueBlocks an opaque BC1 encoding of the same image ({@code width x height}); not modified
     * @return the punch-through encoding, or {@code null} when {@code opaqueBlocks} has the wrong size
     */
    public static Result encode(ByteBuffer rgba8, int width, int height, ByteBuffer opaqueBlocks) {
        int blocksX = BlockMath.blocksAcross(width);
        int blocksY = BlockMath.blocksAcross(height);
        long expected = (long) blocksX * blocksY * BYTES_PER_BLOCK;
        if (opaqueBlocks == null || opaqueBlocks.remaining() != expected) {
            return null;
        }
        ByteBuffer source = opaqueBlocks.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        int sourceBase = source.position();
        ByteBuffer out = ByteBuffer.allocateDirect((int) expected).order(ByteOrder.LITTLE_ENDIAN);

        int[] block = new int[BlockMath.BLOCK_TEXELS * 4];
        boolean[] visible = new boolean[BlockMath.BLOCK_TEXELS];
        int[] decoded = new int[BlockMath.BLOCK_TEXELS * 3];
        Fit fit = new Fit();
        long squaredError = 0;
        long visibleTexels = 0;

        for (int by = 0; by < blocksY; by++) {
            for (int bx = 0; bx < blocksX; bx++) {
                BlockMath.readBlock(rgba8, width, height, bx, by, block);
                int visibleCount = 0;
                for (int i = 0; i < BlockMath.BLOCK_TEXELS; i++) {
                    visible[i] = block[i * 4 + 3] >= ALPHA_THRESHOLD;
                    if (visible[i]) {
                        visibleCount++;
                    }
                }
                int offset = sourceBase + (by * blocksX + bx) * BYTES_PER_BLOCK;
                int c0 = source.getShort(offset) & 0xFFFF;
                int c1 = source.getShort(offset + 2) & 0xFFFF;
                int indices = source.getInt(offset + 4);

                if (visibleCount == 0) {
                    // 3-colour mode (c0 <= c1), every texel index 3: transparent black.
                    c0 = 0;
                    c1 = 0;
                    indices = -1;
                } else if (visibleCount < BlockMath.BLOCK_TEXELS) {
                    fit.run(block, visible, false);
                    c0 = fit.c0;
                    c1 = fit.c1;
                    indices = fit.indices;
                } else if (c0 <= c1 && usesIndex3(indices)) {
                    // An opaque block the encoder wrote with 3-colour black: here that is transparent.
                    fit.run(block, visible, true);
                    c0 = fit.c0;
                    c1 = fit.c1;
                    indices = fit.indices;
                }
                out.putShort((short) c0).putShort((short) c1).putInt(indices);

                if (visibleCount > 0) {
                    decode(c0, c1, indices, decoded);
                    for (int i = 0; i < BlockMath.BLOCK_TEXELS; i++) {
                        if (visible[i]) {
                            squaredError += BlockMath.rgbDistanceSquared(block[i * 4], block[i * 4 + 1], block[i * 4 + 2],
                                    decoded[i * 3], decoded[i * 3 + 1], decoded[i * 3 + 2]);
                            visibleTexels++;
                        }
                    }
                }
            }
        }
        out.flip();
        return new Result(out, psnr(squaredError, visibleTexels));
    }

    private static boolean usesIndex3(int indices) {
        for (int i = 0; i < BlockMath.BLOCK_TEXELS; i++) {
            if (((indices >>> (i * 2)) & 3) == 3) {
                return true;
            }
        }
        return false;
    }

    private static double psnr(long squaredError, long texels) {
        if (texels == 0 || squaredError == 0) {
            return Double.POSITIVE_INFINITY;
        }
        double mse = squaredError / (texels * 3.0);
        return 10.0 * Math.log10(255.0 * 255.0 / mse);
    }

    /** RGB of each texel as a BC1 decoder with 1-bit alpha reads the block (transparent texels decode black). */
    static void decode(int c0, int c1, int indices, int[] rgbOut) {
        int[] palette = new int[12];
        palette(c0, c1, palette);
        for (int i = 0; i < BlockMath.BLOCK_TEXELS; i++) {
            int index = (indices >>> (i * 2)) & 3;
            rgbOut[i * 3] = palette[index * 3];
            rgbOut[i * 3 + 1] = palette[index * 3 + 1];
            rgbOut[i * 3 + 2] = palette[index * 3 + 2];
        }
    }

    private static void palette(int c0, int c1, int[] palette) {
        int r0 = expandR(c0), g0 = expandG(c0), b0 = expandB(c0);
        int r1 = expandR(c1), g1 = expandG(c1), b1 = expandB(c1);
        palette[0] = r0;
        palette[1] = g0;
        palette[2] = b0;
        palette[3] = r1;
        palette[4] = g1;
        palette[5] = b1;
        if (c0 > c1) {
            palette[6] = (2 * r0 + r1) / 3;
            palette[7] = (2 * g0 + g1) / 3;
            palette[8] = (2 * b0 + b1) / 3;
            palette[9] = (r0 + 2 * r1) / 3;
            palette[10] = (g0 + 2 * g1) / 3;
            palette[11] = (b0 + 2 * b1) / 3;
        } else {
            palette[6] = (r0 + r1) / 2;
            palette[7] = (g0 + g1) / 2;
            palette[8] = (b0 + b1) / 2;
            palette[9] = 0;
            palette[10] = 0;
            palette[11] = 0;
        }
    }

    private static int pack565(double r, double g, double b) {
        int r5 = BlockMath.quantizeTo(clamp(r), 5);
        int g6 = BlockMath.quantizeTo(clamp(g), 6);
        int b5 = BlockMath.quantizeTo(clamp(b), 5);
        return (r5 << 11) | (g6 << 5) | b5;
    }

    private static int clamp(double value) {
        return (int) Math.max(0, Math.min(255, Math.round(value)));
    }

    private static int expandR(int packed) {
        return BlockMath.expandFrom((packed >>> 11) & 0x1F, 5);
    }

    private static int expandG(int packed) {
        return BlockMath.expandFrom((packed >>> 5) & 0x3F, 6);
    }

    private static int expandB(int packed) {
        return BlockMath.expandFrom(packed & 0x1F, 5);
    }

    /** Endpoint fit of one block over its visible texels, in 4-colour or 3-colour (+ transparent) mode. */
    private static final class Fit {
        int c0;
        int c1;
        int indices;
        private final int[] palette = new int[12];

        void run(int[] block, boolean[] visible, boolean fourColour) {
            double meanR = 0, meanG = 0, meanB = 0;
            int count = 0;
            for (int i = 0; i < BlockMath.BLOCK_TEXELS; i++) {
                if (visible[i]) {
                    meanR += block[i * 4];
                    meanG += block[i * 4 + 1];
                    meanB += block[i * 4 + 2];
                    count++;
                }
            }
            meanR /= count;
            meanG /= count;
            meanB /= count;

            double crr = 0, crg = 0, crb = 0, cgg = 0, cgb = 0, cbb = 0;
            for (int i = 0; i < BlockMath.BLOCK_TEXELS; i++) {
                if (visible[i]) {
                    double r = block[i * 4] - meanR, g = block[i * 4 + 1] - meanG, b = block[i * 4 + 2] - meanB;
                    crr += r * r;
                    crg += r * g;
                    crb += r * b;
                    cgg += g * g;
                    cgb += g * b;
                    cbb += b * b;
                }
            }
            // Principal axis by power iteration, started on the channel that varies most: a start on
            // the grey diagonal is orthogonal to the axis of a block such as red against green, and
            // would collapse both endpoints onto the mean.
            double ar = 0, ag = 0, ab = 0;
            if (crr >= cgg && crr >= cbb) {
                ar = 1;
            } else if (cgg >= cbb) {
                ag = 1;
            } else {
                ab = 1;
            }
            for (int iteration = 0; iteration < 8; iteration++) {
                double nr = crr * ar + crg * ag + crb * ab;
                double ng = crg * ar + cgg * ag + cgb * ab;
                double nb = crb * ar + cgb * ag + cbb * ab;
                double length = Math.sqrt(nr * nr + ng * ng + nb * nb);
                if (length < 1e-9) {
                    break;
                }
                ar = nr / length;
                ag = ng / length;
                ab = nb / length;
            }
            double minT = Double.MAX_VALUE, maxT = -Double.MAX_VALUE;
            for (int i = 0; i < BlockMath.BLOCK_TEXELS; i++) {
                if (visible[i]) {
                    double t = (block[i * 4] - meanR) * ar + (block[i * 4 + 1] - meanG) * ag + (block[i * 4 + 2] - meanB) * ab;
                    minT = Math.min(minT, t);
                    maxT = Math.max(maxT, t);
                }
            }
            int high = pack565(meanR + ar * maxT, meanG + ag * maxT, meanB + ab * maxT);
            int low = pack565(meanR + ar * minT, meanG + ag * minT, meanB + ab * minT);

            long bestError = Long.MAX_VALUE;
            for (int pass = 0; pass < 2; pass++) {
                long error = assign(block, visible, high, low, fourColour);
                if (error < bestError) {
                    bestError = error;
                    best = candidate;
                    bestIndices = candidateIndices;
                }
                if (error == 0 || !refine(block, visible, fourColour)) {
                    break;
                }
                high = refinedHigh;
                low = refinedLow;
            }
            c0 = best >>> 16;
            c1 = best & 0xFFFF;
            indices = bestIndices;
        }

        private int best;
        private int bestIndices;
        private int candidate;
        private int candidateIndices;
        private int refinedHigh;
        private int refinedLow;
        private final double[] weights = new double[BlockMath.BLOCK_TEXELS];

        /** Orders the endpoints for the mode, picks each texel's index; returns the squared error. */
        private long assign(int[] block, boolean[] visible, int high, int low, boolean fourColour) {
            int e0;
            int e1;
            if (fourColour) {
                e0 = Math.max(high, low);
                e1 = Math.min(high, low);
                if (e0 == e1) {
                    // Equal endpoints would select 3-colour mode: nudge one so c0 > c1 holds.
                    if (e0 == 0) {
                        e0 = 1;
                    } else {
                        e1 = e0 - 1;
                    }
                }
            } else {
                e0 = Math.min(high, low);
                e1 = Math.max(high, low);
            }
            palette(e0, e1, palette);
            int choices = fourColour ? 4 : 3;
            long error = 0;
            int packed = 0;
            for (int i = 0; i < BlockMath.BLOCK_TEXELS; i++) {
                if (!visible[i]) {
                    packed |= 3 << (i * 2);
                    continue;
                }
                int bestIndex = 0;
                int bestDistance = Integer.MAX_VALUE;
                for (int index = 0; index < choices; index++) {
                    int distance = BlockMath.rgbDistanceSquared(block[i * 4], block[i * 4 + 1], block[i * 4 + 2],
                            palette[index * 3], palette[index * 3 + 1], palette[index * 3 + 2]);
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        bestIndex = index;
                    }
                }
                packed |= bestIndex << (i * 2);
                error += bestDistance;
            }
            candidate = e0 << 16 | e1;
            candidateIndices = packed;
            return error;
        }

        /**
         * Least-squares endpoints for the indices just chosen: each visible texel is
         * {@code w * c0 + (1 - w) * c1}, with {@code w} fixed by its index.
         */
        private boolean refine(int[] block, boolean[] visible, boolean fourColour) {
            double aa = 0, ab = 0, bb = 0;
            double ar = 0, ag = 0, abl = 0, br = 0, bg = 0, bbl = 0;
            for (int i = 0; i < BlockMath.BLOCK_TEXELS; i++) {
                if (!visible[i]) {
                    continue;
                }
                int index = (candidateIndices >>> (i * 2)) & 3;
                double w = fourColour
                        ? switch (index) {
                            case 0 -> 1.0;
                            case 1 -> 0.0;
                            case 2 -> 2.0 / 3.0;
                            default -> 1.0 / 3.0;
                        }
                        : switch (index) {
                            case 0 -> 1.0;
                            case 1 -> 0.0;
                            default -> 0.5;
                        };
                weights[i] = w;
                double v = 1.0 - w;
                aa += w * w;
                ab += w * v;
                bb += v * v;
                ar += w * block[i * 4];
                ag += w * block[i * 4 + 1];
                abl += w * block[i * 4 + 2];
                br += v * block[i * 4];
                bg += v * block[i * 4 + 1];
                bbl += v * block[i * 4 + 2];
            }
            double determinant = aa * bb - ab * ab;
            if (Math.abs(determinant) < 1e-9) {
                return false;
            }
            double inverse = 1.0 / determinant;
            int first = pack565((ar * bb - br * ab) * inverse, (ag * bb - bg * ab) * inverse, (abl * bb - bbl * ab) * inverse);
            int second = pack565((br * aa - ar * ab) * inverse, (bg * aa - ag * ab) * inverse, (bbl * aa - abl * ab) * inverse);
            refinedHigh = first;
            refinedLow = second;
            return true;
        }
    }
}
