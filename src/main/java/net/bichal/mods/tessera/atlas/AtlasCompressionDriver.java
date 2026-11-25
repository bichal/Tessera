package net.bichal.mods.tessera.atlas;

import net.bichal.mods.tessera.compat.Platform;
import net.bichal.mods.tessera.compat.TesseraCompat;
import net.bichal.mods.tessera.cache.AtlasCache;
import net.bichal.mods.tessera.compress.CompressionPipeline;
import net.bichal.mods.tessera.compress.backend.TesseraRuntime;
import net.bichal.mods.tessera.compress.gpu.GpuCompression;
import net.bichal.mods.tessera.config.Config;
import net.bichal.mods.tessera.gui.DebugOverlay;
import net.bichal.mods.tessera.vram.VramBudgetEngine;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.lwjgl.opengl.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.security.MessageDigest;
import net.bichal.mods.tessera.util.ParallelWork;
import net.bichal.mods.tessera.util.TesseraHasher;

/**
 * BC1/BC7 compression and upload for one atlas texture.
 *
 * <p>Two phases, deliberately separate: {@link #compress} does CPU work only
 * (mip chain, disk cache, encoding) and is safe on a background thread;
 * {@link #upload} issues the GL calls and must run on the render thread.
 * Encoding a full atlas takes long enough that doing it on the render thread
 * would stall frames during every resource reload.
 */
@SuppressWarnings("JavadocReference")
public final class AtlasCompressionDriver {

    private static final Logger LOGGER = LoggerFactory.getLogger("Tessera/AtlasCompressionDriver");

    private static volatile AtlasCache cacheInstance;

    private AtlasCompressionDriver() {
    }

    /**
     * An assembled atlas.
     *
     * @param opaque every sprite texel (padding ring included) has alpha 255, so BC1 loses nothing
     */
    public record AssembledAtlas(ByteBuffer pixels, boolean opaque) {
    }

    /** At most this many sprites per parallel chunk when copying or digesting an atlas's sprites... */
    private static final int SPRITES_PER_CHUNK = 16;
    /** ...and a chunk closes once it holds this many texels, so atlases of a few large sprites split too. */
    private static final int TEXELS_PER_CHUNK = 128 * 128;

    /**
     * Chunk boundaries over {@code sprites}: chunk {@code g} covers {@code [b[g], b[g + 1])}, closing at
     * {@link #SPRITES_PER_CHUNK} sprites or {@link #TEXELS_PER_CHUNK} texels, whichever comes first.
     */
    private static int[] spriteChunks(TextureAtlasSprite[] sprites) {
        int[] bounds = new int[sprites.length + 1];
        int chunks = 0;
        int count = 0;
        long texels = 0;
        for (int i = 0; i < sprites.length; i++) {
            if (count == 0) {
                bounds[chunks++] = i;
            }
            count++;
            texels += (long) sprites[i].contents().width() * sprites[i].contents().height();
            if (count >= SPRITES_PER_CHUNK || texels >= TEXELS_PER_CHUNK) {
                count = 0;
                texels = 0;
            }
        }
        bounds[chunks] = sprites.length;
        return java.util.Arrays.copyOf(bounds, chunks + 1);
    }

