package net.bichal.mods.tessera.atlas;

import net.bichal.mods.tessera.compress.CompressionPipeline.Target;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class AtlasFormatsTest {

    private static final Set<Target> DESKTOP = Set.of(Target.BC1, Target.BC1_ALPHA, Target.BC7);
    private static final Set<Target> ARM = Set.of(Target.ASTC_4X4, Target.ASTC_6X6);

    @Test
    void desktopFormats() {
        assertEquals(Target.BC1, AtlasFormats.choose(AtlasFormats.Alpha.OPAQUE, 4, DESKTOP::contains));
        assertEquals(Target.BC1_ALPHA, AtlasFormats.choose(AtlasFormats.Alpha.BINARY, 0, DESKTOP::contains));
        assertEquals(Target.BC7, AtlasFormats.choose(AtlasFormats.Alpha.BLENDED, 0, DESKTOP::contains));
    }

    @Test
    void oneBitAlphaOnlyWithoutMipLevels() {
        // Mip levels average sprite edges into partial alpha, which one-bit alpha cannot hold.
        assertEquals(Target.BC7, AtlasFormats.choose(AtlasFormats.Alpha.BINARY, 4, DESKTOP::contains));
    }

    @Test
    void armFormats() {
        assertEquals(Target.ASTC_6X6, AtlasFormats.choose(AtlasFormats.Alpha.OPAQUE, 4, ARM::contains));
        assertEquals(Target.ASTC_4X4, AtlasFormats.choose(AtlasFormats.Alpha.BINARY, 0, ARM::contains));
        assertEquals(Target.ASTC_4X4, AtlasFormats.choose(AtlasFormats.Alpha.BLENDED, 0, ARM::contains));
    }

    @Test
    void nothingUsable() {
        assertNull(AtlasFormats.choose(AtlasFormats.Alpha.OPAQUE, 0, target -> false));
    }

    @Test
    void alphaFolding() {
        AtlasFormats.Alpha alpha = AtlasFormats.Alpha.OPAQUE.with(255);
        assertEquals(AtlasFormats.Alpha.OPAQUE, alpha);
        alpha = alpha.with(0);
        assertEquals(AtlasFormats.Alpha.BINARY, alpha);
        assertEquals(AtlasFormats.Alpha.BINARY, alpha.with(255));
        assertEquals(AtlasFormats.Alpha.BLENDED, alpha.with(128));
        assertEquals(AtlasFormats.Alpha.BLENDED, AtlasFormats.Alpha.BLENDED.with(0));
    }
}
