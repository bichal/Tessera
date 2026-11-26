package net.bichal.mods.tessera.compress.software;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Pure-Java BC7 block decoder, every mode. For checks on blocks before they are uploaded (the GUI
 * atlas's transparent texels, see {@link ExactTransparency}), where the native decoder may not be
 * loaded. Matches the native decoder bit for bit ({@code Bc7DecoderTest}).
 */
public final class Bc7Decoder {

    /** Per mode: subsets, partition bits, rotation bits, index-selection bits, colour bits, alpha bits,
     *  p-bit per endpoint, p-bit per subset, index bits, second index bits. */
    private static final int[][] MODES = {
            {3, 4, 0, 0, 4, 0, 1, 0, 3, 0},
            {2, 6, 0, 0, 6, 0, 0, 1, 3, 0},
            {3, 6, 0, 0, 5, 0, 0, 0, 2, 0},
            {2, 6, 0, 0, 7, 0, 1, 0, 2, 0},
            {1, 0, 2, 1, 5, 6, 0, 0, 2, 3},
            {1, 0, 2, 0, 7, 8, 0, 0, 2, 2},
            {1, 0, 0, 0, 7, 7, 1, 0, 4, 0},
            {2, 6, 0, 0, 5, 5, 1, 0, 2, 0},
    };

    /** Two-subset partitions: bit i is the subset of texel i. */
    private static final int[] PARTITIONS2 = {
            0xCCCC, 0x8888, 0xEEEE, 0xECC8, 0xC880, 0xFEEC, 0xFEC8, 0xEC80,
            0xC800, 0xFFEC, 0xFE80, 0xE800, 0xFFE8, 0xFF00, 0xFFF0, 0xF000,
            0xF710, 0x008E, 0x7100, 0x08CE, 0x008C, 0x7310, 0x3100, 0x8CCE,
            0x088C, 0x3110, 0x6666, 0x366C, 0x17E8, 0x0FF0, 0x718E, 0x399C,
            0xAAAA, 0xF0F0, 0x5A5A, 0x33CC, 0x3C3C, 0x55AA, 0x9696, 0xA55A,
            0x73CE, 0x13C8, 0x324C, 0x3BDC, 0x6996, 0xC33C, 0x9966, 0x0660,
            0x0272, 0x04E4, 0x4E40, 0x2720, 0xC936, 0x936C, 0x39C6, 0x639C,
            0x9336, 0x9CC6, 0x817E, 0xE718, 0xCCF0, 0x0FCC, 0x7744, 0xEE22,
    };

    /** Three-subset partitions: bits 2i..2i+1 are the subset of texel i. */
    private static final int[] PARTITIONS3 = {
            0xAA685050, 0x6A5A5040, 0x5A5A4200, 0x5450A0A8, 0xA5A50000, 0xA0A05050, 0x5555A0A0, 0x5A5A5050,
            0xAA550000, 0xAA555500, 0xAAAA5500, 0x90909090, 0x94949494, 0xA4A4A4A4, 0xA9A59450, 0x2A0A4250,
            0xA5945040, 0x0A425054, 0xA5A5A500, 0x55A0A0A0, 0xA8A85454, 0x6A6A4040, 0xA4A45000, 0x1A1A0500,
            0x0050A4A4, 0xAAA59090, 0x14696914, 0x69691400, 0xA08585A0, 0xAA821414, 0x50A4A450, 0x6A5A0200,
            0xA9A58000, 0x5090A0A8, 0xA8A09050, 0x24242424, 0x00AA5500, 0x24924924, 0x24499224, 0x50A50A50,
            0x500AA550, 0xAAAA4444, 0x66660000, 0xA5A0A5A0, 0x50A050A0, 0x69286928, 0x44AAAA44, 0x66666600,
            0xAA444444, 0x54A854A8, 0x95809580, 0x96969600, 0xA85454A8, 0x80959580, 0xAA141414, 0x96960000,
            0xAAAA1414, 0xA05050A0, 0xA0A5A5A0, 0x96000000, 0x40804080, 0xA9A8A9A8, 0xAAAAAA44, 0x2A4A5254,
    };

    /** Anchor texel of the second subset, two-subset partitions. */
    private static final byte[] ANCHOR2 = {
            15, 15, 15, 15, 15, 15, 15, 15, 15, 15, 15, 15, 15, 15, 15, 15,
            15, 2, 8, 2, 2, 8, 8, 15, 2, 8, 2, 2, 8, 8, 2, 2,
            15, 15, 6, 8, 2, 8, 15, 15, 2, 8, 2, 2, 2, 15, 15, 6,
            6, 2, 6, 8, 15, 15, 2, 2, 15, 15, 15, 15, 15, 2, 2, 15,
    };

    /** Anchor texels of the second and third subsets, three-subset partitions. */
    private static final byte[] ANCHOR3_1 = {
            3, 3, 15, 15, 8, 3, 15, 15, 8, 8, 6, 6, 6, 5, 3, 3,
            3, 3, 8, 15, 3, 3, 6, 10, 5, 8, 8, 6, 8, 5, 15, 15,
            8, 15, 3, 5, 6, 10, 8, 15, 15, 3, 15, 5, 15, 15, 15, 15,
            3, 15, 5, 5, 5, 8, 5, 10, 5, 10, 8, 13, 15, 12, 3, 3,
    };
    private static final byte[] ANCHOR3_2 = {
            15, 8, 8, 3, 15, 15, 3, 8, 15, 15, 15, 15, 15, 15, 15, 8,
            15, 8, 15, 3, 15, 8, 15, 8, 3, 15, 6, 10, 15, 15, 10, 8,
            15, 3, 15, 10, 10, 8, 9, 10, 6, 15, 8, 15, 3, 6, 6, 8,
            15, 3, 15, 15, 15, 15, 15, 15, 15, 15, 15, 15, 3, 15, 15, 8,
    };

    /** Interpolation weights by index bit count. */
    private static final int[][] WEIGHTS = {null, null, SoftwareBc7Encoder.W2, SoftwareBc7Encoder.W3, SoftwareBc7Encoder.W4};

    private Bc7Decoder() {
    }

    /** Decodes BC7 blocks into a {@code width x height} RGBA8 image (partial edge blocks cropped). */
    public static ByteBuffer decode(ByteBuffer blocks, int width, int height) {
        ByteBuffer src = blocks.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        int base = src.position();
        int blocksX = BlockMath.blocksAcross(width);
        int blocksY = BlockMath.blocksAcross(height);
        ByteBuffer out = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.LITTLE_ENDIAN);
        int[] texels = new int[16];
        for (int by = 0; by < blocksY; by++) {
            for (int bx = 0; bx < blocksX; bx++) {
                int at = base + (by * blocksX + bx) * 16;
                decodeBlock(src.getLong(at), src.getLong(at + 8), texels);
                for (int i = 0; i < 16; i++) {
                    int x = bx * 4 + (i & 3), y = by * 4 + (i >> 2);
                    if (x < width && y < height) {
                        out.putInt((y * width + x) * 4, texels[i]);
                    }
                }
            }
        }
        return out;
    }

    /**
     * Decodes one block (its 128 bits, little-endian halves) into 16 texels in row order, each
     * packed as R | G << 8 | B << 16 | A << 24. A reserved mode decodes to all zeros.
     */
    public static void decodeBlock(long lo, long hi, int[] out) {
        int mode = Long.numberOfTrailingZeros(lo);
        if (mode > 7) {
            java.util.Arrays.fill(out, 0);
            return;
        }
        int[] m = MODES[mode];
        int subsets = m[0], colourBits = m[4], alphaBits = m[5], indexBits = m[8], indexBits2 = m[9];
        int pos = mode + 1;
        int partition = (int) bits(lo, hi, pos, m[1]);
        pos += m[1];
        int rotation = (int) bits(lo, hi, pos, m[2]);
        pos += m[2];
        int selection = (int) bits(lo, hi, pos, m[3]);
        pos += m[3];

        int endpoints = subsets * 2;
        int[][] e = new int[endpoints][4];
        for (int c = 0; c < 3; c++) {
            for (int i = 0; i < endpoints; i++) {
                e[i][c] = (int) bits(lo, hi, pos, colourBits);
                pos += colourBits;
            }
        }
        for (int i = 0; i < endpoints; i++) {
            if (alphaBits > 0) {
                e[i][3] = (int) bits(lo, hi, pos, alphaBits);
                pos += alphaBits;
            }
        }
        int cb = colourBits, ab = alphaBits;
        if (m[6] != 0 || m[7] != 0) {
            int[] p = new int[endpoints];
            if (m[6] != 0) {
                for (int i = 0; i < endpoints; i++) {
                    p[i] = (int) bits(lo, hi, pos++, 1);
                }
            } else {
                for (int s = 0; s < subsets; s++) {
                    p[2 * s] = p[2 * s + 1] = (int) bits(lo, hi, pos++, 1);
                }
            }
            for (int i = 0; i < endpoints; i++) {
                for (int c = 0; c < 3; c++) {
                    e[i][c] = e[i][c] << 1 | p[i];
                }
                if (alphaBits > 0) {
                    e[i][3] = e[i][3] << 1 | p[i];
                }
            }
            cb++;
            if (alphaBits > 0) {
                ab++;
            }
        }
        for (int i = 0; i < endpoints; i++) {
            for (int c = 0; c < 3; c++) {
                e[i][c] = expand(e[i][c], cb);
            }
            e[i][3] = alphaBits > 0 ? expand(e[i][3], ab) : 255;
        }

        int[] subsetOf = new int[16];
        int anchor1 = 0, anchor2 = 0;
        if (subsets == 2) {
            for (int i = 0; i < 16; i++) {
                subsetOf[i] = PARTITIONS2[partition] >>> i & 1;
            }
            anchor1 = ANCHOR2[partition];
        } else if (subsets == 3) {
            for (int i = 0; i < 16; i++) {
                subsetOf[i] = PARTITIONS3[partition] >>> (2 * i) & 3;
            }
            anchor1 = ANCHOR3_1[partition];
            anchor2 = ANCHOR3_2[partition];
        }
        int[] index = new int[16];
        for (int i = 0; i < 16; i++) {
            boolean anchor = i == 0 || (subsets > 1 && i == anchor1) || (subsets == 3 && i == anchor2);
            int n = anchor ? indexBits - 1 : indexBits;
            index[i] = (int) bits(lo, hi, pos, n);
            pos += n;
        }
        int[] index2 = null;
        if (indexBits2 > 0) {
            index2 = new int[16];
            for (int i = 0; i < 16; i++) {
                int n = i == 0 ? indexBits2 - 1 : indexBits2;
                index2[i] = (int) bits(lo, hi, pos, n);
                pos += n;
            }
        }

        for (int i = 0; i < 16; i++) {
            int[] e0 = e[2 * subsetOf[i]], e1 = e[2 * subsetOf[i] + 1];
            int colourIndex = index[i], alphaIndex = index[i];
            int[] colourWeights = WEIGHTS[indexBits], alphaWeights = WEIGHTS[indexBits];
            if (index2 != null) {
                if (selection == 0) {
                    alphaIndex = index2[i];
                    alphaWeights = WEIGHTS[indexBits2];
                } else {
                    colourIndex = index2[i];
                    colourWeights = WEIGHTS[indexBits2];
                }
            }
            int cw = colourWeights[colourIndex], aw = alphaWeights[alphaIndex];
            int r = interpolate(e0[0], e1[0], cw);
            int g = interpolate(e0[1], e1[1], cw);
            int b = interpolate(e0[2], e1[2], cw);
            int a = interpolate(e0[3], e1[3], aw);
            switch (rotation) {
                case 1 -> { int t = a; a = r; r = t; }
                case 2 -> { int t = a; a = g; g = t; }
                case 3 -> { int t = a; a = b; b = t; }
                default -> { }
            }
            out[i] = r | g << 8 | b << 16 | a << 24;
        }
    }

    static int interpolate(int e0, int e1, int weight) {
        return ((64 - weight) * e0 + weight * e1 + 32) >> 6;
    }

    private static int expand(int v, int bits) {
        return bits == 8 ? v : (v << (8 - bits)) | (v >>> (2 * bits - 8));
    }

    /** {@code count} bits at bit {@code pos} of the 128-bit block. */
    private static long bits(long lo, long hi, int pos, int count) {
        if (count == 0) {
            return 0;
        }
        long v;
        if (pos >= 64) {
            v = hi >>> (pos - 64);
        } else {
            v = lo >>> pos;
            if (pos + count > 64) {
                v |= hi << (64 - pos);
            }
        }
        return v & ((1L << count) - 1);
    }
}