    /**
     * Assembles one contiguous RGBA8 buffer for an entire atlas from its
     * stitched sprites' pixel data, placed at each sprite's atlas position
     * ({@code TextureAtlasSprite.getX()/getY()}), and logs how many sprites are
     * perceptual near-duplicates of another (see {@link SpriteFamilyAnalyzer#groupBySimilarity}).
     *
     * @param label       for logging only (e.g. {@code "OPAQUE"}/{@code "ALPHA"})
     * @param regions     every sprite placed in this atlas by its own stitch pass
     * @param atlasWidth  the atlas's own packed width (from {@code Preparations.width()})
     * @param atlasHeight the atlas's own packed height (from {@code Preparations.height()})
     * @return the assembled RGBA8 buffer and whether it is opaque, or {@code null} when native
     * compression is disabled and the caller should fall back to an uncompressed upload
     */
    public static AssembledAtlas assembleAtlasBuffer(
            String label, Collection<TextureAtlasSprite> regions, int atlasWidth, int atlasHeight
    ) {

        // Mirrors compress(): with native compression disabled the atlas stays vanilla, so there
        // is nothing to assemble. No native-availability gate: TesseraRuntime falls back to the
        // software encoder, and gating on native would disable that fallback entirely.
        if (Config.get(Config.DISABLE_NATIVE_COMPRESSION)) {
            return null;
        }

        // Each sprite writes its own rectangle of the atlas, so groups of sprites are copied in
        // parallel, each fingerprinted where it lands. A rectangle that does not fit is left out.
        byte[] atlas = new byte[atlasWidth * atlasHeight * 4];
        TextureAtlasSprite[] sprites = regions.toArray(new TextureAtlasSprite[0]);
        long[] fingerprints = new long[sprites.length];
        int[] chunks = spriteChunks(sprites);
        int groups = chunks.length - 1;
        boolean[] opaqueGroups = new boolean[groups];
        ParallelWork.forEach(groups, group -> {
            int texelAnd = -1;
            int[] row = new int[0];
            // Rows go in with one bulk copy each: no per-texel stores, which are slow before the JIT compiles them.
            IntBuffer atlasInts = ByteBuffer.wrap(atlas).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer();
            for (int i = chunks[group], end = chunks[group + 1]; i < end; i++) {
                TextureAtlasSprite sprite = sprites[i];
                int padding = TesseraCompat.padding(sprite);
                int contentWidth = sprite.contents().width();
                int contentHeight = sprite.contents().height();
                int width = contentWidth + 2 * padding;
                int height = contentHeight + 2 * padding;
                int originX = sprite.getX();
                int originY = sprite.getY();
                if (originX < 0 || originY < 0 || originX + width > atlasWidth || originY + height > atlasHeight) {
                    continue;
                }
                if (row.length < width) {
                    row = new int[width];
                }
                int readY = -1;
                for (int y = 0; y < height; y++) {
                    int sy = Math.max(0, Math.min(contentHeight - 1, y - padding));
                    if (sy != readY) {
                        readY = sy;
                        // The sprite row goes in the middle; its edge texels repeat over the padding.
                        TesseraCompat.spriteRow(sprite, sy, row);
                        System.arraycopy(row, 0, row, padding, contentWidth);
                        for (int x = 0; x < padding; x++) {
                            row[x] = row[padding];
                            row[padding + contentWidth + x] = row[padding + contentWidth - 1];
                        }
                        for (int x = 0; x < width; x++) {
                            texelAnd &= row[x];
                        }
                    }
                    atlasInts.put((originY + y) * atlasWidth + originX, row, 0, width);
                }
                fingerprints[i] = SpriteFamilyAnalyzer.fingerprint(
                        atlas, (originY * atlasWidth + originX) * 4, width, height, atlasWidth);
            }
            opaqueGroups[group] = (texelAnd >>> 24) == 0xFF;
        });
        boolean opaque = true;
        for (boolean groupOpaque : opaqueGroups) {
            opaque &= groupOpaque;
        }

        int families = SpriteFamilyAnalyzer.groupBySimilarity(
                fingerprints, new boolean[sprites.length], Config.get(Config.DEDUP_SIMILARITY_THRESHOLD)).size();
        if (families < sprites.length) {
            LOGGER.info("{} of {} sprites in the {} atlas are perceptual near-duplicates of another sprite in this atlas.",
                    sprites.length - families, sprites.length, label);
        }

        return new AssembledAtlas(ByteBuffer.wrap(atlas), opaque);
    }

    /**
     * What identifies an atlas for the whole-chain cache: a digest of its layout and every
     * sprite's pixels, and what their alpha holds (which decides the format).
     */
    public record Fingerprint(String digest, AtlasFormats.Alpha alpha) {

        /** Every sprite texel has alpha 255. */
        public boolean opaque() {
            return alpha == AtlasFormats.Alpha.OPAQUE;
        }
    }

