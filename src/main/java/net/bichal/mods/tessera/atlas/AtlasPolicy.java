package net.bichal.mods.tessera.atlas;

import net.bichal.mods.tessera.compat.ModCompat;
import net.bichal.mods.tessera.config.Config;

/**
 * Which atlases Tessera leaves as vanilla RGBA, and which block format the rest get.
 *
 * <p>Format by content: an atlas whose sprites are all opaque is stored as BC1
 * (4 bits per texel), one with any translucent texel as BC7 (8 bits per texel,
 * keeps alpha). Atlases with animated sprites stay RGBA8888 so animation frames
 * are written as vanilla does (and as Kerria expects).
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
     * Whether an atlas that holds animated sprites may be block-compressed. Off by
     * default (animated textures stay RGBA8888); never while Kerria is installed,
     * which owns the animation upload path.
     */
    public static boolean mayCompressAnimated() {
        // -Dtessera.compressAnimated=true: the client self-test checks the BC animation path.
        boolean wanted = Boolean.getBoolean("tessera.compressAnimated") || Config.get(Config.COMPRESS_ANIMATED_ATLASES);
        return wanted && !ModCompat.isKerriaLoaded();
    }
}
