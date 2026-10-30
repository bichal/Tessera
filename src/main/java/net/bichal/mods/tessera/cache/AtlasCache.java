package net.bichal.mods.tessera.cache;

import net.bichal.mods.tessera.util.TesseraHasher;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Optional;

public final class AtlasCache {

    // TSR3 (TSR2 stored the blocks as they were): the payload may be compressed with CacheCodec.
    private static final byte[] MAGIC = {'T', 'S', 'R', '3'};
    // A whole mip chain in one entry, keyed by the atlas fingerprint instead of per-level pixels.
    private static final byte[] CHAIN_MAGIC = {'T', 'S', 'C', '1'};
    private static final int HEADER_BYTES = MAGIC.length + Integer.BYTES * 7;
    private static final int PAYLOAD_RAW = 0;
    private static final int PAYLOAD_CELERIS = 1;
    private final Path cacheDirectory;

    public AtlasCache(Path cacheDirectory) {
        this.cacheDirectory = cacheDirectory;
    }

    public String hashHex(ByteBuffer rgba8Direct, int length) {
        ByteBuffer slice = rgba8Direct.duplicate();
        slice.limit(slice.position() + length);
        return TesseraHasher.hashContent(slice);
    }

    /**
     * Reads a cached compressed atlas, scoped to a specific {@link CompressedFormat}.
     *
     * <p>The cache key is content-hash-based (see {@link #hashHex}), which only
     * covers pixel data -- it says nothing about which GL format the caller
     * wants that content encoded to. The same RGBA8 pixels can legitimately
     * be requested as BC1 for one atlas bucket and BC7 for another (e.g. a
     * sprite whose opaque/alpha classification differs between mod-added
     * variants sharing identical pixels), so {@code format} is threaded
     * through both the read and write paths and is checked against what's
     * on disk before a hit is returned -- a format mismatch is treated as a
     * cache miss, never as a silently-wrong-format hit.
     */
    public Optional<CachedAtlas> read(String hashHex, CompressedFormat format) throws IOException {
        Path file = fileFor(hashHex, format);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }

        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            if (channel.size() < HEADER_BYTES) {
                return Optional.empty();
            }

