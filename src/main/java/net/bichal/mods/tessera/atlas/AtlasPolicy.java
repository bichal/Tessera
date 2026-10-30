package net.bichal.mods.tessera.atlas;


/**
 * Which atlases Tessera leaves as vanilla RGBA, and which block format the rest get.
 *
 * <p>Format by content: an atlas whose sprites are all opaque is stored as BC1
 * (4 bits per texel), one with any translucent texel as BC7 (8 bits per texel,
 * keeps alpha). Atlases with animated sprites stay RGBA8888, so animation frames
 * are written as vanilla does (and as Kerria does).
 *
 * <p>The GUI atlas is tiny (about a megabyte), so compressing it saves almost
 * nothing, but its sprites are drawn with blend modes that expose any texel BC7
 * does not reproduce exactly: the crosshair inverts the colour behind it using
 * the sprite's RGB, so a "transparent" texel decoded with a trace of colour
 * showed up as a grey bar beside the crosshair.
 */
public final class AtlasPolicy {

    private AtlasPolicy() {
    }

    /** @param atlasName the atlas texture location, e.g. {@code minecraft:textures/atlas/gui.png} */
    public static boolean isCompressible(String atlasName) {
        return !atlasName.endsWith("textures/atlas/gui.png");
    }

    /**
     * Whether any sprite of the atlas is animated. Such an atlas is never compressed: vanilla writes
     * animation frames into it as RGBA8888 (Kerria does the same, faster), which a block-compressed
     * texture cannot take, and a few animated blocks are not worth giving up the whole atlas's frames.
     */
    public static boolean holdsAnimations(java.util.Collection<net.minecraft.client.renderer.texture.TextureAtlasSprite> sprites) {
        for (var sprite : sprites) {
            if (net.bichal.mods.tessera.compat.TesseraCompat.isAnimated(sprite.contents())) {
                return true;
            }
        }
        return false;
    }
}
