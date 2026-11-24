package net.bichal.mods.tessera.atlas;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AtlasPolicyTest {

    @Test
    void guiAtlasStaysUncompressed() {
        assertFalse(AtlasPolicy.isCompressible("minecraft:textures/atlas/gui.png"));
    }

    @Test
    void worldAtlasesAreCompressed() {
        assertTrue(AtlasPolicy.isCompressible("minecraft:textures/atlas/blocks.png"));
        assertTrue(AtlasPolicy.isCompressible("minecraft:textures/atlas/particles.png"));
        assertTrue(AtlasPolicy.isCompressible("minecraft:textures/atlas/chests.png"));
    }

    @Test
    void rgbaChainBytesCountsEveryLevel() {
        assertEquals(16L * 16 * 4, AtlasPolicy.rgbaChainBytes(16, 16, 0));
        assertEquals((16L * 16 + 8 * 8 + 4 * 4) * 4, AtlasPolicy.rgbaChainBytes(16, 16, 2));
        // Levels stop at 1x1 even when more are asked for.
        assertEquals((2L * 1 + 1) * 4, AtlasPolicy.rgbaChainBytes(2, 1, 5));
    }
}
