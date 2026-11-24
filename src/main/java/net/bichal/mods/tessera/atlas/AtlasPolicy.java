package net.bichal.mods.tessera.atlas;

import net.bichal.mods.tessera.compat.TesseraCompat;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;

import java.util.Collection;

/**
 * Which atlases Tessera compresses, split in two by what they hold.
 *
 * <ul>
 *   <li>{@link Kind#STATIC}: no animated sprite. Compressed with the smallest format the content
 *       allows (see {@link AtlasFormats}).</li>
 *   <li>{@link Kind#ANIMATED}: at least one animated sprite. Stays RGBA8888 exactly as vanilla
 *       uploaded it, so animation frames are written the way vanilla (or Kerria) writes them: a
 *       block-compressed texture cannot take RGBA frame writes, nor be a render target (1.21.11+).</li>
 * </ul>
 *
 * <p>The GUI atlas is tiny (about a megabyte), so compressing it saves almost
 * nothing, but its sprites are drawn with blend modes that expose any texel BC7
 * does not reproduce exactly: the crosshair inverts the colour behind it using
 * the sprite's RGB, so a "transparent" texel decoded with a trace of colour
 * showed up as a grey bar beside the crosshair.
 */
public final class AtlasPolicy {

    /** The two halves of the split. */
    public enum Kind {
        /** No animated sprite: compressed. */
        STATIC,
        /** Some animated sprite: left RGBA8888. */
        ANIMATED
    }

    private AtlasPolicy() {
    }

    /** @param atlasName the atlas texture location, e.g. {@code minecraft:textures/atlas/gui.png} */
    public static boolean isCompressible(String atlasName) {
        return !atlasName.endsWith("textures/atlas/gui.png");
    }

    /** Which half of the split an atlas with these sprites belongs to. */
    public static Kind classify(Collection<TextureAtlasSprite> sprites) {
        return holdsAnimations(sprites) ? Kind.ANIMATED : Kind.STATIC;
    }

    /**
     * Whether any sprite of the atlas is animated. Such an atlas is never compressed: vanilla writes
     * animation frames into it as RGBA8888 (Kerria does the same, faster), which a block-compressed
     * texture cannot take.
     */
    public static boolean holdsAnimations(Collection<TextureAtlasSprite> sprites) {
        for (var sprite : sprites) {
            if (TesseraCompat.isAnimated(sprite.contents())) {
                return true;
            }
        }
        return false;
    }

    /** Bytes an RGBA8888 atlas of this size takes with levels 0 to {@code mipLevel}. */
    public static long rgbaChainBytes(int width, int height, int mipLevel) {
        long total = 0;
        for (int level = 0; level <= mipLevel; level++) {
            int levelWidth = Math.max(1, width >> level);
            int levelHeight = Math.max(1, height >> level);
            total += (long) levelWidth * levelHeight * 4;
            if (levelWidth == 1 && levelHeight == 1) {
                break;
            }
        }
        return total;
    }
}