    /**
     * Digest of an atlas as stitched: its size, mip level, each sprite's position, padding and
     * pixels. Cheaper than {@link #assembleAtlasBuffer} plus per-level hashing, and enough to
     * tell whether an encoded chain from an earlier run still applies.
     */
    public static Fingerprint fingerprint(
            Collection<TextureAtlasSprite> regions, int atlasWidth, int atlasHeight, int mipLevel) {
        MessageDigest digest = TesseraHasher.newDigest();
        ByteBuffer head = ByteBuffer.allocate(Integer.BYTES * 5).order(ByteOrder.LITTLE_ENDIAN);
        head.putInt(atlasWidth).putInt(atlasHeight).putInt(mipLevel)
                .putInt(Config.get(Config.DEDUP_SIMILARITY_THRESHOLD).hashCode()).putInt(regions.size());
        digest.update(head.array());

        // Each sprite is digested on its own, in parallel; the atlas digest takes those in order.
        TextureAtlasSprite[] sprites = regions.toArray(new TextureAtlasSprite[0]);
        byte[][] spriteDigests = new byte[sprites.length][];
        AtlasFormats.Alpha[] alphas = new AtlasFormats.Alpha[sprites.length];
        int[] chunks = spriteChunks(sprites);
        ParallelWork.forEach(chunks.length - 1, group -> {
            MessageDigest spriteDigest = TesseraHasher.newDigest();
            byte[] row = new byte[0];
            IntBuffer rowInts = null;
            int[] texels = new int[0];
            ByteBuffer place = ByteBuffer.allocate(Integer.BYTES * 4).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = chunks[group], end = chunks[group + 1]; i < end; i++) {
                TextureAtlasSprite sprite = sprites[i];
                int padding = TesseraCompat.padding(sprite);
                int contentWidth = sprite.contents().width();
                int width = contentWidth + 2 * padding;
                int height = sprite.contents().height() + 2 * padding;
                int maxY = sprite.contents().height() - 1;
                place.clear();
                place.putInt(sprite.getX()).putInt(sprite.getY()).putInt(width).putInt(height);
                spriteDigest.update(place.array());
                if (row.length < width * 4) {
                    row = new byte[width * 4];
                    rowInts = ByteBuffer.wrap(row).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer();
                    texels = new int[width];
                }
                // Alpha over the content: the AND of every alpha, and whether any is neither 0 nor 255.
                int alphaAnd = 0xFF;
                int blended = 0;
                int readY = -1;
                for (int y = 0; y < height; y++) {
                    int sy = Math.max(0, Math.min(maxY, y - padding));
                    if (sy != readY) {
                        readY = sy;
                        TesseraCompat.spriteRow(sprite, sy, texels);
                        for (int x = 0; x < contentWidth; x++) {
                            int a = texels[x] >>> 24;
                            alphaAnd &= a;
                            blended |= (a + 1) & 0xFE;
                        }
                        // The padded row, edge texels repeated, written in one bulk copy (no per-texel stores:
                        // this runs once per reload, mostly before the JIT has compiled it).
                        System.arraycopy(texels, 0, texels, padding, contentWidth);
                        java.util.Arrays.fill(texels, 0, padding, texels[padding]);
                        java.util.Arrays.fill(texels, padding + contentWidth, width, texels[padding + contentWidth - 1]);
                        rowInts.put(0, texels, 0, width);
                    }
                    spriteDigest.update(row, 0, width * 4);
                }
                AtlasFormats.Alpha alpha = blended != 0 ? AtlasFormats.Alpha.BLENDED
                        : alphaAnd == 0xFF ? AtlasFormats.Alpha.OPAQUE : AtlasFormats.Alpha.BINARY;
                spriteDigests[i] = spriteDigest.digest();
                alphas[i] = alpha;
            }
        });
        AtlasFormats.Alpha alpha = AtlasFormats.Alpha.OPAQUE;
        for (int i = 0; i < sprites.length; i++) {
            digest.update(spriteDigests[i]);
            if (alphas[i].ordinal() > alpha.ordinal()) {
                alpha = alphas[i];
            }
        }
        return new Fingerprint(TesseraHasher.hex(digest), alpha);
    }

    /**
     * @param refused the chain of the fallback format for an atlas whose chosen format refused it
     *                (kept apart, so it is never served for an atlas that format would take)
     */
    private static String chainKey(Fingerprint fingerprint, int quality, boolean refused) {
        return fingerprint.digest() + "-chain-q" + quality + backendTag() + CACHE_VERSION
                + (refused ? "-r" : "");
    }

    /**
     * The encoded chain stored for this exact atlas, or {@code null} when there is none or it
     * cannot be read; the caller then assembles and encodes.
     *
     * @param refused look for the chain stored after the preferred format refused this atlas
     */
    public static CompressedAtlas loadChain(
            String atlasLocation, CompressionPipeline.Target target, Fingerprint fingerprint, int requestedMaxLevel,
            boolean refused
    ) {
        int quality = Config.get(Config.COMPRESSION_QUALITY);
        try {
            var hit = cache().readChain(chainKey(fingerprint, quality, refused), target.cacheFormat(), quality);
            if (hit.isEmpty()) {
                return null;
            }
            List<CompressedLevel> levels = new ArrayList<>(hit.get().size());
            long resident = 0;
            for (int level = 0; level < hit.get().size(); level++) {
                AtlasCache.ChainLevel stored = hit.get().get(level);
                if (stored.blocks().remaining() != target.levelBytes(stored.width(), stored.height())) {
                    return null;
                }
                resident += stored.blocks().remaining();
                levels.add(new CompressedLevel(level, stored.width(), stored.height(), stored.blocks()));
            }
            // Advisory only: the compressed chain always takes less than the RGBA atlas it replaces.
            VramBudgetEngine.isWithinBudget(atlasLocation, resident);
            LOGGER.info("Atlas {}: all {} mip level(s) served from one disk cache entry.", atlasLocation, levels.size());
            return new CompressedAtlas(atlasLocation, target, levels, requestedMaxLevel);
        } catch (IOException | RuntimeException e) {
            LOGGER.debug("Ignoring unreadable chain entry for atlas {}", atlasLocation, e);
            return null;
        }
    }

    /**
     * Stores a complete chain under the atlas fingerprint for the next run.
     *
     * @param refused {@code compressed} is in the fallback format, after the preferred one refused the atlas
     */
    public static void storeChain(Fingerprint fingerprint, CompressedAtlas compressed, boolean refused) {
        if (compressed == null || !isFullChain(compressed)) {
            return;
        }
        int quality = Config.get(Config.COMPRESSION_QUALITY);
        List<AtlasCache.ChainLevel> levels = new ArrayList<>(compressed.levels().size());
        for (CompressedLevel level : compressed.levels()) {
            // A view of its own: the chain may be uploading on the render thread meanwhile.
            levels.add(new AtlasCache.ChainLevel(level.width(), level.height(), level.compressedBlocks().duplicate()));
        }
        try {
            cache().writeChain(chainKey(fingerprint, quality, refused), compressed.target().cacheFormat(), quality, levels);
        } catch (IOException | RuntimeException e) {
            LOGGER.debug("Failed writing chain entry for atlas {}", compressed.atlasLocation(), e);
        }
    }

    /**
     * Upper-bound byte estimate for an atlas's <em>full</em> mip chain (base
     * level down to 1x1). A full chain never exceeds 4/3 of the base level,
     * since {@code sum(1/4^i)} converges to {@code 4/3}.
     */
    public static long estimateFullMipChainBytes(int width, int height, CompressionPipeline.Target target, int maxLevel) {
        long total = 0L;
        int levelWidth = width;
        int levelHeight = height;
        // Mirrors MipChainBuilder/the native build_mip_chain's actual level
        // progression (halve-and-floor-to-1, stop once both dims hit 1) up
        // to maxLevel, rather than assuming the full geometric series down
        // to 1x1 regardless of how many levels will actually be requested
        // -- the previous (baseLevelBytes * 4) / 3 shortcut always assumed
        // an unbounded chain, which could overestimate VRAM cost enough to
        // reject an atlas that would have comfortably fit at its actual
        // requested level count.
        for (int level = 0; level <= maxLevel; level++) {
            total += target.levelBytes(levelWidth, levelHeight);
            if (levelWidth <= 1 && levelHeight <= 1) {
                break;
            }
            levelWidth = Math.max(levelWidth / 2, 1);
            levelHeight = Math.max(levelHeight / 2, 1);
        }
        return total;
    }

    /**
     * CPU-only compression phase: builds the mip chain, then for each level
     * either loads it from the disk cache or encodes it (native encoder when
     * available, pure-Java otherwise). No GL calls; safe on any thread.
     * Hand the result to {@link #upload} on the render thread.
     *
     * @return a {@link CompressedAtlas} with zero or more levels (zero levels
     * means compression was skipped; the caller keeps the uncompressed atlas)
     */
    public static CompressedAtlas compress(
            String atlasLocation, CompressionPipeline.Target target,
            ByteBuffer baseRgba8, int baseWidth, int baseHeight, int requestedMaxLevel
    ) {
        return compress(atlasLocation, target, baseRgba8, baseWidth, baseHeight, requestedMaxLevel, Integer.MAX_VALUE);
    }

    /**
     * @param retainRgbaFromLevel mip levels at or above this index also keep their RGBA pixels in the
     *                            result (see {@link CompressedAtlas#retainedRgba}); {@link Integer#MAX_VALUE}
     *                            retains nothing
     */
    @SuppressWarnings("LoggingSimilarMessage")
    public static CompressedAtlas compress(
            String atlasLocation, CompressionPipeline.Target target,
            ByteBuffer baseRgba8, int baseWidth, int baseHeight, int requestedMaxLevel, int retainRgbaFromLevel
    ) {
        if (!target.gpuSupported()) {
            LOGGER.info("[Tessera coverage] Atlas {} SKIPPED ({} unsupported on this GPU).", atlasLocation, target);
            return new CompressedAtlas(atlasLocation, target, List.of(), requestedMaxLevel);
        }

        // Advisory only (it logs a warning): leaving an atlas uncompressed because the compressed chain
        // is over budget would put back the RGBA atlas, which takes two to eight times more.
        VramBudgetEngine.isWithinBudget(atlasLocation, estimateFullMipChainBytes(baseWidth, baseHeight, target, requestedMaxLevel));

        List<MipChainBuilder.MipLevel> mipLevels = MipChainBuilder.build(baseRgba8, baseWidth, baseHeight, requestedMaxLevel);
        if (mipLevels == null || mipLevels.isEmpty()) {
            // Native mip-chain generation unavailable/failed entirely --
            // fall back to compressing level 0 only.
            mipLevels = List.of(new MipChainBuilder.MipLevel(baseWidth, baseHeight, baseRgba8));
        }

        // Synchronous on the caller's thread: the split-atlas path already runs this
        // off the render thread, and the native encoder parallelises each level
        // internally, so a per-call pool plus queue would only add overhead.
        List<CompressedLevel> compressedLevels = new ArrayList<>(mipLevels.size());
        int quality = Config.get(Config.COMPRESSION_QUALITY);
        String key = atlasLocation.toString();
        AtlasCache cache = cache();
        AtlasCache.CompressedFormat format = target.cacheFormat();
        String backendTag = backendTag();
        int cacheHits = 0;

        for (int level = 0; level < mipLevels.size(); level++) {
            MipChainBuilder.MipLevel mipLevel = mipLevels.get(level);
            int width = mipLevel.width();
            int height = mipLevel.height();
            // Clamp so a short buffer reaches compressBlocking's validation instead of throwing here.
            int rgbaBytes = Math.min(width * height * 4, mipLevel.rgba8().remaining());
            // Key on the input pixels per level, so any mip-filter change also invalidates.
            String cacheKey = cache.hashHex(mipLevel.rgba8(), rgbaBytes) + "-q" + quality + backendTag + CACHE_VERSION;

            ByteBuffer blocks = readCached(cache, cacheKey, format, width, height, quality);
            if (blocks != null) {
                cacheHits++;
            } else {
                blocks = CompressionPipeline.compressBlocking(key, mipLevel.rgba8(), width, height, target, quality, level, true);
                if (blocks == null) {
                    // Keep the levels already produced; upload() caps MAX_LEVEL accordingly.
                    break;
                }
                // Not written back per level: the caller stores the whole chain in one entry
                // (storeChain), and a changed sprite changes every level's pixels anyway. Per-level
                // entries from earlier versions are still read above.
            }
            compressedLevels.add(new CompressedLevel(level, width, height, blocks));
        }
        if (cacheHits > 0) {
            LOGGER.info("Atlas {}: {} of {} mip level(s) served from disk cache.", atlasLocation, cacheHits, compressedLevels.size());
        }

        return new CompressedAtlas(atlasLocation, target, compressedLevels, requestedMaxLevel,
                retained(mipLevels, retainRgbaFromLevel));
    }

    /**
     * The RGBA pixels of levels {@code fromLevel} and deeper, built from the base level the same way
     * {@link #compress} builds them. For an atlas served from the disk cache whose animated sprites
     * need those levels patched per frame.
     */
    public static Map<Integer, MipChainBuilder.MipLevel> retainedLevels(
            ByteBuffer baseRgba8, int baseWidth, int baseHeight, int requestedMaxLevel, int fromLevel) {
        if (fromLevel > requestedMaxLevel) {
            return Map.of();
        }
        return retained(MipChainBuilder.build(baseRgba8, baseWidth, baseHeight, requestedMaxLevel), fromLevel);
    }

    private static Map<Integer, MipChainBuilder.MipLevel> retained(List<MipChainBuilder.MipLevel> levels, int fromLevel) {
        Map<Integer, MipChainBuilder.MipLevel> retained = new HashMap<>();
        for (int level = Math.max(0, fromLevel); level < levels.size(); level++) {
            retained.put(level, levels.get(level));
        }
        return Map.copyOf(retained);
    }

    /**
     * Render-thread-only upload phase: takes a {@link CompressedAtlas}
     * already built by {@link #compress} (on a background thread) and
     * issues the actual {@code glCompressedTexImage2D} calls. This is the
     * only part of the pipeline that touches GL and therefore the only
     * part that must run on the render thread.
     *
     * @return total resident bytes across all uploaded levels, or
     * {@code -1} if not even the base level could be uploaded (or
     * {@code compressed} had zero levels to begin with) -- the
     * caller should leave the atlas on its existing uncompressed
     * upload in that case
     */
    public static long upload(int textureId, CompressedAtlas compressed) {
        if (compressed.levels().isEmpty()) {
            return -1;
        }

        // Per-atlas GL debug group: every GL call until the matching pop, and
        // any async debug message the driver emits for it, is tagged with this
        // atlas's location, so upload errors are attributable in the log.
        // Harmless on drivers that ignore debug groups.
        // Only where the context has it: OpenGL 4.3 / KHR_debug is missing on macOS (4.1) and on
        // OpenGL ES-based layers, where calling it would crash the render thread.
        boolean debugGroups = hasDebugGroups();
        String debugGroupLabel = "tessera:upload:" + compressed.atlasLocation();
        if (debugGroups) {
            GL43.glPushDebugGroup(GL43.GL_DEBUG_SOURCE_APPLICATION, 0, debugGroupLabel);
        }
        try {
            long resident = tessera$uploadInner(textureId, compressed);
            // -1 (nothing uploaded) clears the entry: the atlas stays vanilla RGBA.
            VramBudgetEngine.recordResident(compressed.atlasLocation(), resident);
            return resident;
        } finally {
            if (debugGroups) {
                GL43.glPopDebugGroup();
            }

            // One-shot, non-looping poll -- deliberately NOT the flood-
            // amplifying spin loop the removed tessera$flushGlErrors() was
            // (see uploadCompressedLevel's own doc comment on why that
            // was wrong). A single call here produces at most one log
            // line per upload() invocation (there are only ever a
            // handful of these per reload, one per atlas), so this cannot
            // recreate the multi-thousand-line flood the way polling
            // inside a per-sprite or per-block loop would. This does NOT
            // replace the async GLDebugMessageCallback as the primary
            // error-reporting mechanism -- that is untouched and is what
            // actually identifies *which* GL call failed; this is purely
            // a "did upload() as a whole leave any error flagged" signal,
            // logged at DEBUG so it stays out of the way by default.
            int trailingError = GL11.glGetError();
            if (trailingError != GL11.GL_NO_ERROR) {
                LOGGER.debug("GL error {} flagged at some point during {} (see the async GlDebug messages logged around this atlas's upload for the specific failing call).",
                        trailingError, debugGroupLabel);
            }
        }
    }

    private static boolean hasImmutableQuery() {
        var caps = GL.getCapabilities();
        return caps.OpenGL42 || caps.GL_ARB_texture_storage;
    }

    /**
     * Whether the bound texture's level 0 is now block-compressed. Asked as "compressed at all"
     * rather than "this exact format": some drivers report BC1 without alpha as its RGBA variant.
     */
    private static boolean levelZeroCompressed() {
        return GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL13.GL_TEXTURE_COMPRESSED) != GL11.GL_FALSE;
    }

    private static boolean hasDebugGroups() {
        var caps = GL.getCapabilities();
        return caps.OpenGL43 || caps.GL_KHR_debug;
    }

    private static long tessera$uploadInner(int textureId, CompressedAtlas compressed) {
        if (TesseraCompat.FULL_CHAIN_REQUIRED && !isFullChain(compressed)) {
            // Texture views reset GL_TEXTURE_MAX_LEVEL to the full chain: a level
            // left in RGBA next to compressed ones would make the atlas incomplete.
            LOGGER.warn("Atlas {}: compressed mip chain incomplete ({} of {} levels); keeping it uncompressed.",
                    compressed.atlasLocation(), compressed.levels().size(), compressed.requestedMaxLevel() + 1);
            return -1;
        }
        TesseraCompat.bindTexture(textureId);
        TesseraCompat.resetUnpackState();

        // Immutable storage (glTexStorage2D, e.g. from another mod) cannot be respecified: the
        // compressed upload would fail and leave the atlas as it is, so it is not attempted.
        if (hasImmutableQuery()
                && GL11.glGetTexParameteri(GL11.GL_TEXTURE_2D, GL42.GL_TEXTURE_IMMUTABLE_FORMAT) != GL11.GL_FALSE) {
            LOGGER.info("Atlas {} has immutable texture storage; keeping it uncompressed.", compressed.atlasLocation());
            return -1;
        }

        // requestedMaxLevel is the atlas's own mip level count. It only bounds
        // how deep this upload goes: on vanilla's mutable storage,
        // glCompressedTexImage2D respecifies each level, so there is no
        // pre-allocated depth to overflow.
        int maxAllocatedLevel = compressed.requestedMaxLevel();

        long totalResidentBytes = 0L;
        // Sum of each uploaded level's own uncompressed RGBA8 footprint
        // (width*height*4), NOT just the base level -- a full mip chain's
        // uncompressed baseline is the same geometric-series sum used by
        // estimateFullMipChainBytes, so tallying per-level here rather
        // than assuming level 0 alone keeps the DebugOverlay's "saved MB"
        // figure consistent with what vanilla's own uncompressed upload
        // would actually have resident for the same level count.
        long totalUncompressedBytes = 0L;
        int lastUploadedLevel = -1;

        for (CompressedLevel level : compressed.levels()) {
            if (level.level() > maxAllocatedLevel) {
                LOGGER.warn("Atlas {} compressed chain produced level {} beyond the {} level(s) vanilla's own upload allocated storage for; stopping chain here.",
                        compressed.atlasLocation(), level.level(), maxAllocatedLevel + 1);
                break;
            }
            boolean uploaded = uploadCompressedLevel(
                    compressed.atlasLocation(), compressed.target(), level.level(), level.width(), level.height(), level.compressedBlocks());
            if (!uploaded) {
                break;
            }
            // One query, on level 0 only: if the driver did not take the base level, nothing changed
            // (the texture is still vanilla's RGBA) and uploading the other levels would leave it
            // incomplete, which samples as black.
            if (level.level() == 0 && !levelZeroCompressed()) {
                LOGGER.warn("Atlas {}: the driver did not take {} for the base level; keeping it uncompressed.",
                        compressed.atlasLocation(), compressed.target());
                return -1;
            }
            totalResidentBytes += level.compressedBlocks().remaining();
            totalUncompressedBytes += (long) level.width() * level.height() * 4;
            lastUploadedLevel = level.level();
        }

        if (lastUploadedLevel < 0) {
            return -1;
        }

        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_BASE_LEVEL, 0);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LEVEL, lastUploadedLevel);
        applyTextureFilteringState(lastUploadedLevel);

        if (lastUploadedLevel < compressed.requestedMaxLevel()) {
            LOGGER.info("Atlas {} mip chain stopped early at level {} of {} requested.",
                    compressed.atlasLocation(), lastUploadedLevel, compressed.requestedMaxLevel());
        }

        // Feeds the F3 debug overlay (see DebugOverlay#recordCompression).
        long savedBytes = totalUncompressedBytes - totalResidentBytes;
        DebugOverlay.recordCompression(compressed.atlasLocation().toString(), savedBytes, totalResidentBytes);
        DebugOverlay.recordBucketCompression(
                compressed.atlasLocation().toString(), compressed.target().name(), savedBytes, totalResidentBytes);

        return totalResidentBytes;
    }

    /** Levels 0..requestedMaxLevel, in order, each with exactly the block bytes its size needs. */
    private static boolean isFullChain(CompressedAtlas compressed) {
        if (compressed.levels().size() != compressed.requestedMaxLevel() + 1) {
            return false;
        }
        for (int i = 0; i < compressed.levels().size(); i++) {
            CompressedLevel level = compressed.levels().get(i);
            if (level.level() != i || level.compressedBlocks() == null
                    || level.compressedBlocks().remaining() != compressed.target().levelBytes(level.width(), level.height())) {
                return false;
            }
        }
        return true;
    }

    private static void applyTextureFilteringState(int maxMipLevel) {
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER,
                maxMipLevel > 0 ? GL11.GL_NEAREST_MIPMAP_LINEAR : GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
    }

    private static boolean uploadCompressedLevel(
            String atlasLocation, CompressionPipeline.Target target,
            int level, int width, int height, ByteBuffer compressedBlocks
    ) {
        if (compressedBlocks == null) {
            return false;
        }

        long expectedBytes = target.levelBytes(width, height);

        if (compressedBlocks.remaining() != expectedBytes) {
            LOGGER.error(
                    "{} size mismatch for atlas {} mip level {} ({}x{}): received {} bytes, expected {}.",
                    target, atlasLocation, level, width, height, compressedBlocks.remaining(), expectedBytes
            );
            return false;
        }

        // Never issue glCompressedTexImage2D with a format the driver has not advertised:
        // the level's storage would be left undefined.
        if (!target.gpuSupported()) {
            LOGGER.warn("Atlas {} {} upload skipped: the driver does not advertise the format.", atlasLocation, target);
            return false;
        }

        int glInternalFormat = target.glFormat();

        // glCompressedTexImage2D (not glCompressedTexSubImage2D): vanilla allocates
        // atlas storage per level with glTexImage2D as mutable GL_RGBA8, and a
        // compressed *sub*-image upload into an RGBA8 level is GL_INVALID_OPERATION.
        // Respecifying the level's format and storage in one call is legal on
        // mutable storage.
        GL13.glCompressedTexImage2D(
                GL11.GL_TEXTURE_2D, level, glInternalFormat, width, height, 0, compressedBlocks
        );

        // No glGetError() here: polling error state on the render thread is a
        // synchronous pipeline stall. GL errors are reported asynchronously by
        // vanilla's GlDebug callback, which logs any failure of this call.

        long uncompressedSize = (long) width * height * 4;
        long compressedSize = compressedBlocks.remaining();
        double savedMB = (uncompressedSize - compressedSize) / (1024.0 * 1024.0);
        LOGGER.info("Successfully compressed atlas {} to {} mip level {}: {}x{}. VRAM saved: {} MB",
                atlasLocation, target, level, width, height, String.format("%.2f", savedMB));
        return true;
    }

    /** Bump whenever encoder output for identical input changes (new presets, encoder upgrade). */
    /**
     * Which encoder a cache entry came from: software output must not be served forever once native
     * becomes available, and GPU output (experimental) is kept apart from the CPU's both ways.
     */
    private static String backendTag() {
        return GpuCompression.activeEncoder() != null ? "g" : TesseraRuntime.isNativeActive() ? "n" : "s";
    }

    private static final String CACHE_VERSION = "v3"; // v2: alpha bleeding before encode; v3: bc7f + bc7enc best-of BC7

    private static ByteBuffer readCached(AtlasCache cache, String cacheKey, AtlasCache.CompressedFormat format,
                                         int width, int height, int quality) {
        try {
            return cache.read(cacheKey, format)
                    .filter(hit -> hit.width() == width && hit.height() == height && hit.qualityPreset() == quality)
                    .map(AtlasCache.CachedAtlas::compressedBlocks)
                    .orElse(null);
        } catch (IOException | RuntimeException e) {
            // Corrupt or unreadable entry: treat as a miss and recompress.
            LOGGER.debug("Ignoring unreadable cache entry {}", cacheKey, e);
            return null;
        }
    }

    private static synchronized AtlasCache cache() {
        if (cacheInstance == null) {
            cacheInstance = new AtlasCache(Platform.gameDir().resolve(Config.get(Config.CACHE_DIRECTORY)));
            // Once per (re)creation, i.e. per resource reload, on whichever thread
            // calls compress(); a directory listing, negligible next to encoding.
            cacheInstance.prune(AtlasCache.configuredMaxBytes());
        }
        return cacheInstance;
    }

    /**
     * Drops the memoized {@link AtlasCache}, forcing the next {@link #cache()}
     * call to re-read {@link Config#CACHE_DIRECTORY} and rebuild it against
     * the current value. Without this, {@code cacheInstance} stays pinned to
     * whatever directory was configured the first time any reload needed the
     * cache, for the rest of the game session -- changing
     * {@code cacheDirectory} in the settings screen would silently do
     * nothing until restart. Called from {@code Tessera}'s config-reload
     * listener.
     */
    public static synchronized void invalidateCache() {
        cacheInstance = null;
    }

    /**
     * Result of the CPU-side compression phase: every successfully
     * compressed mip level, ready to be uploaded. Carries no GL state and
     * touches no GL calls to produce -- safe to build entirely on a
     * background executor thread. {@link #upload} is the only part of
     * this pipeline that must run on the render thread.
     */
    public record CompressedAtlas(
            String atlasLocation,
            CompressionPipeline.Target target,
            List<CompressedLevel> levels,
            int requestedMaxLevel,
            // RGBA pixels of the mip levels where animated sprites are smaller than a 4x4 block,
            // kept so their frames can be patched and re-encoded block by block. Empty for most atlases.
            Map<Integer, MipChainBuilder.MipLevel> retainedRgba
    ) {
        public CompressedAtlas(String atlasLocation, CompressionPipeline.Target target,
                               List<CompressedLevel> levels, int requestedMaxLevel) {
            this(atlasLocation, target, levels, requestedMaxLevel, Map.of());
        }

        /** The same chain with these RGBA levels retained. */
        public CompressedAtlas withRetained(Map<Integer, MipChainBuilder.MipLevel> rgba) {
            return new CompressedAtlas(atlasLocation, target, levels, requestedMaxLevel, rgba);
        }
    }

    public record CompressedLevel(int level, int width, int height, ByteBuffer compressedBlocks) {
    }
}