            ByteBuffer header = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN);
            while (header.hasRemaining()) {
                if (channel.read(header) < 0) {
                    // Truncated file, shorter than its own declared header
                    // size -- treat as a miss rather than reading a
                    // partially zero-filled header.
                    return Optional.empty();
                }
            }
            header.flip();

            byte[] magic = new byte[MAGIC.length];
            header.get(magic);
            if (!java.util.Arrays.equals(magic, MAGIC)) {
                return Optional.empty();
            }

            int formatOrdinal = header.getInt();
            if (formatOrdinal != format.ordinal()) {
                // Same content hash, different requested format (or a stale
                // cache file from before format-tagging existed) -- treat as a
                // miss rather than returning bytes in the wrong GL format.
                return Optional.empty();
            }

            int width = header.getInt();
            int height = header.getInt();
            int qualityPreset = header.getInt();
            int payloadLength = header.getInt();
            int storedLength = header.getInt();
            int encoding = header.getInt();

            // Corrupt header guard: negative/oversized lengths would otherwise throw
            // IllegalArgumentException from allocateDirect, or allocate garbage sizes.
            if (payloadLength <= 0 || storedLength <= 0 || width <= 0 || height <= 0
                    || (encoding != PAYLOAD_RAW && encoding != PAYLOAD_CELERIS)
                    || (encoding == PAYLOAD_RAW && storedLength != payloadLength)
                    || channel.size() < (long) HEADER_BYTES + storedLength) {
                return Optional.empty();
            }

            ByteBuffer payload = ByteBuffer.allocateDirect(storedLength).order(ByteOrder.LITTLE_ENDIAN);
            while (payload.hasRemaining()) {
                if (channel.read(payload) < 0) {
                    // Truncated mid-read (e.g. concurrent external
                    // modification of the cache file) -- treat as a miss
                    // rather than spinning forever or returning a
                    // partially-filled buffer.
                    return Optional.empty();
                }
            }
            payload.flip();

            if (encoding == PAYLOAD_CELERIS) {
                payload = CacheCodec.decompress(payload, payloadLength);
                if (payload == null) {
                    return Optional.empty(); // does not decode: recompress and overwrite
                }
            }

            touch(file); // refresh LRU position for prune()
            return Optional.of(new CachedAtlas(width, height, qualityPreset, payload));
        }
    }

    // False Positive
    @SuppressWarnings("ResultOfMethodCallIgnored")
    public void write(String hashHex, CompressedFormat format, int width, int height, int qualityPreset, ByteBuffer compressedBlocks) throws IOException {
        Files.createDirectories(cacheDirectory);

        int payloadLength = compressedBlocks.remaining();
        // Block data is mostly runs of identical or empty blocks: store it compressed when that is smaller.
        ByteBuffer packed = CacheCodec.compress(compressedBlocks);
        ByteBuffer stored = packed != null ? packed : compressedBlocks.duplicate();
        int storedLength = stored.remaining();
        ByteBuffer header = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        header.put(MAGIC);
        header.putInt(format.ordinal());
        header.putInt(width);
        header.putInt(height);
        header.putInt(qualityPreset);
        header.putInt(payloadLength);
        header.putInt(storedLength);
        header.putInt(packed != null ? PAYLOAD_CELERIS : PAYLOAD_RAW);
        header.flip();

        // Gathering write: header + payload straight from the (direct) block buffer,
        // instead of first copying the whole payload into a heap staging buffer.
        ByteBuffer[] parts = {header, stored};
        long total = (long) HEADER_BYTES + storedLength;

        Path tempFile = Files.createTempFile(cacheDirectory, hashHex, ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(tempFile,
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                long written = 0;
                while (written < total) {
                    written += channel.write(parts);
                }
            }
            Files.move(tempFile, fileFor(hashHex, format), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.deleteIfExists(tempFile);
            throw e;
        }
    }


    /** One level of a cached chain: its size and its encoded blocks. */
    public record ChainLevel(int width, int height, ByteBuffer blocks) {
    }

    /**
     * Reads a whole mip chain stored under an atlas fingerprint. Any mismatch (format, quality,
     * a damaged or truncated file) is a miss.
     */
    public Optional<java.util.List<ChainLevel>> readChain(String key, CompressedFormat format, int qualityPreset) throws IOException {
        Path file = fileFor(key, format);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        ByteBuffer all = ByteBuffer.wrap(Files.readAllBytes(file)).order(ByteOrder.LITTLE_ENDIAN);
        if (all.remaining() < CHAIN_MAGIC.length + Integer.BYTES * 6) {
            return Optional.empty();
        }
        byte[] magic = new byte[CHAIN_MAGIC.length];
        all.get(magic);
        if (!java.util.Arrays.equals(magic, CHAIN_MAGIC)
                || all.getInt() != format.ordinal() || all.getInt() != qualityPreset) {
            return Optional.empty();
        }
        int levelCount = all.getInt();
        int payloadLength = all.getInt();
        int storedLength = all.getInt();
        int encoding = all.getInt();
        if (levelCount <= 0 || levelCount > 32 || payloadLength <= 0 || storedLength <= 0
                || (encoding != PAYLOAD_RAW && encoding != PAYLOAD_CELERIS)
                || (encoding == PAYLOAD_RAW && storedLength != payloadLength)
                || all.remaining() < levelCount * Integer.BYTES * 3 + storedLength) {
            return Optional.empty();
        }
        int[] widths = new int[levelCount];
        int[] heights = new int[levelCount];
        int[] lengths = new int[levelCount];
        long sum = 0;
        for (int i = 0; i < levelCount; i++) {
            widths[i] = all.getInt();
            heights[i] = all.getInt();
            lengths[i] = all.getInt();
            if (widths[i] <= 0 || heights[i] <= 0 || lengths[i] <= 0) {
                return Optional.empty();
            }
            sum += lengths[i];
        }
        if (sum != payloadLength) {
            return Optional.empty();
        }
        ByteBuffer stored = ByteBuffer.allocateDirect(storedLength).order(ByteOrder.LITTLE_ENDIAN);
        stored.put(all.slice(all.position(), storedLength)).flip();
        ByteBuffer payload = stored;
        if (encoding == PAYLOAD_CELERIS) {
            payload = CacheCodec.decompress(stored, payloadLength);
            if (payload == null) {
                return Optional.empty();
            }
        }
        java.util.List<ChainLevel> levels = new java.util.ArrayList<>(levelCount);
        int offset = 0;
        for (int i = 0; i < levelCount; i++) {
            ByteBuffer blocks = ByteBuffer.allocateDirect(lengths[i]).order(ByteOrder.LITTLE_ENDIAN);
            blocks.put(payload.slice(offset, lengths[i])).flip();
            levels.add(new ChainLevel(widths[i], heights[i], blocks));
            offset += lengths[i];
        }
        touch(file);
        return Optional.of(levels);
    }

    /** Stores a whole mip chain under an atlas fingerprint (all levels in one file). */
    public void writeChain(String key, CompressedFormat format, int qualityPreset, java.util.List<ChainLevel> levels) throws IOException {
        Files.createDirectories(cacheDirectory);
        int payloadLength = 0;
        for (ChainLevel level : levels) {
            payloadLength += level.blocks().remaining();
        }
        ByteBuffer raw = ByteBuffer.allocateDirect(payloadLength).order(ByteOrder.LITTLE_ENDIAN);
        for (ChainLevel level : levels) {
            raw.put(level.blocks().duplicate());
        }
        raw.flip();
        ByteBuffer packed = CacheCodec.compress(raw);
        ByteBuffer stored = packed != null ? packed : raw;
        ByteBuffer header = ByteBuffer.allocate(CHAIN_MAGIC.length + Integer.BYTES * 6 + levels.size() * Integer.BYTES * 3)
                .order(ByteOrder.LITTLE_ENDIAN);
        header.put(CHAIN_MAGIC);
        header.putInt(format.ordinal());
        header.putInt(qualityPreset);
        header.putInt(levels.size());
        header.putInt(payloadLength);
        header.putInt(stored.remaining());
        header.putInt(packed != null ? PAYLOAD_CELERIS : PAYLOAD_RAW);
        for (ChainLevel level : levels) {
            header.putInt(level.width());
            header.putInt(level.height());
            header.putInt(level.blocks().remaining());
        }
        header.flip();

        Path tempFile = Files.createTempFile(cacheDirectory, key, ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(tempFile, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer[] parts = {header, stored};
                long total = (long) header.remaining() + stored.remaining();
                long written = 0;
                while (written < total) {
                    written += channel.write(parts);
                }
            }
            Files.move(tempFile, fileFor(key, format), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.deleteIfExists(tempFile);
            throw e;
        }
    }

    /** Default on-disk cap; override with {@code -Dtessera.cache.maxMB=N}. */
    private static final long DEFAULT_MAX_BYTES = 512L * 1024 * 1024;
    private static final long STALE_TMP_MILLIS = 60L * 60 * 1000;

    public static long configuredMaxBytes() {
        return Long.getLong("tessera.cache.maxMB", DEFAULT_MAX_BYTES / (1024 * 1024)) * 1024 * 1024;
    }

    /**
     * Least-recently-used eviction down to {@code maxBytes} (hits refresh mtime in
     * {@link #touch}), plus removal of orphaned {@code .tmp} files left by crashed
     * writes. Best-effort: I/O errors on individual files are ignored.
     */
    public void prune(long maxBytes) {
        if (!Files.isDirectory(cacheDirectory)) {
            return;
        }
        record Entry(Path path, long size, long mtime) {
        }
        java.util.List<Entry> entries = new java.util.ArrayList<>();
        long now = System.currentTimeMillis();
        long total = 0L;
        try (java.util.stream.Stream<Path> files = Files.list(cacheDirectory)) {
            for (Path p : (Iterable<Path>) files::iterator) {
                String name = p.getFileName().toString();
                try {
                    long mtime = Files.getLastModifiedTime(p).toMillis();
                    if (name.endsWith(".tmp")) {
                        if (now - mtime > STALE_TMP_MILLIS) {
                            Files.deleteIfExists(p);
                        }
                        continue;
                    }
                    if (java.util.Arrays.stream(CompressedFormat.values()).noneMatch(f -> name.endsWith(f.extension))) {
                        continue;
                    }
                    long size = Files.size(p);
                    entries.add(new Entry(p, size, mtime));
                    total += size;
                } catch (IOException ignored) {
                    // Concurrently removed or unreadable: skip.
                }
            }
        } catch (IOException ignored) {
            return;
        }
        if (total <= maxBytes) {
            return;
        }
        entries.sort(java.util.Comparator.comparingLong(Entry::mtime));
        for (Entry e : entries) {
            if (total <= maxBytes) {
                break;
            }
            try {
                Files.deleteIfExists(e.path());
                total -= e.size();
            } catch (IOException ignored) {
                // Locked on Windows, etc.: try the next one.
            }
        }
    }

    private static void touch(Path file) {
        try {
            Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()));
        } catch (IOException ignored) {
            // LRU ordering is best-effort.
        }
    }

    private Path fileFor(String hashHex, CompressedFormat format) {
        return cacheDirectory.resolve(hashHex + format.fileExtension());
    }

    /**
     * Which GPU block-compression format a cached blob holds. Determines
     * both the on-disk file extension (so BC1/BC7 caches for the same
     * content hash never collide on the filesystem either) and the
     * in-header discriminator checked by {@link #read}.
     */
    public enum CompressedFormat {
        BC1(".bc1"),
        BC7(".bc7"),
        ASTC_4X4(".astc4"),
        ASTC_6X6(".astc6");

        private final String extension;

        CompressedFormat(String extension) {
            this.extension = extension;
        }

        public String fileExtension() {
            return extension;
        }
    }

    public record CachedAtlas(int width, int height, int qualityPreset, ByteBuffer compressedBlocks) {
    }
}
