package net.bichal.mods.tessera.atlas;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class SpriteFamilyAnalyzerTest {

    /** The box filter as first written: each channel on its own, edge-clamped, (sum + 2) / 4. */
    private static byte[] reference(byte[] pixels, int w, int h) {
        int outW = Math.max(w / 2, 1);
        int outH = Math.max(h / 2, 1);
        byte[] out = new byte[outW * outH * 4];
        for (int y = 0; y < outH; y++) {
            int y0 = Math.min(y * 2, h - 1);
            int y1 = Math.min(y * 2 + 1, h - 1);
            for (int x = 0; x < outW; x++) {
                int x0 = Math.min(x * 2, w - 1);
                int x1 = Math.min(x * 2 + 1, w - 1);
                for (int c = 0; c < 4; c++) {
                    int sum = (pixels[(y0 * w + x0) * 4 + c] & 0xFF) + (pixels[(y0 * w + x1) * 4 + c] & 0xFF)
                            + (pixels[(y1 * w + x0) * 4 + c] & 0xFF) + (pixels[(y1 * w + x1) * 4 + c] & 0xFF);
                    out[(y * outW + x) * 4 + c] = (byte) ((sum + 2) / 4);
                }
            }
        }
        return out;
    }

    @Test
    void downsampleMatchesTheChannelByChannelFilter() {
        Random random = new Random(7);
        int[][] sizes = {{1, 1}, {3, 1}, {1, 5}, {7, 9}, {16, 16}, {33, 17}, {1024, 600}};
        for (int[] size : sizes) {
            byte[] pixels = new byte[size[0] * size[1] * 4];
            random.nextBytes(pixels);
            var result = SpriteFamilyAnalyzer.downsampleBoxFilter(pixels, size[0], size[1]);
            assertArrayEquals(reference(pixels, size[0], size[1]), result.pixels(), size[0] + "x" + size[1]);
        }
    }

    @Test
    void averageOfExtremesStaysInItsChannel() {
        assertEquals(0xFFFFFFFF, SpriteFamilyAnalyzer.average4(-1, -1, -1, -1));
        assertEquals(0, SpriteFamilyAnalyzer.average4(0, 0, 0, 0));
        // 255 + 255 + 1 + 0 = 511: (511 + 2) / 4 = 128 in every channel.
        assertEquals(0x80808080, SpriteFamilyAnalyzer.average4(-1, -1, 0x01010101, 0));
    }

    @Test
    void fingerprintInsideALargerImageMatchesThePackedSprite() {
        Random random = new Random(11);
        int spriteW = 18;
        int spriteH = 20;
        int atlasW = 64;
        byte[] atlas = new byte[atlasW * 40 * 4];
        random.nextBytes(atlas);
        int originX = 5;
        int originY = 7;
        byte[] packed = new byte[spriteW * spriteH * 4];
        for (int y = 0; y < spriteH; y++) {
            System.arraycopy(atlas, ((originY + y) * atlasW + originX) * 4, packed, y * spriteW * 4, spriteW * 4);
        }
        assertEquals(SpriteFamilyAnalyzer.fingerprint(packed, 0, spriteW, spriteH),
                SpriteFamilyAnalyzer.fingerprint(atlas, (originY * atlasW + originX) * 4, spriteW, spriteH, atlasW));
    }
}
