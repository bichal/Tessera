package net.bichal.mods.tessera.atlas;

import org.junit.jupiter.api.Test;

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
}
