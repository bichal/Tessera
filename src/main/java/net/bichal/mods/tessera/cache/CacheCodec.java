package net.bichal.mods.tessera.cache;

import net.bichal.mods.celeris.core.memory.CelerisCodecs;
import net.bichal.mods.celeris.core.memory.CompressionCodec;
import net.bichal.mods.celeris.core.memory.backend.CelerisRuntime;
import net.bichal.mods.celeris.core.memory.backend.MemoryBackend;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Lossless compression of cache payloads with Celeris's codec (native zstd when it is
 * loaded, deflate otherwise). Block-compressed atlases are mostly runs of identical or
 * empty blocks, so entries shrink to a fraction of their size at the price of one
 * decompression per cache hit.
 *
 * <p>Both directions go through Celeris's off-heap {@link MemoryBackend}, which is what the
 * codec works on; any failure returns {@code null} and the caller stores or recompresses
 * the data as it was.
 */
final class CacheCodec {

    private static final Logger LOGGER = LoggerFactory.getLogger("Tessera/CacheCodec");
    /** zstd's default level: the smallest entries for a write that happens once, off the render thread. */
    private static final int LEVEL = 3;
    private static final int COPY_CHUNK = 1 << 20;

    private CacheCodec() {
    }

    /** {@code raw} compressed, or {@code null} when the codec fails or the result is not smaller. */
    static ByteBuffer compress(ByteBuffer raw) {
        int length = raw.remaining();
        try {
            MemoryBackend backend = CelerisRuntime.backend();
            CompressionCodec codec = CelerisCodecs.compressionCodec();
            long bound = codec.compressBound(length);
            long src = backend.allocate(length, 64);
            try {
                long dst = backend.allocate(bound, 64);
                try {
                    store(backend, src, raw.duplicate());
                    long written = codec.compress(backend, dst, bound, src, 0, length, LEVEL);
                    if (written <= 0 || written >= length) {
                        return null;
                    }
                    return load(backend, dst, Math.toIntExact(written));
                } finally {
                    backend.free(dst);
                }
            } finally {
                backend.free(src);
            }
        } catch (Throwable t) {
            LOGGER.debug("Cache payload not compressed", t);
            return null;
        }
    }

    /** The {@code rawLength} bytes {@code stored} was made from, or {@code null} if it does not decode. */
    static ByteBuffer decompress(ByteBuffer stored, int rawLength) {
        try {
            MemoryBackend backend = CelerisRuntime.backend();
            CompressionCodec codec = CelerisCodecs.compressionCodec();
            int storedLength = stored.remaining();
            long src = backend.allocate(storedLength, 64);
            try {
                long dst = backend.allocate(rawLength, 64);
                try {
                    store(backend, src, stored.duplicate());
                    long written = codec.decompress(backend, dst, rawLength, src, 0, storedLength);
                    return written == rawLength ? load(backend, dst, rawLength) : null;
                } finally {
                    backend.free(dst);
                }
            } finally {
                backend.free(src);
            }
        } catch (Throwable t) {
            LOGGER.debug("Cache payload does not decode", t);
            return null;
        }
    }

    /** Copies {@code from}'s remaining bytes to the start of the allocation. */
    private static void store(MemoryBackend backend, long handle, ByteBuffer from) {
        int length = from.remaining();
        ByteBuffer view = backend.byteBuffer(handle, 0, length);
        if (view != null) {
            view.put(from);
            return;
        }
        byte[] chunk = new byte[Math.min(length, COPY_CHUNK)];
        for (int offset = 0; offset < length; ) {
            int n = Math.min(chunk.length, length - offset);
            from.get(chunk, 0, n);
            backend.copyFromHeap(handle, offset, chunk, 0, n);
            offset += n;
        }
    }

    /** A direct little-endian buffer holding the first {@code length} bytes of the allocation. */
    private static ByteBuffer load(MemoryBackend backend, long handle, int length) {
        ByteBuffer out = ByteBuffer.allocateDirect(length).order(ByteOrder.LITTLE_ENDIAN);
        ByteBuffer view = backend.byteBuffer(handle, 0, length);
        if (view != null) {
            out.put(view).flip();
            return out;
        }
        byte[] chunk = new byte[Math.min(length, COPY_CHUNK)];
        for (int offset = 0; offset < length; ) {
            int n = Math.min(chunk.length, length - offset);
            backend.copyToHeap(handle, offset, chunk, 0, n);
            out.put(chunk, 0, n);
            offset += n;
        }
        return out.flip();
    }
}
