package net.bichal.mods.tessera.atlas;

import net.bichal.mods.tessera.compat.TesseraCompat;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;

import java.util.Collection;

/**
 * Which atlases Tessera compresses, and how their animated sprites are handled.
 *
 * <ul>
 *   <li>{@link Kind#STATIC}: no animated sprite. Compressed with the smallest format the content
 *       allows (see {@link AtlasFormats}).</li>
 *   <li>{@link Kind#ANIMATED}: at least one animated sprite (the block atlas: water, lava, fire).
 *       Compressed too, by default: every animation frame is encoded once and written into the
 *       atlas as compressed blocks ({@code CompressedAnimationUploader}). Left RGBA8888, exactly as
 *       vanilla uploaded it, when Kerria is installed (it writes those frames as RGBA8888), when the
 *       {@code compressAnimatedAtlases} setting is off, or when the format is not a 4x4-block one
 *       (frames are written per 4x4 block).</li>
 * </ul>
 *
 * <p>The GUI atlas is left alone unless the experimental {@code compressGuiAtlas} setting is on.
 * It is small in vanilla (about a megabyte), and its sprites are drawn with blend modes that
 * expose any texel the encoder does not reproduce exactly: the crosshair inverts the colour behind
 * it using the sprite's RGB, so a "transparent" texel decoded with a trace of colour shows up as a
 * grey bar beside it. With the setting on, its transparent texels are written to decode as exactly
 * zero ({@code ExactTransparency}); ASTC cannot promise that, so there it stays RGBA8888.
 */
public final class AtlasPolicy {

    /** The two halves of the split. */
    public enum Kind {
        /** No animated sprite: compressed. */
        STATIC,
        /** Some animated sprite: compressed with its frames precoded, or left RGBA8888 (see {@link #mayCompressAnimated}). */
        ANIMATED
    }

    private AtlasPolicy() {
    }

    /** @param atlasName the atlas texture location, e.g. {@code minecraft:textures/atlas/gui.png} */
    public static boolean isCompressible(String atlasName) {
        return isCompressible(atlasName, net.bichal.mods.tessera.config.Config.get(
                net.bichal.mods.tessera.config.Config.COMPRESS_GUI_ATLAS));
    }

    /** @param compressGui the {@code compressGuiAtlas} setting */
    public static boolean isCompressible(String atlasName, boolean compressGui) {
        return compressGui || !isGuiAtlas(atlasName);
    }

    /** Whether the atlas is the GUI's, whose transparent texels must decode as exactly zero. */
    public static boolean isGuiAtlas(String atlasName) {
        return atlasName.endsWith("textures/atlas/gui.png");
    }

    /** Which half of the split an atlas with these sprites belongs to. */
    public static Kind classify(Collection<TextureAtlasSprite> sprites) {
        return holdsAnimations(sprites) ? Kind.ANIMATED : Kind.STATIC;
    }

    /**
     * Whether an atlas with animated sprites may be compressed in {@code target}: the setting is on,
     * Kerria (which writes frames as RGBA8888) is not installed, and the format has 4x4 blocks
     * written per frame region (BC1, BC7). ASTC and one-bit alpha are left out: the per-frame
     * encoders are BC1 and BC7, and mipmapped atlases never take one-bit alpha anyway.
     */
    public static boolean mayCompressAnimated(net.bichal.mods.tessera.compress.CompressionPipeline.Target target) {
        boolean wanted = net.bichal.mods.tessera.config.Config.get(net.bichal.mods.tessera.config.Config.COMPRESS_ANIMATED_ATLASES)
                || Boolean.getBoolean("tessera.compressAnimated");
        return wanted && !net.bichal.mods.tessera.compat.ModCompat.isKerriaLoaded()
                && (target == net.bichal.mods.tessera.compress.CompressionPipeline.Target.BC1
                || target == net.bichal.mods.tessera.compress.CompressionPipeline.Target.BC7);
    }

    /** Whether any sprite of the atlas is animated. */
    public static boolean holdsAnimations(Collection<TextureAtlasSprite> sprites) {
        for (var sprite : sprites) {
            if (TesseraCompat.isAnimated(sprite.contents())) {
                return true;
            }
        }
        return false;
    }

    /** Whether every frame of every animated sprite is fully opaque (frame 0 alone decides nothing for BC1). */
    public static boolean animationFramesOpaque(Collection<TextureAtlasSprite> sprites) {
        for (TextureAtlasSprite sprite : sprites) {
            var contents = sprite.contents();
            if (!TesseraCompat.isAnimated(contents)) {
                continue;
            }
            for (int frame : TesseraCompat.uniqueFrames(contents)) {
                for (int y = 0; y < contents.height(); y++) {
                    for (int x = 0; x < contents.width(); x++) {
                        if ((TesseraCompat.spriteAbgr(sprite, frame, x, y) >>> 24) != 0xFF) {
                            return false;
                        }
                    }
                }
            }
        }
        return true;
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
