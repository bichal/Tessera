package net.bichal.mods.tessera.compress;

import net.bichal.mods.tessera.cache.AtlasCache;
import net.bichal.mods.tessera.compress.backend.CompressionBackend;
import net.bichal.mods.tessera.compress.backend.TesseraRuntime;
import net.bichal.mods.tessera.compress.software.TransparentTexelBleed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * BC7/BC1/ASTC encoding entry point. {@link #compressBlocking} encodes on the
 * calling thread (callers run it off the render thread): it applies alpha
 * bleeding, guarantees direct buffers in and out, and retries once on the
 * pure-Java encoder if the native one fails.
 */
public final class CompressionPipeline {

    /**
     * A block-compressed GPU format Tessera can write: the block footprint in texels and the size
     * of one block. All of them are 8 or 16 bytes per block; what differs is how many texels a
     * block covers (and so the bits per texel) and which drivers can sample it.
     */
    public enum Target {
        /** 4 bits per texel, no alpha. S3TC: desktop GPUs. */
        BC1(4, 4, 8, AtlasCache.CompressedFormat.BC1),
        /** 8 bits per texel, alpha. BPTC: desktop GPUs with OpenGL 4.2 (not macOS). */
        BC7(4, 4, 16, AtlasCache.CompressedFormat.BC7),
        /** 8 bits per texel, alpha. ASTC LDR: mobile and Apple GPUs (Android, Apple Silicon). */
        ASTC_4X4(4, 4, 16, AtlasCache.CompressedFormat.ASTC_4X4),
        /** 3.56 bits per texel, for opaque atlases: about BC1's size with ASTC's quality. */
        ASTC_6X6(6, 6, 16, AtlasCache.CompressedFormat.ASTC_6X6);

        private final int blockWidth;
        private final int blockHeight;
        private final int bytesPerBlock;
        private final AtlasCache.CompressedFormat cacheFormat;

        Target(int blockWidth, int blockHeight, int bytesPerBlock, AtlasCache.CompressedFormat cacheFormat) {
            this.blockWidth = blockWidth;
            this.blockHeight = blockHeight;
            this.bytesPerBlock = bytesPerBlock;
            this.cacheFormat = cacheFormat;
        }

        public int blockWidth() {
            return blockWidth;
        }

        public int blockHeight() {
            return blockHeight;
        }

        public int bytesPerBlock() {
            return bytesPerBlock;
        }

        public boolean isAstc() {
            return this == ASTC_4X4 || this == ASTC_6X6;
        }

        /** The format as the disk cache and {@code AtlasCompressEvent} know it. */
        public AtlasCache.CompressedFormat cacheFormat() {
            return cacheFormat;
        }

        public static Target of(AtlasCache.CompressedFormat format) {
            for (Target target : values()) {
                if (target.cacheFormat == format) {
                    return target;
                }
            }
            throw new IllegalArgumentException(String.valueOf(format));
        }

        /** Bytes of one mip level of {@code width x height} texels; partial blocks at the edges count whole. */
        public long levelBytes(int width, int height) {
            long blocksWide = (width + blockWidth - 1) / blockWidth;
            long blocksHigh = (height + blockHeight - 1) / blockHeight;
            return blocksWide * blocksHigh * bytesPerBlock;
        }

        /** OpenGL internal format for {@code glCompressedTexImage2D}. */
        public int glFormat() {
            return switch (this) {
                case BC1 -> org.lwjgl.opengl.EXTTextureCompressionS3TC.GL_COMPRESSED_RGB_S3TC_DXT1_EXT;
                case BC7 -> org.lwjgl.opengl.GL42.GL_COMPRESSED_RGBA_BPTC_UNORM;
                case ASTC_4X4 -> org.lwjgl.opengl.KHRTextureCompressionASTCLDR.GL_COMPRESSED_RGBA_ASTC_4x4_KHR;
                case ASTC_6X6 -> org.lwjgl.opengl.KHRTextureCompressionASTCLDR.GL_COMPRESSED_RGBA_ASTC_6x6_KHR;
            };
        }

        /** Whether the driver can take this format. Needs {@code Tessera#warmUpGl()} to have run on the render thread. */
        public boolean gpuSupported() {
            return switch (this) {
                case BC1 -> Bc1TextureFormatSupport.isSupported();
                case BC7 -> Bc7GpuSupport.isSupported();
                case ASTC_4X4, ASTC_6X6 -> AstcGpuSupport.isSupported();
            };
        }
    }

    private static final Logger LOGGER = LoggerFactory.getLogger("Tessera/CompressionPipeline");

    private CompressionPipeline() {
    }

    /**
     * Synchronous compression on the calling thread. Intended for callers that are
     * already off the render thread (e.g. the split-atlas background stitch), where
     * hopping through a worker pool and result queue would only add latency.
     *
     * <p>Guarantees: the input is handed to the backend as a direct buffer, the result
     * is a direct little-endian buffer suitable for {@code glCompressedTexImage2D},
     * and a native failure falls back to the pure-Java encoder once before giving up.
     *
     * @param mipLevel the level being encoded; the software ASTC encoder checks its quality on level 0 only
     * @return direct buffer of BC blocks, or {@code null} if every backend failed
     */
    public static ByteBuffer compressBlocking(String atlasKey, ByteBuffer rgba8, int width, int height,
                                              Target target, int qualityPreset, int mipLevel) {
        long requiredBytes = (long) width * height * 4L;
        if (width <= 0 || height <= 0 || rgba8.remaining() < requiredBytes) {
            LOGGER.error("Invalid RGBA input for atlas '{}': {}x{} needs {} bytes, buffer has {}",
                    atlasKey, width, height, requiredBytes, rgba8.remaining());
            return null;
        }
        ByteBuffer input = toDirect(rgba8);
        // Invisible texels must not steer block endpoints (white/grey fringes).
        TransparentTexelBleed.apply(input, width, height);
        CompressionBackend backend = TesseraRuntime.backend();
        ByteBuffer compressed;
        try {
            compressed = encode(backend, target, input, width, height, qualityPreset, mipLevel == 0);
        } catch (Throwable t) {
            LOGGER.warn("Backend {} threw while compressing atlas '{}' -- retrying with software encoder",
                    backend.name(), atlasKey, t);
            compressed = null;
        }
        if (compressed == null && TesseraRuntime.isNativeActive()) {
            compressed = encode(TesseraRuntime.softwareBackend(), target, input, width, height, qualityPreset, mipLevel == 0);
        }
        if (compressed == null) {
            LOGGER.warn("Compression failed for atlas '{}' ({} path) on every backend", atlasKey, target);
            return null;
        }
        return toDirect(compressed);
    }

    private static ByteBuffer encode(CompressionBackend backend, Target target, ByteBuffer input, int width,
                                     int height, int quality, boolean guardQuality) {
        return switch (target) {
            case BC7 -> backend.compressBc7(input, width, height, quality);
            case BC1 -> backend.compressBc1(input, width, height, quality);
            case ASTC_4X4, ASTC_6X6 -> backend.supportsAstc()
                    ? backend.compressAstc(input, width, height, target.blockWidth(), target.blockHeight(), quality, guardQuality)
                    : null;
        };
    }

    private static ByteBuffer toDirect(ByteBuffer buffer) {
        if (buffer.isDirect()) {
            return buffer.slice().order(ByteOrder.LITTLE_ENDIAN);
        }
        ByteBuffer direct = ByteBuffer.allocateDirect(buffer.remaining()).order(ByteOrder.LITTLE_ENDIAN);
        direct.put(buffer.duplicate()).flip();
        return direct;
    }
}
