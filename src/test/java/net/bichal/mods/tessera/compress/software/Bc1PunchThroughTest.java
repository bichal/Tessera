package net.bichal.mods.tessera.compress.software;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Bc1PunchThroughTest {

    private static final int SIZE = 37; // not a multiple of 4: partial edge blocks too

    /** Sprite-like content: a shaded disc on a transparent background, with one-texel holes. */
    private static ByteBuffer cutoutImage() {
        ByteBuffer image = ByteBuffer.allocateDirect(SIZE * SIZE * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                int dx = x - SIZE / 2, dy = y - SIZE / 2;
                boolean inside = dx * dx + dy * dy < 15 * 15 && (x * 7 + y * 3) % 11 != 0;
                int r = 40 + x * 5, g = 200 - y * 4, b = 90 + (x ^ y) % 40;
                image.putInt((y * SIZE + x) * 4, inside ? (0xFF << 24 | b << 16 | g << 8 | r) : 0x00FFFFFF);
            }
        }
        return image;
    }

    @Test
    void alphaComesBackExactlyAndColourStaysClose() {
        ByteBuffer image = cutoutImage();
        TransparentTexelBleed.apply(image, SIZE, SIZE);
        ByteBuffer opaque = SoftwareBc1Encoder.encode(image, SIZE, SIZE);

        Bc1PunchThrough.Result result = Bc1PunchThrough.encode(image, SIZE, SIZE, opaque);
        assertNotNull(result);
        int blocksX = (SIZE + 3) / 4;
        assertEquals((long) blocksX * blocksX * 8, result.blocks().remaining());
        assertTrue(result.visiblePsnr() >= 32.0, "visible PSNR " + result.visiblePsnr());

        ByteBuffer blocks = result.blocks().duplicate().order(ByteOrder.LITTLE_ENDIAN);
        for (int by = 0; by < blocksX; by++) {
            for (int bx = 0; bx < blocksX; bx++) {
                int offset = (by * blocksX + bx) * 8;
                int c0 = blocks.getShort(offset) & 0xFFFF;
                int c1 = blocks.getShort(offset + 2) & 0xFFFF;
                int indices = blocks.getInt(offset + 4);
                for (int i = 0; i < 16; i++) {
                    int x = Math.min(bx * 4 + i % 4, SIZE - 1), y = Math.min(by * 4 + i / 4, SIZE - 1);
                    boolean opaqueTexel = (image.getInt((y * SIZE + x) * 4) >>> 24) == 0xFF;
                    boolean decodedTransparent = c0 <= c1 && ((indices >>> (i * 2)) & 3) == 3;
                    assertEquals(opaqueTexel, !decodedTransparent, "texel " + x + "," + y);
                }
            }
        }
    }

    @Test
    void opaqueBlackInThreeColourModeIsRefitted() {
        // One opaque block whose given encoding is 3-colour mode with index 3 (black): with
        // one-bit alpha that decodes transparent, so it must come back in 4-colour mode.
        ByteBuffer image = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < 16; i++) {
            image.putInt(i * 4, i % 2 == 0 ? 0xFF000000 : 0xFF2080C0);
        }
        ByteBuffer given = ByteBuffer.allocateDirect(8).order(ByteOrder.LITTLE_ENDIAN);
        given.putShort((short) 0x0410).putShort((short) 0x2418).putInt(0x77777777).flip(); // c0 <= c1, indices 3 and 1

        Bc1PunchThrough.Result result = Bc1PunchThrough.encode(image, 4, 4, given);
        assertNotNull(result);
        int c0 = result.blocks().getShort(0) & 0xFFFF;
        int c1 = result.blocks().getShort(2) & 0xFFFF;
        assertTrue(c0 > c1, "4-colour mode");
        assertTrue(result.visiblePsnr() > 30.0, "visible PSNR " + result.visiblePsnr());
    }

    @Test
    void fullyTransparentBlockDecodesTransparent() {
        ByteBuffer image = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.LITTLE_ENDIAN);
        ByteBuffer given = ByteBuffer.allocateDirect(8).order(ByteOrder.LITTLE_ENDIAN);
        given.putShort((short) 0xFFFF).putShort((short) 0).putInt(0).flip();

        Bc1PunchThrough.Result result = Bc1PunchThrough.encode(image, 4, 4, given);
        assertNotNull(result);
        assertEquals(0, result.blocks().getShort(0));
        assertEquals(0, result.blocks().getShort(2));
        assertEquals(-1, result.blocks().getInt(4));
        assertEquals(Double.POSITIVE_INFINITY, result.visiblePsnr());
    }

    @Test
    void opposedColoursKeepBothEndpoints() {
        // Red against green with transparent texels: the colours' axis is orthogonal to grey, which
        // must not collapse both endpoints onto their mean.
        ByteBuffer image = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < 16; i++) {
            image.putInt(i * 4, i < 4 ? 0 : (i % 2 == 0 ? 0xFF0000F8 : 0xFF00F800));
        }
        TransparentTexelBleed.apply(image, 4, 4);
        Bc1PunchThrough.Result result = Bc1PunchThrough.encode(image, 4, 4, SoftwareBc1Encoder.encode(image, 4, 4));
        assertNotNull(result);
        assertTrue(result.visiblePsnr() > 45.0, "visible PSNR " + result.visiblePsnr());
    }
}
