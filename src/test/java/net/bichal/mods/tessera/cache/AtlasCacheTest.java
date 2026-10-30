package net.bichal.mods.tessera.cache;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AtlasCacheTest {

    @TempDir
    Path dir;

    @Test
    void emptyAndRepeatedBlocksShrinkOnDiskAndReadBackIdentical() throws IOException {
        // An atlas is mostly empty blocks and a few distinct ones.
        ByteBuffer blocks = ByteBuffer.allocateDirect(1 << 20).order(ByteOrder.LITTLE_ENDIAN);
        Random random = new Random(7);
        while (blocks.hasRemaining()) {
            boolean empty = random.nextInt(10) < 8;
            for (int i = 0; i < 16 && blocks.hasRemaining(); i++) {
                blocks.put(empty ? 0 : (byte) random.nextInt());
            }
        }
        blocks.flip();

        AtlasCache cache = new AtlasCache(dir);
        cache.write("a", AtlasCache.CompressedFormat.BC7, 512, 512, 3, blocks);
        long onDisk = Files.size(dir.resolve("a.bc7"));
        System.out.printf("[consumption] cache entry: %d KiB of blocks stored in %d KiB (%.0f%%)%n",
                blocks.remaining() / 1024, onDisk / 1024, 100.0 * onDisk / blocks.remaining());
        assertTrue(onDisk < blocks.remaining() / 2, "stored " + onDisk);

        AtlasCache.CachedAtlas hit = cache.read("a", AtlasCache.CompressedFormat.BC7).orElseThrow();
        assertEquals(512, hit.width());
        assertEquals(3, hit.qualityPreset());
        assertEquals(blocks.duplicate(), hit.compressedBlocks());
    }

    @Test
    void incompressibleBlocksAreStoredAsTheyAre() throws IOException {
        byte[] noise = new byte[64 * 1024];
        new Random(1).nextBytes(noise);
        ByteBuffer blocks = ByteBuffer.allocateDirect(noise.length).order(ByteOrder.LITTLE_ENDIAN);
        blocks.put(noise).flip();

        AtlasCache cache = new AtlasCache(dir);
        cache.write("b", AtlasCache.CompressedFormat.BC1, 256, 256, 3, blocks);
        assertTrue(Files.size(dir.resolve("b.bc1")) >= noise.length);
        assertEquals(blocks.duplicate(), cache.read("b", AtlasCache.CompressedFormat.BC1).orElseThrow().compressedBlocks());
    }

    @Test
    void entriesOfTheOldLayoutAreMisses() throws IOException {
        Files.write(dir.resolve("c.bc7"), new byte[]{'T', 'S', 'R', '2', 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 9});
        assertTrue(new AtlasCache(dir).read("c", AtlasCache.CompressedFormat.BC7).isEmpty());
    }

    private static ByteBuffer filled(int length, int seed) {
        ByteBuffer blocks = ByteBuffer.allocateDirect(length).order(ByteOrder.LITTLE_ENDIAN);
        Random random = new Random(seed);
        while (blocks.hasRemaining()) {
            boolean empty = random.nextInt(10) < 7;
            for (int i = 0; i < 16 && blocks.hasRemaining(); i++) {
                blocks.put(empty ? 0 : (byte) random.nextInt());
            }
        }
        return blocks.flip();
    }

    @Test
    void aWholeChainReadsBackLevelForLevel() throws IOException {
        var levels = java.util.List.of(
                new AtlasCache.ChainLevel(512, 512, filled(512 * 512, 1)),
                new AtlasCache.ChainLevel(256, 256, filled(256 * 256, 2)),
                new AtlasCache.ChainLevel(128, 128, filled(128 * 128, 3)));
        AtlasCache cache = new AtlasCache(dir);
        cache.writeChain("d-chain", AtlasCache.CompressedFormat.BC7, 3, levels);

        var back = cache.readChain("d-chain", AtlasCache.CompressedFormat.BC7, 3).orElseThrow();
        assertEquals(levels.size(), back.size());
        for (int i = 0; i < levels.size(); i++) {
            assertEquals(levels.get(i).width(), back.get(i).width());
            assertEquals(levels.get(i).height(), back.get(i).height());
            assertEquals(levels.get(i).blocks().duplicate(), back.get(i).blocks());
        }
    }

    @Test
    void aChainForAnotherFormatOrQualityIsAMiss() throws IOException {
        AtlasCache cache = new AtlasCache(dir);
        cache.writeChain("e-chain", AtlasCache.CompressedFormat.BC7, 3,
                java.util.List.of(new AtlasCache.ChainLevel(64, 64, filled(4096, 4))));
        assertTrue(cache.readChain("e-chain", AtlasCache.CompressedFormat.BC7, 4).isEmpty());
        assertTrue(cache.readChain("e-chain", AtlasCache.CompressedFormat.BC1, 3).isEmpty());
    }

    @Test
    void aTruncatedChainIsAMiss() throws IOException {
        AtlasCache cache = new AtlasCache(dir);
        cache.writeChain("f-chain", AtlasCache.CompressedFormat.BC7, 3,
                java.util.List.of(new AtlasCache.ChainLevel(64, 64, filled(4096, 5))));
        Path file = dir.resolve("f-chain.bc7");
        byte[] bytes = Files.readAllBytes(file);
        Files.write(file, java.util.Arrays.copyOf(bytes, bytes.length - 20));
        assertTrue(cache.readChain("f-chain", AtlasCache.CompressedFormat.BC7, 3).isEmpty());
    }
}
