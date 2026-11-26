package net.bichal.mods.tessera.compress.software;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static net.bichal.mods.tessera.compress.software.SoftwareBc7Encoder.W2;
import static net.bichal.mods.tessera.compress.software.SoftwareBc7Encoder.W3;
import static net.bichal.mods.tessera.compress.software.SoftwareBc7Encoder.W4;
import static net.bichal.mods.tessera.compress.software.SoftwareBc7Encoder.flip;

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
                SoftwareBc7Encoder.quantize(e, tryStored, rec, count, bits, mode6);
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

        private void packMode6(long[] out) {
            SoftwareBc7Encoder.packMode6(out, new int[5], stored.clone(), idx.clone());
        }

        /** Modes 4 and 5: puts each anchor index's top bit at 0, then packs. */
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
            SoftwareBc7Encoder.packMode45(out, mode, selection, c0, c1, a0, a1, cIdx, aIdx,
                    colourBits, alphaBits, colourIndexBits, alphaIndexBits);
        }
    }
}
