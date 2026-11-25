package net.bichal.mods.tessera.compress.software;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Fully transparent texels that decode as exactly (0, 0, 0, 0), for atlases whose sprites are drawn
 * with blend modes that read the colour of transparent texels: the GUI's crosshair inverts what is
 * behind it by the sprite's RGB, whatever its alpha, so a "transparent" texel with a trace of colour
 * shows up beside it.
 *
 * <p>Two steps around the usual encoder. Before it, {@link #clearHidden} sets those texels to 0
 * (instead of {@link TransparentTexelBleed}'s neighbouring colour). After it, {@link #fixBc7}
 * decodes every block that holds one and, where one did not come back as 0, encodes the block again
 * with one endpoint at 0 and those texels on it: exact by construction, with the visible texels
 * fitted along a line from black (single subset, so a block with several unrelated colours loses
 * some of them). BC1 with one-bit alpha needs neither step: its transparent texels decode as 0.
 */
public final class ExactTransparency {

    private static final int[] W2 = {0, 21, 43, 64};
    private static final int[] W3 = {0, 9, 18, 27, 37, 46, 55, 64};
    private static final int[] W4 = {0, 4, 9, 13, 17, 21, 26, 30, 34, 38, 43, 47, 51, 55, 60, 64};
    private static final int FIT_PASSES = 4;

    private ExactTransparency() {
    }

    /** Sets every texel with A = 0 to (0, 0, 0, 0), in a tightly packed RGBA8 image starting at index 0. */
    public static void clearHidden(ByteBuffer rgba, int width, int height) {
        int base = rgba.position();
        for (int i = 0, n = width * height; i < n; i++) {
            int at = base + i * 4;
            if (rgba.get(at + 3) == 0) {
                rgba.putInt(at, 0);
            }
        }
    }

    /**
     * Re-encodes, in place, the BC7 blocks of {@code rgba} ({@code width x height}, after
     * {@link #clearHidden}) whose A = 0 texels do not decode as exactly 0.
     *
     * @return the number of blocks encoded again
     */
    public static int fixBc7(ByteBuffer blocks, ByteBuffer rgba, int width, int height) {
        ByteBuffer out = blocks.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        int base = out.position();
        int blocksX = BlockMath.blocksAcross(width);
        int blocksY = BlockMath.blocksAcross(height);
        int[] block = new int[64];
        int[] decoded = new int[16];
        Fitter fitter = new Fitter();
        int fixed = 0;
        for (int by = 0; by < blocksY; by++) {
            for (int bx = 0; bx < blocksX; bx++) {
                BlockMath.readBlock(rgba, width, height, bx, by, block);
                if (!holdsHidden(block)) {
                    continue;
                }
                int at = base + (by * blocksX + bx) * 16;
                Bc7Decoder.decodeBlock(out.getLong(at), out.getLong(at + 8), decoded);
                if (hiddenExact(block, decoded)) {
                    continue;
                }
                long[] bits = fitter.encode(block);
                out.putLong(at, bits[0]);
                out.putLong(at + 8, bits[1]);
                fixed++;
            }
        }
        return fixed;
    }

    /** Largest colour channel (R, G or B) any A = 0 texel of {@code rgba} decodes to from {@code blocks}. */
    public static int maxHiddenColour(ByteBuffer blocks, ByteBuffer rgba, int width, int height) {
        ByteBuffer decoded = Bc7Decoder.decode(blocks, width, height);
        int max = 0;
        for (int i = 0, n = width * height; i < n; i++) {
            if (rgba.get(i * 4 + 3) == 0) {
                int v = decoded.getInt(i * 4);
                max = Math.max(max, Math.max(v & 0xFF, Math.max(v >>> 8 & 0xFF, v >>> 16 & 0xFF)));
            }
        }
        return max;
    }

    private static boolean holdsHidden(int[] block) {
        for (int i = 0; i < 16; i++) {
            if (block[i * 4 + 3] == 0) {
                return true;
            }
        }
        return false;
    }

    private static boolean hiddenExact(int[] block, int[] decoded) {
        for (int i = 0; i < 16; i++) {
            if (block[i * 4 + 3] == 0 && decoded[i] != 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * One block with its A = 0 texels at index 0 of an endpoint stored as 0: modes 6, 5 and 4 (both
     * index selections), whichever reproduces the visible texels best.
     */
    static final class Fitter {
        private final boolean[] hidden = new boolean[16];
        private final int[] idx = new int[16], colourIdx = new int[16], alphaIdx = new int[16];
        private final int[] stored = new int[5], colourStored = new int[5], alphaStored = new int[5];
        private final int[] tryIdx = new int[16], tryStored = new int[5], rec = new int[4];
        private final long[] best = new long[2], candidate = new long[2];

        long[] encode(int[] block) {
            for (int i = 0; i < 16; i++) {
                hidden[i] = block[i * 4 + 3] == 0;
            }
            long bestError = Long.MAX_VALUE;
            // Mode 6: RGBA together, 4-bit indices, 7 bits and a p-bit per endpoint.
            long error = fit(block, 0, 4, W4, 7, true, idx, stored);
            if (error < bestError) {
                bestError = error;
                packMode6(best);
            }
            // Mode 5: colour and alpha apart, 2-bit indices each, 7-bit colour and 8-bit alpha.
            error = fit(block, 0, 3, W2, 7, false, colourIdx, colourStored)
                    + fit(block, 3, 1, W2, 8, false, alphaIdx, alphaStored);
            if (error < bestError) {
                bestError = error;
                packMode45(best, 5, 0, 7, 8, 2, 2);
            }
            // Mode 4: 5-bit colour and 6-bit alpha; the 3-bit indices go to colour, then to alpha.
            error = fit(block, 0, 3, W3, 5, false, colourIdx, colourStored)
                    + fit(block, 3, 1, W2, 6, false, alphaIdx, alphaStored);
            if (error < bestError) {
                bestError = error;
                packMode45(best, 4, 1, 5, 6, 3, 2);
            }
            error = fit(block, 0, 3, W2, 5, false, colourIdx, colourStored)
                    + fit(block, 3, 1, W3, 6, false, alphaIdx, alphaStored);
            if (error < bestError) {
                packMode45(best, 4, 0, 5, 6, 2, 3);
            }
            return best;
        }

        /**
         * Fits channels {@code [first, first + count)} along a line from 0 to one endpoint, which goes
         * to {@code outStored} (with mode 6's p-bit at [4]); hidden texels take index 0.
         *
         * @return the squared error of the visible texels
         */
        private long fit(int[] block, int first, int count, int[] weights, int bits, boolean mode6,
                         int[] outIdx, int[] outStored) {
            float[] e = new float[count];
            for (int i = 0; i < 16; i++) {
                if (!hidden[i]) {
                    for (int c = 0; c < count; c++) {
                        e[c] = Math.max(e[c], block[i * 4 + first + c]);
                    }
                }
            }
            long bestError = Long.MAX_VALUE;
            for (int pass = 0; pass < FIT_PASSES; pass++) {
                quantize(e, count, bits, mode6);
                long error = 0;
                for (int i = 0; i < 16; i++) {
                    if (hidden[i]) {
                        tryIdx[i] = 0;
                        continue;
                    }
                    long texelBest = Long.MAX_VALUE;
                    for (int k = 0; k < weights.length; k++) {
                        long d = 0;
                        for (int c = 0; c < count; c++) {
                            int v = (weights[k] * rec[c] + 32) >> 6;
                            int diff = v - block[i * 4 + first + c];
                            d += (long) diff * diff;
                        }
                        if (d < texelBest) {
                            texelBest = d;
                            tryIdx[i] = k;
                        }
                    }
                    error += texelBest;
                }
                if (error < bestError) {
                    bestError = error;
                    System.arraycopy(tryIdx, 0, outIdx, 0, 16);
                    System.arraycopy(tryStored, 0, outStored, 0, 5);
                }
                // Least squares for the far endpoint, given the indices: e = sum(t v) / sum(t t).
                float tt = 0;
                float[] tv = new float[count];
                for (int i = 0; i < 16; i++) {
                    if (hidden[i]) {
                        continue;
                    }
                    float t = weights[tryIdx[i]] / 64f;
                    tt += t * t;
                    for (int c = 0; c < count; c++) {
                        tv[c] += t * block[i * 4 + first + c];
                    }
                }
                if (tt == 0) {
                    break;
                }
                for (int c = 0; c < count; c++) {
                    e[c] = Math.max(0f, Math.min(255f, tv[c] / tt));
                }
            }
            return bestError;
        }

        /** Stored values of the far endpoint into {@code tryStored}, its 8-bit values into {@code rec}. */
        private void quantize(float[] e, int count, int bits, boolean mode6) {
            if (mode6) {
                float bestError = Float.MAX_VALUE;
                for (int p = 0; p <= 1; p++) {
                    float error = 0;
                    for (int c = 0; c < count; c++) {
                        int v = Math.max(0, Math.min(127, Math.round((e[c] - p) / 2f)));
                        float d = ((v << 1) | p) - e[c];
                        error += d * d;
                    }
                    if (error < bestError) {
                        bestError = error;
                        for (int c = 0; c < count; c++) {
                            int v = Math.max(0, Math.min(127, Math.round((e[c] - p) / 2f)));
                            tryStored[c] = v;
                            rec[c] = (v << 1) | p;
                        }
                        tryStored[4] = p;
                    }
                }
                return;
            }
            int max = (1 << bits) - 1;
            for (int c = 0; c < count; c++) {
                int bestV = 0;
                float bestError = Float.MAX_VALUE;
                int guess = Math.round(e[c] * max / 255f);
                for (int v = Math.max(0, guess - 1); v <= Math.min(max, guess + 1); v++) {
                    float d = expand(v, bits) - e[c];
                    if (d * d < bestError) {
                        bestError = d * d;
                        bestV = v;
                    }
                }
                tryStored[c] = bestV;
                rec[c] = expand(bestV, bits);
            }
        }

        private void packMode6(long[] out) {
            int[] e0 = new int[5], e1 = stored.clone();
            int[] indices = idx.clone();
            if (indices[0] >= 8) { // the anchor index keeps its top bit 0: swap the endpoints
                int[] t = e0;
                e0 = e1;
                e1 = t;
                flip(indices, 15);
            }
            out[0] = out[1] = 0;
            int pos = put(out, 0, 1 << 6, 7);
            for (int c = 0; c < 4; c++) {
                pos = put(out, pos, e0[c], 7);
                pos = put(out, pos, e1[c], 7);
            }
            pos = put(out, pos, e0[4], 1);
            pos = put(out, pos, e1[4], 1);
            putIndices(out, pos, indices, 4);
        }

        /**
         * Modes 4 and 5, no rotation. {@code selection} 1 (mode 4 only) puts the 3-bit indices on colour.
         */
        private void packMode45(long[] out, int mode, int selection, int colourBits, int alphaBits,
                                int colourIndexBits, int alphaIndexBits) {
            int[] c0 = new int[3], c1 = colourStored.clone();
            int[] cIdx = colourIdx.clone();
            if (cIdx[0] >= 1 << (colourIndexBits - 1)) {
                int[] t = c0;
                c0 = c1;
                c1 = t;
                flip(cIdx, (1 << colourIndexBits) - 1);
            }
            int a0 = 0, a1 = alphaStored[0];
            int[] aIdx = alphaIdx.clone();
            if (aIdx[0] >= 1 << (alphaIndexBits - 1)) {
                a0 = a1;
                a1 = 0;
                flip(aIdx, (1 << alphaIndexBits) - 1);
            }
            out[0] = out[1] = 0;
            int pos = put(out, 0, 1 << mode, mode + 1);
            pos = put(out, pos, 0, 2); // rotation
            if (mode == 4) {
                pos = put(out, pos, selection, 1);
            }
            for (int c = 0; c < 3; c++) {
                pos = put(out, pos, c0[c], colourBits);
                pos = put(out, pos, c1[c], colourBits);
            }
            pos = put(out, pos, a0, alphaBits);
            pos = put(out, pos, a1, alphaBits);
            // The 2-bit set comes first: colour's, unless mode 4 gives colour the 3-bit set.
            if (selection == 0) {
                pos = putIndices(out, pos, cIdx, colourIndexBits);
                putIndices(out, pos, aIdx, alphaIndexBits);
            } else {
                pos = putIndices(out, pos, aIdx, alphaIndexBits);
                putIndices(out, pos, cIdx, colourIndexBits);
            }
        }

        private static void flip(int[] indices, int top) {
            for (int i = 0; i < 16; i++) {
                indices[i] = top - indices[i];
            }
        }

        private static int putIndices(long[] out, int pos, int[] indices, int bits) {
            pos = put(out, pos, indices[0], bits - 1);
            for (int i = 1; i < 16; i++) {
                pos = put(out, pos, indices[i], bits);
            }
            return pos;
        }

        private static int expand(int v, int bits) {
            return bits == 8 ? v : (v << (8 - bits)) | (v >>> (2 * bits - 8));
        }

        private static int put(long[] bits, int pos, int value, int numBits) {
            long v = value & ((1L << numBits) - 1);
            if (pos < 64) {
                bits[0] |= v << pos;
                if (pos + numBits > 64) {
                    bits[1] |= v >>> (64 - pos);
                }
            } else {
                bits[1] |= v << (pos - 64);
            }
            return pos + numBits;
        }
    }
}
