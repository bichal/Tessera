package com.panzer.mods.tessera.atlas;

/**
 * Which atlases Tessera leaves as vanilla RGBA.
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
}
