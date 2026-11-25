package net.bichal.mods.tessera.compress;

import com.mojang.blaze3d.platform.NativeImage;
import net.bichal.mods.tessera.atlas.MipChainBuilder;
import net.bichal.mods.tessera.compat.TesseraCompat;
import net.bichal.mods.tessera.compress.backend.TesseraRuntime;
import net.bichal.mods.tessera.compress.software.SoftwareBc1Encoder;
import net.bichal.mods.tessera.compress.software.SoftwareBc7Encoder;
import net.bichal.mods.tessera.compress.software.TransparentTexelBleed;
import net.bichal.mods.tessera.config.Config;
import net.bichal.mods.tessera.selftest.TesseraSelfTest;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.lwjgl.opengl.GL11;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.lwjgl.opengl.GL13;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.bichal.mods.celeris.framework.async.AsyncResultQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Keeps animated sprites working on a block-compressed atlas.
 *
 * <p>Vanilla animates by writing RGBA frames into the atlas: sub-image
 * uploads up to 1.21.10, GPU draws into the atlas from 1.21.11. Both are
 * invalid on a BC texture. Instead, each frame region is encoded to BC blocks
 * and written with {@code glCompressedTexSubImage2D}. That is legal for
 * 4x4-block-aligned regions, which {@link #alignedMipLevels} guarantees by
 * capping the atlas's compressed mip chain.
 *
 * <p>Everything encoded on the render thread uses the plain software encoder:
 * the native one costs about 5 ms per call whatever the size (its setup and
 * thread pool), and a tick can need over a hundred small encodes, which the
 * self-test measured at 100-800 ms per tick against under 1 ms in software.
 * <ul>
 *   <li>Keyframes (frames read straight from the sprite's own mip images) are
 *       precoded: when the atlas is registered, every keyframe of every animated
 *       sprite is encoded once in the background with the atlas's own encoder and
 *       quality, all of a mip level's in one call ({@link #precodeKeyframes}).
 *       Steady-state cost is one small GPU upload per sprite per frame change, no
 *       CPU encoding. A keyframe shown before the precode lands is encoded in
 *       software meanwhile, and replaced.</li>
 *   <li>Interpolated frames (blended per tick) and deep mip levels (patched per
 *       frame change, mixed with neighbouring sprites) are looked up by content:
 *       animations repeat, so each distinct image is encoded once (in software,
 *       and in the background with the native encoder for interpolated frames),
 *       then only hashed.</li>
 * </ul>
 *
 * <p>Up to 1.21.10 frames arrive through {@code SpriteContents.upload}
 * ({@link #upload}); from 1.21.11 Tessera draws them itself, replacing
 * {@code TextureAtlas.uploadAnimationFrames} for compressed atlases
 * ({@link #drawFrames}), with the same padding and blending as vanilla's
 * {@code animate_sprite} shaders.
 *
 * <p>Render thread only.
 */
public final class CompressedAnimationUploader {

    private static final Logger LOGGER = LoggerFactory.getLogger("Tessera/Animations");

    /**
     * @param alignedLevel   deepest level at which every animated region is 4x4-block
     *                       aligned (direct per-sprite encode, keyframes cached)
     * @param deepLevels     RGBA copy of each level beyond alignedLevel, patched per
     *                       frame and re-encoded only over the touched blocks; owned by
     *                       the mip thread once registered
     * @param deepSources    copies of each animated sprite's images at those levels,
     *                       read by the mip thread (a reload may close the originals)
     * @param spritesByState 1.21.11+: the atlas's animation states and their sprites
     * @param bank           frames kept in VRAM and copied on the GPU (experimental, with
     *                       {@code gpuCompression}), or {@code null}
     * @param deep           the smallest levels' pending frames and compressed blocks
     */
    private record AtlasState(int glId, CompressionPipeline.Target target, int maxLevel, int alignedLevel,
                              Map<Integer, MipChainBuilder.MipLevel> deepLevels,
                              Map<Object, TextureAtlasSprite> spritesByState,
                              Map<SpriteContents, PixelSource> deepSources, GpuFrameBank bank,
                              DeepBatch deep) {
    }

    /** One frame's pixels, as ABGR, at {@code (x, y)} of its region at a mip level. */
    @FunctionalInterface
    private interface FramePixels {
        int abgr(int level, int x, int y);
    }

    /** Compressed atlases. */
    private static final Map<TextureAtlas, AtlasState> COMPRESSED_ATLASES = new IdentityHashMap<>();
    /**
     * 1.21.11+: the content key ({@link #contentHash}) of each blended frame per mip level, by sprite and
     * {@link #blendKey}. Lets the render thread find a blend in {@link #BY_CONTENT} without building its
     * pixels and hashing them every tick. Filled by the precode and by the first draw of each blend.
     */
    private static final Map<SpriteContents, Map<Long, long[]>> BLEND_KEYS = new IdentityHashMap<>();
    /** Encoded keyframes per sprite: key = frame column << 32 | frame row (or pixel offsets), value = blocks per mip level. */
    private static final Map<SpriteContents, Map<Long, ByteBuffer[]>> KEYFRAME_CACHE = new IdentityHashMap<>();

    /** Atlas currently inside cycleAnimationFrames (up to 1.21.10), or null when it is not compressed. */
    private static AtlasState active;
    /** Atlas whose texture and unpack state are set for this tick's uploads (see {@link #prepareUploads}). */
    private static AtlasState prepared;

    /**
     * Patches the smallest mip levels (those past alignedLevel, where a sprite shares 4x4 blocks with
     * its neighbours) off the render thread: keeps their RGBA copies, re-encodes the touched blocks and
     * hands the blocks back for upload on the next tick. One thread, so patches apply in order.
     */
    private static final ExecutorService MIPS = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "Tessera animation mips");
        thread.setDaemon(true);
        thread.setPriority(Thread.NORM_PRIORITY - 1);
        return thread;
    });
    /**
     * Ticks between sends of the smallest levels' frames to the mip thread: waking it costs the
     * render thread a system call, and those levels are only seen from afar.
     */
    private static final int DEEP_BATCH_TICKS = 4;
    /**
     * Unchanged block rows between two changed runs of a level that are still sent with them, as one
     * upload: below this a second call costs more than the rows it saves.
     */
    private static final int DEEP_ROW_GAP = 4;
    /** Band buffers kept for reuse per atlas. */
    private static final int MAX_SPARE_BANDS = 8;

    /** The smallest levels of one atlas: frames waiting to be patched, and the levels as the GPU has them. */
    private static final class DeepBatch {
        /** The latest frame of each sprite region since the last send; render thread only. */
        final Map<Long, DeepRequest> waiting = new LinkedHashMap<>();
        /** Ticks with frames waiting since the last send; render thread only. */
        int ticks;
        /** Each smallest level's compressed blocks, built on first use; mip thread only. */
        final Map<Integer, ByteBuffer> blocks = new HashMap<>();
        /**
         * Band buffers already uploaded, for the next send: taken on the mip thread, given back on the
         * render thread once {@code glCompressedTexSubImage2D} has copied them.
         */
        final ConcurrentLinkedQueue<ByteBuffer> spare = new ConcurrentLinkedQueue<>();

        /** A spare band buffer of at least {@code bytes}, or a new one (mip thread). */
        ByteBuffer band(int bytes) {
            for (int i = spare.size(); i > 0; i--) {
                ByteBuffer buffer = spare.poll();
                if (buffer == null) {
                    break;
                }
                if (buffer.capacity() >= bytes) {
                    return buffer.clear().limit(bytes);
                }
                spare.offer(buffer);
            }
            return ByteBuffer.allocateDirect(bytes).order(ByteOrder.LITTLE_ENDIAN);
        }

        /** Gives back an uploaded band buffer (render thread). */
        void giveBack(ByteBuffer buffer) {
            if (spare.size() < MAX_SPARE_BANDS) {
                spare.offer(buffer);
            }
        }
    }
    /** Blocks by rectangle content, mip thread only (least recently used out first). */
    private static final Map<Long, ByteBuffer> DEEP_BY_CONTENT = new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, ByteBuffer> eldest) {
            return size() > MAX_BY_CONTENT;
        }
    };
    /** Mip thread only. */
    private static ByteBuffer deepScratch = ByteBuffer.allocateDirect(16 * 16 * 4).order(ByteOrder.LITTLE_ENDIAN);

    /**
     * One frame whose smallest levels need patching: its region at level 0 and how to read its
     * pixels from the sprite's copied images.
     */
    private record DeepRequest(SpriteContents owner, java.util.function.Function<PixelSource, FramePixels> frame,
                               int x, int y, int width, int height, int frameLevels) {
    }

    /** One patched rectangle, ready to upload. */
    private record DeepUpload(int level, int x, int y, int width, int height, ByteBuffer blocks) {
    }
    private static ByteBuffer rgbaScratch = ByteBuffer.allocateDirect(16 * 16 * 4).order(ByteOrder.LITTLE_ENDIAN);

    /** Best-quality keyframe encodes, one at a time, never on the render thread. */
    private static final ExecutorService BEST_QUALITY = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "Tessera animation encoder");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        return thread;
    });
    /**
     * Finished background work (precoded frames, best-quality keyframes), applied on the render thread.
     * Celeris's lock-free single-consumer queue: the render thread drains it without blocking and
     * without a node allocation per item. If it is ever full, the overflow queue keeps the work.
     */
    private static final AsyncResultQueue<Runnable> UPGRADES = new AsyncResultQueue<>(4096);
    private static final ConcurrentLinkedQueue<Runnable> UPGRADE_OVERFLOW = new ConcurrentLinkedQueue<>();
    private static final AtomicInteger PENDING_UPGRADES = new AtomicInteger();

    /** Encoded blocks by image content (see {@link #encodeByContent}); render thread only, least recently used out first. */
    private static final int MAX_BY_CONTENT = 65536;
    private static final Map<Long, ByteBuffer> BY_CONTENT = new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, ByteBuffer> eldest) {
            return size() > MAX_BY_CONTENT;
        }
    };

    /** One aligned mip level of a new keyframe, kept for its best-quality re-encode. */
    private record LevelInput(ByteBuffer rgba, int width, int height) {
    }

    private CompressedAnimationUploader() {
    }

    /** Compressed uploads registered so far (self-test: it settles once this stops changing). */
    private static int registrations;

    public static int registrations() {
        return registrations;
    }

    /**
     * Every atlas currently compressed, with its block format (self-test). Read
     * here rather than from {@code AtlasCompressEvent.Post}: on 1.21 - 1.21.1 the
     * first reload runs during mod loading, and NeoForge drops events posted to
     * its bus before loading ends, so early atlases' events never arrive.
     */
    public static Map<TextureAtlas, CompressionPipeline.Target> compressedAtlases() {
        Map<TextureAtlas, CompressionPipeline.Target> out = new LinkedHashMap<>();
        COMPRESSED_ATLASES.forEach((atlas, state) -> out.put(atlas, state.target()));
        return out;
    }

    /** Keyframes still waiting for their best-quality re-encode (self-test). */
    public static int pendingUpgrades() {
        return PENDING_UPGRADES.get();
    }

    /** Animations of an atlas can be kept running once it is compressed. */
    public static boolean canAnimate(boolean hasAnimatedSprites) {
        return !hasAnimatedSprites || !TesseraCompat.GPU_ANIMATION || AnimationReader.AVAILABLE;
    }

    /**
     * Called after a successful compressed upload of {@code atlas}.
     *
     * @param spritesByState 1.21.11+: {@link TesseraCompat#animatedSprites}
     */
    public static void register(TextureAtlas atlas, int glId, CompressionPipeline.Target target, int maxUploadedLevel,
                                int alignedLevel, Map<Integer, MipChainBuilder.MipLevel> deepLevels,
                                Map<Object, TextureAtlasSprite> spritesByState) {
        // Deep levels without a CPU copy cannot be updated; cap there so a
        // missing copy degrades to fewer levels, never to stale/garbled ones.
        int maxLevel = alignedLevel;
        while (maxLevel < maxUploadedLevel && deepLevels.containsKey(maxLevel + 1)) {
            maxLevel++;
        }
        GpuFrameBank bank = Config.get(Config.GPU_COMPRESSION)
                ? GpuFrameBank.create(atlas.location().toString(), target) : null;
        AtlasState state = new AtlasState(glId, target, maxLevel, alignedLevel, deepLevels, spritesByState,
                deepSources(atlas, alignedLevel, maxLevel), bank, new DeepBatch());
        AtlasState previous = COMPRESSED_ATLASES.put(atlas, state);
        if (previous != null && previous.bank() != null) {
            previous.bank().release();
        }
        registrations++;
        precodeKeyframes(atlas, state);
        if (TesseraCompat.GPU_ANIMATION) {
            // The compressed chain holds frame 0 of every animated sprite; show
            // the frame each animation is on now.
            drawFrames(atlas, true);
        }
    }

    /**
     * Called when vanilla re-uploads the atlas as RGBA (reload). Drops the whole
     * keyframe cache: sprites from the previous reload are dead, and the cache is
     * small enough that rebuilding it lazily is cheaper than tracking ownership.
     */
    public static void unregister(TextureAtlas atlas) {
        AtlasState removed = COMPRESSED_ATLASES.remove(atlas);
        if (removed != null) {
            if (removed.bank() != null) {
                removed.bank().release();
            }
            epoch++;
            prepared = null;
            KEYFRAME_CACHE.clear();
            BY_CONTENT.clear();
            BLEND_KEYS.clear();
        }
    }

    /**
     * Self-test: the RGBA copies of the smallest levels of {@code atlas} (levels past the aligned
     * ones), which the mip thread keeps patching; empty if it has none.
     */
    public static Map<Integer, MipChainBuilder.MipLevel> deepLevelCopies(TextureAtlas atlas) {
        AtlasState state = COMPRESSED_ATLASES.get(atlas);
        Map<Integer, MipChainBuilder.MipLevel> copies = new java.util.TreeMap<>();
        if (state != null) {
            for (int level = state.alignedLevel() + 1; level <= state.maxLevel(); level++) {
                MipChainBuilder.MipLevel copy = state.deepLevels().get(level);
                if (copy != null) {
                    copies.put(level, copy);
                }
            }
        }
        return copies;
    }

    /** VRAM held by the experimental GPU frame banks (render thread). */
    public static long frameBankBytes() {
        return GpuFrameBank.totalBytes();
    }

    /** Queues background work for the render thread (any thread). */
    private static void handOff(Runnable work) {
        if (!UPGRADES.offer(work)) {
            UPGRADE_OVERFLOW.add(work);
        }
    }

    /** Bumped whenever the keyframe cache is dropped, so a precode finishing late is discarded. */
    private static int epoch;

    /** One keyframe region at one mip level, read on the render thread for the precode. */
    private record PrecodeRegion(SpriteContents owner, long key, int levels, ByteBuffer rgba, int width, int height) {

        /** A blended frame: looked up by content ({@code key} is its content hash), not per sprite. */
        boolean blend() {
            return owner == null;
        }
    }

    /** At most this many blended frames are precoded per atlas (a 22-frame prismarine blends into 6600). */
    private static final int MAX_PRECODED_BLENDS = 16384;

    /** Pixels of a sprite's mip images, ABGR, by level and coordinates. */
    @FunctionalInterface
    private interface PixelSource {
        int abgr(int level, int x, int y);
    }

    /** One animated sprite as the precode needs it, copied on the render thread. */
    private record SpriteSnapshot(SpriteContents owner, int width, int height, int padding, int columns, int frameLevels,
                                  int[] uniqueFrames, AnimationReader.Timeline timeline, int[][] pixels, int[] imageWidths) {

        PixelSource source() {
            return (level, x, y) -> pixels[level][y * imageWidths[level] + x];
        }
    }

    /**
     * Encodes every keyframe of every animated sprite in the atlas once, and every blended step of
     * the interpolated ones, in the background with the atlas's own encoder, so frames are written
     * from caches from the start instead of being encoded on the render thread when first shown.
     * Only the sprites' images are copied here (a later reload may close them); building the frames,
     * hashing and encoding happen on the background thread. Each mip level's frames are stacked in one
     * image and encoded in a single call, since the native encoder's fixed cost per call outweighs one
     * small frame.
     */
    private static void precodeKeyframes(TextureAtlas atlas, AtlasState state) {
        long readStart = System.nanoTime();
        int levels = state.alignedLevel();
        Map<SpriteContents, Object> stateBySprite = new IdentityHashMap<>();
        state.spritesByState().forEach((animation, sprite) -> stateBySprite.put(sprite.contents(), animation));
        List<SpriteSnapshot> sprites = new ArrayList<>();
        for (TextureAtlasSprite sprite : TesseraCompat.textures(atlas)) {
            SpriteContents contents = sprite.contents();
            if (!TesseraCompat.isAnimated(contents)) {
                continue;
            }
            NativeImage[] images = contents.byMipLevel;
            int frameLevels = Math.min(levels, images.length - 1);
            int[][] pixels = new int[frameLevels + 1][];
            int[] widths = new int[frameLevels + 1];
            for (int level = 0; level <= frameLevels; level++) {
                NativeImage image = images[level];
                int iw = image.getWidth();
                int ih = image.getHeight();
                int[] copy = new int[iw * ih];
                for (int y = 0; y < ih; y++) {
                    for (int x = 0; x < iw; x++) {
                        copy[y * iw + x] = TesseraCompat.abgr(image, x, y);
                    }
                }
                pixels[level] = copy;
                widths[level] = iw;
            }
            Object animation = stateBySprite.get(contents);
            AnimationReader.Timeline timeline = TesseraCompat.GPU_ANIMATION
                    ? (animation != null && AnimationReader.AVAILABLE ? AnimationReader.timeline(animation) : null)
                    : AnimationReader.legacyTimeline(contents);
            sprites.add(new SpriteSnapshot(contents, contents.width(), contents.height(), TesseraCompat.padding(sprite),
                    Math.max(1, images[0].getWidth() / contents.width()), frameLevels,
                    TesseraCompat.uniqueFrames(contents), timeline, pixels, widths));
        }
        if (sprites.isEmpty()) {
            return;
        }
        long readMillis = (System.nanoTime() - readStart) / 1_000_000;
        int startEpoch = epoch;
        CompressionPipeline.Target target = state.target();
        int quality = Config.get(Config.COMPRESSION_QUALITY);
        PENDING_UPGRADES.incrementAndGet();
        try {
            BEST_QUALITY.execute(() -> {
                try {
                    long start = System.nanoTime();
                    List<List<PrecodeRegion>> byLevel = new ArrayList<>();
                    List<java.util.Set<Long>> blendSeen = new ArrayList<>();
                    for (int level = 0; level <= levels; level++) {
                        byLevel.add(new ArrayList<>());
                        blendSeen.add(new java.util.HashSet<>());
                    }
                    int[] counts = new int[2]; // keyframes, blends
                    // Blend keys found on the way, recorded on the render thread with the blocks.
                    List<BlendKey> blendKeys = new ArrayList<>();
                    for (SpriteSnapshot sprite : sprites) {
                        collectRegions(sprite, target, byLevel, blendSeen, counts, blendKeys);
                    }
                    List<ByteBuffer[]> encoded = new ArrayList<>();
                    for (int level = 0; level < byLevel.size(); level++) {
                        encoded.add(encodeStacked(byLevel.get(level), target, quality, level));
                    }
                    LOGGER.info("Precoded {} animation frames and {} blended frames ({} mip level(s)): {} ms copying"
                                    + " sprite images on the render thread, {} ms building and encoding in the background.",
                            counts[0], counts[1], byLevel.size(), readMillis, (System.nanoTime() - start) / 1_000_000);
                    handOff(() -> {
                        if (epoch != startEpoch) {
                            return; // a reload dropped these sprites
                        }
                        for (BlendKey blend : blendKeys) {
                            blendContentKeys(blend.owner(), blend.key(), blend.levels())[blend.level()] = blend.content();
                        }
                        for (int level = 0; level < byLevel.size(); level++) {
                            List<PrecodeRegion> regions = byLevel.get(level);
                            ByteBuffer[] blocks = encoded.get(level);
                            for (int i = 0; i < regions.size(); i++) {
                                if (blocks[i] == null) {
                                    continue;
                                }
                                PrecodeRegion region = regions.get(i);
                                if (region.blend()) {
                                    BY_CONTENT.put(region.key(), blocks[i]);
                                    continue;
                                }
                                Map<Long, ByteBuffer[]> spriteCache =
                                        KEYFRAME_CACHE.computeIfAbsent(region.owner(), c -> new HashMap<>());
                                ByteBuffer[] chain = spriteCache.get(region.key());
                                chain = chain != null ? chain.clone() : new ByteBuffer[region.levels() + 1];
                                if (level < chain.length) {
                                    chain[level] = blocks[i];
                                    spriteCache.put(region.key(), chain);
                                }
                            }
                        }
                    });
                } catch (RuntimeException e) {
                    LOGGER.warn("Precoding animation frames failed; they are encoded when first shown", e);
                } finally {
                    PENDING_UPGRADES.decrementAndGet();
                }
            });
        } catch (RejectedExecutionException e) {
            PENDING_UPGRADES.decrementAndGet();
        }
    }

    /** Adds one sprite's keyframes (by sprite and frame) and blended frames (by content) to {@code byLevel}. */
    private static void collectRegions(SpriteSnapshot sprite, CompressionPipeline.Target target,
                                       List<List<PrecodeRegion>> byLevel, List<java.util.Set<Long>> blendSeen,
                                       int[] counts, List<BlendKey> blendKeys) {
        PixelSource source = sprite.source();
        int w = sprite.width();
        int h = sprite.height();
        int padding = sprite.padding();
        for (int frame : sprite.uniqueFrames()) {
            int column = frame % sprite.columns();
            int row = frame / sprite.columns();
            long key;
            FramePixels pixels;
            if (TesseraCompat.GPU_ANIMATION) {
                key = ((long) column << 32) | (row & 0xFFFFFFFFL);
                pixels = gpuFramePixels(source, w, h, padding,
                        new AnimationReader.Frame(column, row, column, row, 0.0F, false));
            } else {
                int frameX = column * w;
                int frameY = row * h;
                key = ((long) frameX << 32) | (frameY & 0xFFFFFFFFL);
                pixels = (level, px, py) -> source.abgr(level, (frameX >> level) + px, (frameY >> level) + py);
            }
            for (int level = 0; level <= sprite.frameLevels(); level++) {
                int rw = (w + 2 * padding) >> level;
                int rh = (h + 2 * padding) >> level;
                if (rw < 1 || rh < 1 || ((rw | rh) & 3) != 0) {
                    break;
                }
                byLevel.get(level).add(new PrecodeRegion(sprite.owner(), key, sprite.frameLevels(),
                        newFrameRgba(pixels, level, rw, rh), rw, rh));
            }
            counts[0]++;
        }

        AnimationReader.Timeline timeline = sprite.timeline();
        if (timeline == null || !timeline.interpolated()) {
            return;
        }
        int[] indices = timeline.indices();
        for (int i = 0; i < indices.length && counts[1] < MAX_PRECODED_BLENDS; i++) {
            int current = indices[i];
            int next = indices[(i + 1) % indices.length];
            int time = Math.max(1, timeline.times()[i]);
            if (!TesseraCompat.GPU_ANIMATION && current == next) {
                continue; // vanilla uploads nothing between two equal frames
            }
            for (int subFrame = 0; subFrame < time && counts[1] < MAX_PRECODED_BLENDS; subFrame++) {
                FramePixels pixels;
                long blend = 0L;
                if (TesseraCompat.GPU_ANIMATION) {
                    float progress = (int) ((float) subFrame / time * 1000.0F) / 1000.0F;
                    AnimationReader.Frame frame = new AnimationReader.Frame(
                            current % timeline.rowSize(), current / timeline.rowSize(),
                            next % timeline.rowSize(), next / timeline.rowSize(), progress, true);
                    pixels = gpuFramePixels(source, w, h, padding, frame);
                    blend = blendKey(frame);
                } else {
                    pixels = legacyBlendPixels(source, w, h, current, next, timeline.rowSize(), subFrame, time);
                }
                for (int level = 0; level <= sprite.frameLevels(); level++) {
                    int rw = (w + 2 * padding) >> level;
                    int rh = (h + 2 * padding) >> level;
                    if (rw < 1 || rh < 1 || ((rw | rh) & 3) != 0) {
                        break;
                    }
                    ByteBuffer rgba = newFrameRgba(pixels, level, rw, rh);
                    long hash = contentHash(rgba, rw, rh, target);
                    if (blend != 0L) {
                        blendKeys.add(new BlendKey(sprite.owner(), blend, sprite.frameLevels(), level, hash));
                    }
                    if (blendSeen.get(level).add(hash)) {
                        byLevel.get(level).add(new PrecodeRegion(null, hash, sprite.frameLevels(), rgba, rw, rh));
                    }
                }
                counts[1]++;
            }
        }
    }

    /**
     * Encodes regions (each a multiple of 4 texels on both sides) stacked in one image, and returns
     * each region's blocks; an entry is {@code null} where encoding failed.
     */
    private static ByteBuffer[] encodeStacked(List<PrecodeRegion> regions, CompressionPipeline.Target target,
                                              int quality, int level) {
        ByteBuffer[] out = new ByteBuffer[regions.size()];
        if (regions.isEmpty()) {
            return out;
        }
        int width = 0;
        int height = 0;
        for (PrecodeRegion region : regions) {
            width = Math.max(width, region.width());
            height += region.height();
        }
        ByteBuffer strip = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.LITTLE_ENDIAN);
        int y = 0;
        for (PrecodeRegion region : regions) {
            ByteBuffer src = region.rgba().duplicate().order(ByteOrder.LITTLE_ENDIAN);
            for (int row = 0; row < region.height(); row++) {
                int from = row * region.width() * 4;
                strip.put(((y + row) * width) * 4, src, from, region.width() * 4);
            }
            y += region.height();
        }
        ByteBuffer blocks = CompressionPipeline.compressBlocking("animation frames", strip, width, height, target,
                quality, Math.max(1, level));
        if (blocks == null) {
            return out;
        }
        int bytesPerBlock = target.bytesPerBlock();
        int stripBlocksX = width / 4;
        int blockRow = 0;
        for (int i = 0; i < regions.size(); i++) {
            PrecodeRegion region = regions.get(i);
            int blocksX = region.width() / 4;
            int blocksY = region.height() / 4;
            ByteBuffer slice = ByteBuffer.allocateDirect(blocksX * blocksY * bytesPerBlock).order(ByteOrder.LITTLE_ENDIAN);
            for (int by = 0; by < blocksY; by++) {
                int from = ((blockRow + by) * stripBlocksX) * bytesPerBlock;
                slice.put(slice.position(), blocks, blocks.position() + from, blocksX * bytesPerBlock);
                slice.position(slice.position() + blocksX * bytesPerBlock);
            }
            out[i] = slice.flip();
            blockRow += blocksY;
        }
        return out;
    }

    public static void beginAnimationCycle(TextureAtlas atlas) {
        active = COMPRESSED_ATLASES.get(atlas);
        prepared = null;
    }

    public static void endAnimationCycle() {
        if (active != null) {
            flushDeepRequests(active);
        }
        active = null;
        prepared = null;
    }

    /**
     * Once per tick and atlas rather than per sprite: applies finished background work, binds the
     * atlas and resets the unpack state its uploads need.
     */
    private static void prepareUploads(AtlasState state) {
        if (prepared == state) {
            return;
        }
        UPGRADES.drainTo(Runnable::run, UPGRADES.capacity());
        for (Runnable upgrade; (upgrade = UPGRADE_OVERFLOW.poll()) != null; ) {
            upgrade.run();
        }
        TesseraCompat.bindTexture(state.glId());
        TesseraCompat.resetUnpackState();
        prepared = state;
    }

    /**
     * Writes cached blocks over a region of the bound atlas: copied from the GPU frame bank when the
     * atlas has one, otherwise uploaded (a duplicate: cached buffers are shared and never moved).
     */
    private static void uploadBlocks(AtlasState state, int level, int x, int y, int w, int h, int glFormat,
                                     ByteBuffer blocks) {
        if (TesseraSelfTest.ENABLED) {
            EncodeStats.uploads++;
        }
        if (state.bank() != null && state.bank().copy(state.glId(), level, x, y, w, h, blocks)) {
            return;
        }
        GL13.glCompressedTexSubImage2D(GL11.GL_TEXTURE_2D, level, x, y, w, h, glFormat, blocks.duplicate());
    }

    /**
     * Highest mip level at which every animated sprite region stays aligned to
     * 4x4 blocks (position and size), so compressed sub-image updates are legal.
     */
    public static int alignedMipLevels(int requestedMaxLevel, int[] animatedRects) {
        int level = requestedMaxLevel;
        for (int i = 0; i < animatedRects.length; i += 4) {
            int x = animatedRects[i], y = animatedRects[i + 1], w = animatedRects[i + 2], h = animatedRects[i + 3];
            while (level > 0 && !isBlockAligned(x >> level, y >> level, w >> level, h >> level)) {
                level--;
            }
        }
        return level;
    }

    private static boolean isBlockAligned(int x, int y, int w, int h) {
        return w > 0 && h > 0 && ((x | y | w | h) & 3) == 0;
    }

    /**
     * Up to 1.21.10: replacement for {@code SpriteContents.upload} on a compressed atlas.
     *
     * @return false when the active atlas is not compressed (caller runs vanilla's upload)
     */
    public static boolean upload(SpriteContents contents, int x, int y, int frameX, int frameY, NativeImage[] images) {
        AtlasState state = active;
        if (state == null) {
            return false;
        }
        long drawStart = TesseraSelfTest.ENABLED ? System.nanoTime() : 0L;
        boolean keyframe = images == contents.byMipLevel;
        long key = ((long) frameX << 32) | (frameY & 0xFFFFFFFFL);
        FramePixels pixels = (level, px, py) -> TesseraCompat.abgr(images[level], (frameX >> level) + px, (frameY >> level) + py);
        writeFrame(state, contents, keyframe, keyframe, key, 0L, x, y, contents.width(), contents.height(),
                images.length - 1, pixels,
                source -> (level, px, py) -> source.abgr(level, (frameX >> level) + px, (frameY >> level) + py));
        if (TesseraSelfTest.ENABLED) {
            EncodeStats.addDraw(System.nanoTime() - drawStart);
        }
        return true;
    }

    /**
     * 1.21.11+: replacement for {@code TextureAtlas.uploadAnimationFrames} on a
     * compressed atlas. Draws what {@code SpriteContents.AnimationState.drawToAtlas}
     * would: the current frame (blended with the next one for interpolated
     * sprites) over the sprite's padded region, edge pixels repeated into the
     * padding, at every mip level.
     *
     * @param all every animation, not only those vanilla would redraw this tick
     * @return false when the atlas is not compressed (caller runs vanilla's passes)
     */
    public static boolean drawFrames(TextureAtlas atlas, boolean all) {
        AtlasState state = COMPRESSED_ATLASES.get(atlas);
        if (state == null) {
            return false;
        }
        long drawStart = TesseraSelfTest.ENABLED ? System.nanoTime() : 0L;
        prepared = null; // vanilla may have bound something else since the last tick
        prepareUploads(state);
        for (Map.Entry<Object, TextureAtlasSprite> entry : state.spritesByState().entrySet()) {
            Object animation = entry.getKey();
            if (!all && !AnimationReader.needsToDraw(animation)) {
                continue;
            }
            TextureAtlasSprite sprite = entry.getValue();
            AnimationReader.Frame frame = AnimationReader.read(animation);
            SpriteContents contents = sprite.contents();
            NativeImage[] images = contents.byMipLevel;
            int w = contents.width();
            int h = contents.height();
            int padding = TesseraCompat.padding(sprite);
            FramePixels pixels = gpuFramePixels((level, px, py) -> TesseraCompat.abgr(images[level], px, py),
                    w, h, padding, frame);
            long key = ((long) frame.column() << 32) | (frame.row() & 0xFFFFFFFFL);
            // Interpolated sprites never draw a pure keyframe here: their smallest levels follow the
            // start of each frame (progress 0) instead.
            int column = frame.column();
            int row = frame.row();
            // The smallest levels show the frame itself (an interpolated one at progress 0).
            writeFrame(state, contents, !frame.interpolated(), !frame.interpolated() || frame.progress() == 0.0F,
                    key, frame.interpolated() ? blendKey(frame) : 0L, sprite.getX(), sprite.getY(),
                    w + 2 * padding, h + 2 * padding, images.length - 1, pixels,
                    source -> gpuFramePixels(source, w, h, padding,
                            new AnimationReader.Frame(column, row, column, row, 0.0F, false)));
        }
        flushDeepRequests(state);
        prepared = null;
        if (TesseraSelfTest.ENABLED) {
            EncodeStats.addDraw(System.nanoTime() - drawStart);
        }
        return true;
    }

    /**
     * 1.21.11+: what {@code animate_sprite} draws over a sprite's padded region for {@code frame}.
     * Frame textures are w x h with the sprite's mip chain; animate_sprite.vsh stretches them over the
     * padded region, the clamp-to-edge sampler fills the ring. Shared by drawing and precoding, so a
     * precoded frame hashes the same as the one drawn.
     */
    private static FramePixels gpuFramePixels(PixelSource images, int w, int h, int padding, AnimationReader.Frame frame) {
        float padU = (float) padding / w;
        float padV = (float) padding / h;
        // Region pixel -> frame texel per level, worked out once per frame instead of per texel.
        int[][] mapX = new int[MAX_LEVELS][];
        int[][] mapY = new int[MAX_LEVELS][];
        return (level, px, py) -> {
            int[] mx = mapX[level];
            if (mx == null) {
                mx = mapX[level] = texelMap((w + 2 * padding) >> level, padU, Math.max(1, w >> level));
            }
            int[] my = mapY[level];
            if (my == null) {
                my = mapY[level] = texelMap((h + 2 * padding) >> level, padV, Math.max(1, h >> level));
            }
            int tx = mx[px];
            int ty = my[py];
            int current = images.abgr(level, ((frame.column() * w) >> level) + tx, ((frame.row() * h) >> level) + ty);
            if (!frame.interpolated()) {
                return current;
            }
            int next = images.abgr(level, ((frame.nextColumn() * w) >> level) + tx, ((frame.nextRow() * h) >> level) + ty);
            return mix(current, next, frame.progress());
        };
    }

    /**
     * Up to 1.21.10: the blended frame vanilla's {@code InterpolationData} builds at {@code subFrame}
     * of a frame lasting {@code time} ticks: each colour channel {@code (int) (d * current + (1 - d) * next)}
     * with {@code d = 1 - subFrame / time}, alpha from the current frame.
     */
    private static FramePixels legacyBlendPixels(PixelSource images, int w, int h, int current, int next,
                                                 int rowSize, int subFrame, int time) {
        double d = 1.0 - (double) subFrame / time;
        int cx = (current % rowSize) * w, cy = (current / rowSize) * h;
        int nx = (next % rowSize) * w, ny = (next / rowSize) * h;
        return (level, px, py) -> {
            int a = images.abgr(level, (cx >> level) + px, (cy >> level) + py);
            int b = images.abgr(level, (nx >> level) + px, (ny >> level) + py);
            int out = a & 0xFF000000;
            for (int shift = 0; shift <= 16; shift += 8) {
                int channel = (int) (d * ((a >>> shift) & 0xFF) + (1.0 - d) * ((b >>> shift) & 0xFF));
                out |= (channel & 0xFF) << shift;
            }
            return out;
        };
    }

    /** Mip levels a frame can have (Minecraft stops at 4; this leaves room). */
    private static final int MAX_LEVELS = 16;

    /** {@link #texel} for every pixel of a region. */
    private static int[] texelMap(int region, float padding, int size) {
        int[] map = new int[Math.max(0, region)];
        for (int i = 0; i < map.length; i++) {
            map[i] = texel(i, region, padding, size);
        }
        return map;
    }

    /** animate_sprite.vsh plus a NEAREST clamp-to-edge sampler: pixel i of a padded region to a frame texel. */
    private static int texel(int i, int region, float padding, int size) {
        float uv = (i + 0.5F) / region;
        float tex = uv + padding * (uv * 2.0F - 1.0F);
        int t = (int) Math.floor(tex * size);
        return Math.max(0, Math.min(size - 1, t));
    }

    /** animate_sprite_interpolate.fsh: mix(current, next, progress) per channel, stored as 8-bit. */
    private static int mix(int current, int next, float progress) {
        int out = 0;
        for (int shift = 0; shift < 32; shift += 8) {
            float a = ((current >>> shift) & 0xFF) / 255.0F;
            float b = ((next >>> shift) & 0xFF) / 255.0F;
            int c = Math.round((a * (1.0F - progress) + b * progress) * 255.0F);
            out |= Math.max(0, Math.min(255, c)) << shift;
        }
        return out;
    }

    /**
     * Writes one frame over the region at {@code (x, y)} of size {@code width x height}
     * (level 0; halved per level) at every compressed level the frame has.
     */
    /**
     * @param updateDeep also patch the levels past the aligned ones (keyframes and frame starts only)
     */
    private static void writeFrame(AtlasState state, SpriteContents owner, boolean cacheable, boolean updateDeep, long cacheKey,
                                   long blendKey, int x, int y, int width, int height, int frameLevels, FramePixels pixels,
                                   java.util.function.Function<PixelSource, FramePixels> deepFrame) {
        prepareUploads(state);
        ByteBuffer[] cached = null;
        Map<Long, ByteBuffer[]> spriteCache = null;
        if (cacheable) {
            spriteCache = KEYFRAME_CACHE.computeIfAbsent(owner, c -> new HashMap<>());
            cached = spriteCache.get(cacheKey);
        }
        int levels = Math.min(state.maxLevel(), frameLevels);
        ByteBuffer[] encoded = cached != null ? cached : new ByteBuffer[levels + 1];
        int glFormat = state.target().glFormat();
        // A new keyframe: its pixels per level, for the best-quality re-encode.
        LevelInput[] inputs = cacheable && cached == null && TesseraRuntime.isNativeActive()
                ? new LevelInput[levels + 1] : null;

        for (int level = 0; level <= levels; level++) {
            int w = width >> level;
            int h = height >> level;
            if (w < 1 || h < 1) {
                break; // same guard as vanilla's upload
            }
            if (level > state.alignedLevel()) {
                // The smallest levels, where the sprite shares 4x4 blocks with its neighbours, follow
                // keyframes only (a blended step there is a couple of texels), patched on the mip thread.
                if (updateDeep) {
                    state.deep().waiting.put(((long) x << 32) | (y & 0xFFFFFFFFL),
                            new DeepRequest(owner, deepFrame, x, y, width, height, frameLevels));
                }
                break; // every level from here on is one of them
            }
            if (!isBlockAligned(x >> level, y >> level, w, h)) {
                break; // unreachable: alignedLevel guarantees alignment up to here
            }
            ByteBuffer blocks = encoded[level];
            if (blocks == null) {
                if (!cacheable) {
                    // Interpolated: not a keyframe, but the same blend comes back every cycle. Known
                    // blends are found by their frame and progress, without building their pixels.
                    if (TesseraSelfTest.ENABLED) {
                        EncodeStats.blendLookups++;
                    }
                    long[] contentKeys = blendKey != 0L ? blendContentKeys(owner, blendKey, frameLevels) : null;
                    ByteBuffer known = contentKeys != null && contentKeys[level] != 0L ? BY_CONTENT.get(contentKeys[level]) : null;
                    if (known != null) {
                        blocks = known;
                        if (TesseraSelfTest.ENABLED) {
                            EncodeStats.found++;
                            EncodeStats.foundByFrame++;
                        }
                    } else {
                        ByteBuffer rgba = frameRgba(pixels, level, w, h);
                        long hash = contentHash(rgba, w, h, state.target());
                        if (contentKeys != null) {
                            contentKeys[level] = hash;
                        }
                        long foundBefore = EncodeStats.found;
                        blocks = encodeByContent(hash, rgba, w, h, state.target(), true);
                        if (TesseraSelfTest.ENABLED && EncodeStats.found == foundBefore) {
                            EncodeStats.blendMisses++;
                        }
                    }
                } else {
                    ByteBuffer rgba = frameRgba(pixels, level, w, h);
                    if (inputs != null) {
                        inputs[level] = new LevelInput(copyOf(rgba), w, h);
                    }
                    if (TesseraSelfTest.ENABLED) {
                        EncodeStats.keyframeMisses++;
                    }
                    blocks = encodeFast(rgba, w, h, state.target());
                    encoded[level] = blocks;
                }
            }
            uploadBlocks(state, level, x >> level, y >> level, w, h, glFormat, blocks);
        }
        if (cacheable && cached == null) {
            spriteCache.put(cacheKey, encoded);
            if (inputs != null) {
                encodeBestLater(spriteCache, cacheKey, encoded, inputs, state.target());
            }
        }
    }

    /**
     * Re-encodes a new keyframe with the atlas's own encoder on the background
     * thread; the next frame drawn on the render thread puts it in the cache,
     * unless the entry changed meanwhile (a reload dropped the cache).
     */
    private static void encodeBestLater(Map<Long, ByteBuffer[]> spriteCache, long key, ByteBuffer[] fast,
                                        LevelInput[] inputs, CompressionPipeline.Target target) {
        encodeBestLater(target, inputs, fast, best -> {
            if (spriteCache.get(key) == fast) {
                spriteCache.put(key, best);
            }
        });
    }

    /**
     * Encodes {@code inputs} with the atlas's own encoder on the background thread,
     * then hands {@code apply} the result (levels that failed keep {@code fast}) on
     * the render thread, before the next frame is drawn.
     */
    private static void encodeBestLater(CompressionPipeline.Target target, LevelInput[] inputs, ByteBuffer[] fast,
                                        java.util.function.Consumer<ByteBuffer[]> apply) {
        PENDING_UPGRADES.incrementAndGet();
        try {
            BEST_QUALITY.execute(() -> {
                try {
                    ByteBuffer[] best = fast.clone();
                    int quality = Config.get(Config.COMPRESSION_QUALITY);
                    for (int level = 0; level < inputs.length; level++) {
                        LevelInput in = inputs[level];
                        if (in == null) {
                            continue;
                        }
                        long start = System.nanoTime();
                        ByteBuffer blocks = CompressionPipeline.compressBlocking("animation", in.rgba(), in.width(),
                                in.height(), target, quality, level);
                        if (TesseraSelfTest.ENABLED) {
                            EncodeStats.addBest(System.nanoTime() - start);
                        }
                        if (blocks != null) {
                            best[level] = blocks;
                        }
                    }
                    handOff(() -> apply.accept(best));
                } finally {
                    PENDING_UPGRADES.decrementAndGet();
                }
            });
        } catch (RejectedExecutionException e) {
            PENDING_UPGRADES.decrementAndGet();
        }
    }

    /**
     * Blocks for an image that may have been encoded before: by a 64-bit hash of its
     * pixels, size and format; the cached buffer itself, never to be changed (callers upload a
     * duplicate). A new image is encoded in software now; with
     * {@code best}, the native encoder redoes it in the background and the cache
     * takes that version.
     */
    private static ByteBuffer encodeByContent(ByteBuffer rgba, int w, int h, CompressionPipeline.Target target,
                                             boolean best) {
        return encodeByContent(contentHash(rgba, w, h, target), rgba, w, h, target, best);
    }

    /** {@link #encodeByContent(ByteBuffer, int, int, CompressionPipeline.Target, boolean)} with the key already computed. */
    private static ByteBuffer encodeByContent(long key, ByteBuffer rgba, int w, int h, CompressionPipeline.Target target,
                                             boolean best) {
        ByteBuffer known = BY_CONTENT.get(key);
        if (known != null) {
            if (TesseraSelfTest.ENABLED) {
                EncodeStats.found++;
            }
            return known;
        }
        ByteBuffer input = best && TesseraRuntime.isNativeActive() && (w & 3) == 0 && (h & 3) == 0
                ? copyOf(rgba) : null;
        ByteBuffer blocks = encodeFast(rgba, w, h, target);
        BY_CONTENT.put(key, blocks);
        if (input != null) {
            encodeBestLater(target, new LevelInput[]{new LevelInput(input, w, h)}, new ByteBuffer[]{blocks},
                    better -> {
                        if (BY_CONTENT.get(key) == blocks) {
                            BY_CONTENT.put(key, better[0]);
                        }
                    });
        }
        return blocks;
    }

    private static long contentHash(ByteBuffer rgba, int w, int h, CompressionPipeline.Target target) {
        long hash = 0x9E3779B97F4A7C15L ^ ((long) w << 32 | (long) h << 1 | target.ordinal());
        int base = rgba.position();
        int bytes = w * h * 4;
        int i = 0;
        for (; i + 8 <= bytes; i += 8) {
            hash = mix(hash ^ rgba.getLong(base + i));
        }
        for (; i < bytes; i++) {
            hash = mix(hash ^ (rgba.get(base + i) & 0xFFL));
        }
        return hash;
    }

    /** A blended frame's identity: both frames and the progress between them (never 0). */
    private static long blendKey(AnimationReader.Frame frame) {
        long key = mix(((long) frame.column() << 32) ^ (frame.row() & 0xFFFFFFFFL));
        key = mix(key ^ (((long) frame.nextColumn() << 32) ^ (frame.nextRow() & 0xFFFFFFFFL)));
        key = mix(key ^ Float.floatToIntBits(frame.progress()));
        return key == 0L ? 1L : key;
    }

    /** The content keys of one blend per mip level (0 = not known yet); render thread only. */
    private static long[] blendContentKeys(SpriteContents owner, long blendKey, int frameLevels) {
        long[] keys = BLEND_KEYS.computeIfAbsent(owner, c -> new HashMap<>())
                .computeIfAbsent(blendKey, k -> new long[frameLevels + 1]);
        return keys;
    }

    /** One blend's content key at one level, found by the precode. */
    private record BlendKey(SpriteContents owner, long key, int levels, int level, long content) {
    }

    /** SplitMix64's finalizer: every input bit reaches every output bit. */
    private static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    private static ByteBuffer copyOf(ByteBuffer rgba) {
        ByteBuffer copy = ByteBuffer.allocateDirect(rgba.remaining()).order(ByteOrder.LITTLE_ENDIAN);
        copy.put(rgba.duplicate()).flip();
        return copy;
    }

    /** Copies of each animated sprite's images at the levels past {@code alignedLevel} (render thread). */
    private static Map<SpriteContents, PixelSource> deepSources(TextureAtlas atlas, int alignedLevel, int maxLevel) {
        Map<SpriteContents, PixelSource> sources = new IdentityHashMap<>();
        if (maxLevel <= alignedLevel) {
            return sources;
        }
        for (TextureAtlasSprite sprite : TesseraCompat.textures(atlas)) {
            SpriteContents contents = sprite.contents();
            if (!TesseraCompat.isAnimated(contents)) {
                continue;
            }
            NativeImage[] images = contents.byMipLevel;
            int top = Math.min(maxLevel, images.length - 1);
            int[][] pixels = new int[top + 1][];
            int[] widths = new int[top + 1];
            for (int level = alignedLevel + 1; level <= top; level++) {
                NativeImage image = images[level];
                int iw = image.getWidth();
                int ih = image.getHeight();
                int[] copy = new int[iw * ih];
                for (int y = 0; y < ih; y++) {
                    for (int x = 0; x < iw; x++) {
                        copy[y * iw + x] = TesseraCompat.abgr(image, x, y);
                    }
                }
                pixels[level] = copy;
                widths[level] = iw;
            }
            sources.put(contents, (level, x, y) -> pixels[level][y * widths[level] + x]);
        }
        return sources;
    }

    /** Sends the waiting frames to the mip thread every {@link #DEEP_BATCH_TICKS} ticks (render thread). */
    private static void flushDeepRequests(AtlasState state) {
        DeepBatch batch = state.deep();
        if (batch.waiting.isEmpty() || ++batch.ticks < DEEP_BATCH_TICKS) {
            return;
        }
        batch.ticks = 0;
        List<DeepRequest> requests = List.copyOf(batch.waiting.values());
        batch.waiting.clear();
        try {
            MIPS.execute(() -> patchDeepLevels(state, requests));
        } catch (RejectedExecutionException ignored) {
            // shutting down
        }
    }

    /**
     * Mip thread: writes each frame into the retained RGBA copies of the smallest levels, re-encodes
     * the block-aligned rectangles covering it (neighbouring pixels come from the copy, so they are
     * preserved) and writes them into that level's compressed copy. The render thread then uploads
     * the runs of block rows each level changed in (runs a few rows apart as one band), before its next draw.
     */
    private static void patchDeepLevels(AtlasState state, List<DeepRequest> requests) {
        long start = System.nanoTime();
        List<DeepUpload> uploads = new ArrayList<>();
        List<ByteBuffer> bands = new ArrayList<>();
        Map<Integer, java.util.BitSet> dirtyRows = new HashMap<>();
        int quality = Config.get(Config.COMPRESSION_QUALITY);
        int patches = 0;
        int[] encodes = {0};
        try {
            for (DeepRequest request : requests) {
                PixelSource source = state.deepSources().get(request.owner());
                if (source == null) {
                    continue;
                }
                FramePixels frame = request.frame().apply(source);
                int levels = Math.min(state.maxLevel(), request.frameLevels());
                for (int level = state.alignedLevel() + 1; level <= levels; level++) {
                    int w = request.width() >> level;
                    int h = request.height() >> level;
                    if (w < 1 || h < 1) {
                        break;
                    }
                    DeepUpload rect = patchDeepLevel(state, level, frame, request.x() >> level,
                            request.y() >> level, w, h, quality, encodes);
                    boolean fresh = !state.deep().blocks.containsKey(level);
                    if (!writeIntoLevel(state, rect, quality)) {
                        uploads.add(rect); // no copy of this level: the rectangle on its own
                    } else {
                        java.util.BitSet rows = dirtyRows.computeIfAbsent(level, l -> new java.util.BitSet());
                        // A level just encoded whole goes up whole, so the GPU matches the copy.
                        if (fresh) {
                            rows.set(0, (state.deepLevels().get(level).height() + 3) >> 2);
                        } else {
                            rows.set(rect.y() >> 2, (rect.y() + rect.height() + 3) >> 2);
                        }
                    }
                    patches++;
                }
            }
            // Each run of changed block rows goes up as one band, runs a few rows apart together.
            for (Map.Entry<Integer, java.util.BitSet> dirty : dirtyRows.entrySet()) {
                java.util.BitSet rows = dirty.getValue();
                int first = rows.nextSetBit(0);
                while (first >= 0) {
                    int end = rows.nextClearBit(first);
                    int next = rows.nextSetBit(end);
                    while (next >= 0 && next - end <= DEEP_ROW_GAP) {
                        end = rows.nextClearBit(next);
                        next = rows.nextSetBit(end);
                    }
                    DeepUpload band = levelBand(state, dirty.getKey(), first, end);
                    uploads.add(band);
                    bands.add(band.blocks());
                    first = next;
                }
            }
        } catch (RuntimeException e) {
            LOGGER.warn("Patching the smallest mip levels of animated sprites failed", e);
        }
        if (TesseraSelfTest.ENABLED) {
            EncodeStats.addDeep(patches, encodes[0], System.nanoTime() - start);
        }
        if (uploads.isEmpty()) {
            return;
        }
        handOff(() -> {
            if (!COMPRESSED_ATLASES.containsValue(state)) {
                return; // reloaded meanwhile
            }
            // Runs while the render thread prepares some atlas's uploads, before it binds that atlas.
            TesseraCompat.bindTexture(state.glId());
            TesseraCompat.resetUnpackState();
            int glFormat = state.target().glFormat();
            for (DeepUpload upload : uploads) {
                // Not through the frame bank: these bands change with every send.
                if (TesseraSelfTest.ENABLED) {
                    EncodeStats.uploads++;
                }
                GL13.glCompressedTexSubImage2D(GL11.GL_TEXTURE_2D, upload.level(), upload.x(), upload.y(),
                        upload.width(), upload.height(), glFormat, upload.blocks().duplicate());
            }
            // The driver has copied them: the next send may reuse them.
            bands.forEach(state.deep()::giveBack);
        });
    }

    /**
     * Mip thread: copies a patched rectangle's blocks into its level's compressed copy, encoding the
     * whole level from its RGBA copy the first time. {@code false} if that encode failed.
     */
    private static boolean writeIntoLevel(AtlasState state, DeepUpload rect, int quality) {
        MipChainBuilder.MipLevel copy = state.deepLevels().get(rect.level());
        int bytesPerBlock = state.target().bytesPerBlock();
        int levelBlocksWide = (copy.width() + 3) >> 2;
        ByteBuffer levelBlocks = state.deep().blocks.get(rect.level());
        if (levelBlocks == null) {
            ByteBuffer rgba = ByteBuffer.allocateDirect(copy.width() * copy.height() * 4).order(ByteOrder.LITTLE_ENDIAN);
            rgba.put(0, copy.rgba8(), 0, rgba.capacity());
            levelBlocks = encodeOffThread(rgba, copy.width(), copy.height(), state.target(), quality);
            if (levelBlocks.remaining() != levelBlocksWide * ((copy.height() + 3) >> 2) * bytesPerBlock) {
                return false;
            }
            state.deep().blocks.put(rect.level(), levelBlocks);
            return true; // encoded from the copy, which already holds this frame
        }
        int rectBlocksWide = (rect.width() + 3) >> 2;
        int rectBlocksHigh = (rect.height() + 3) >> 2;
        int rowBytes = rectBlocksWide * bytesPerBlock;
        ByteBuffer blocks = rect.blocks();
        for (int row = 0; row < rectBlocksHigh; row++) {
            int dst = (((rect.y() >> 2) + row) * levelBlocksWide + (rect.x() >> 2)) * bytesPerBlock;
            levelBlocks.put(dst, blocks, blocks.position() + row * rowBytes, rowBytes);
        }
        return true;
    }

    /** Mip thread: block rows {@code [firstRow, endRow)} of a level's compressed copy, across its whole width. */
    private static DeepUpload levelBand(AtlasState state, int level, int firstRow, int endRow) {
        MipChainBuilder.MipLevel copy = state.deepLevels().get(level);
        int rowBytes = ((copy.width() + 3) >> 2) * state.target().bytesPerBlock();
        ByteBuffer band = state.deep().band((endRow - firstRow) * rowBytes);
        band.put(0, state.deep().blocks.get(level), firstRow * rowBytes, band.limit());
        int y = firstRow << 2;
        return new DeepUpload(level, 0, y, copy.width(), Math.min(copy.height(), endRow << 2) - y, band);
    }

    /** Mip thread: one level of one frame, see {@link #patchDeepLevels}; counts new encodes in {@code encodes[0]}. */
    private static DeepUpload patchDeepLevel(AtlasState state, int level, FramePixels frame, int dstX, int dstY,
                                             int w, int h, int quality, int[] encodes) {
        MipChainBuilder.MipLevel copy = state.deepLevels().get(level);
        int levelWidth = copy.width();
        int levelHeight = copy.height();
        ByteBuffer pixels = copy.rgba8().duplicate().order(ByteOrder.LITTLE_ENDIAN);
        for (int py = 0; py < h; py++) {
            for (int px = 0; px < w; px++) {
                pixels.putInt(((dstY + py) * levelWidth + dstX + px) * 4, frame.abgr(level, px, py));
            }
        }

        // Offsets snap down to the block grid; extents snap up, clamped to the
        // level edge (partial blocks are only legal where they touch the edge).
        int x0 = dstX & ~3;
        int y0 = dstY & ~3;
        int x1 = Math.min(levelWidth, (dstX + w + 3) & ~3);
        int y1 = Math.min(levelHeight, (dstY + h + 3) & ~3);
        int rw = x1 - x0;
        int rh = y1 - y0;

        int bytes = rw * rh * 4;
        if (deepScratch.capacity() < bytes) {
            deepScratch = ByteBuffer.allocateDirect(bytes).order(ByteOrder.LITTLE_ENDIAN);
        }
        ByteBuffer rect = deepScratch.clear();
        for (int row = 0; row < rh; row++) {
            int rowStart = ((y0 + row) * levelWidth + x0) * 4;
            rect.put(rect.position(), pixels, rowStart, rw * 4);
            rect.position(rect.position() + rw * 4);
        }
        rect.flip();
        long key = contentHash(rect, rw, rh, state.target());
        ByteBuffer blocks = DEEP_BY_CONTENT.get(key);
        if (blocks == null) {
            blocks = encodeOffThread(rect, rw, rh, state.target(), quality);
            DEEP_BY_CONTENT.put(key, blocks);
            encodes[0]++;
        }
        return new DeepUpload(level, x0, y0, rw, rh, blocks);
    }

    /**
     * Encodes a rectangle off the render thread (bleeds {@code rgba} in place): the native encoder at
     * the atlas's quality, else the pure-Java one.
     */
    private static ByteBuffer encodeOffThread(ByteBuffer rgba, int w, int h, CompressionPipeline.Target target,
                                              int quality) {
        TransparentTexelBleed.apply(rgba, w, h);
        if (TesseraRuntime.isNativeActive() && rgba.isDirect()) {
            try {
                ByteBuffer blocks = target == CompressionPipeline.Target.BC7
                        ? TesseraRuntime.backend().compressBc7(rgba, w, h, quality)
                        : TesseraRuntime.backend().compressBc1(rgba, w, h, quality);
                if (blocks != null) {
                    return blocks;
                }
            } catch (RuntimeException | UnsatisfiedLinkError ignored) {
                // pure Java below
            }
        }
        return target == CompressionPipeline.Target.BC7
                ? SoftwareBc7Encoder.encode(rgba, w, h)
                : SoftwareBc1Encoder.encode(rgba, w, h);
    }

    /** One frame's pixels at a mip level as RGBA8, in a buffer of its own (any thread). */
    private static ByteBuffer newFrameRgba(FramePixels pixels, int level, int w, int h) {
        ByteBuffer rgba = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (int py = 0; py < h; py++) {
            for (int px = 0; px < w; px++) {
                rgba.putInt(pixels.abgr(level, px, py));
            }
        }
        return rgba.flip();
    }

    /** One frame's pixels at a mip level as RGBA8, in the shared scratch buffer (render thread only). */
    private static ByteBuffer frameRgba(FramePixels pixels, int level, int w, int h) {
        int bytes = w * h * 4;
        if (rgbaScratch.capacity() < bytes) {
            rgbaScratch = ByteBuffer.allocateDirect(bytes).order(ByteOrder.LITTLE_ENDIAN);
        }
        ByteBuffer rgba = rgbaScratch.clear();
        for (int py = 0; py < h; py++) {
            for (int px = 0; px < w; px++) {
                // ABGR written little-endian is R,G,B,A bytes.
                rgba.putInt(pixels.abgr(level, px, py));
            }
        }
        rgba.flip();
        return rgba;
    }

    /**
     * The fastest encoder, for everything encoded on the render thread (bleeds {@code rgba} in place):
     * the native one at preset 0 (bc7f alone for BC7; a frame stays on this thread, it is far below the
     * bridge's threading threshold), else the pure-Java one. The best-quality re-encode follows in the
     * background either way.
     */
    private static ByteBuffer encodeFast(ByteBuffer rgba, int w, int h, CompressionPipeline.Target target) {
        long start = TesseraSelfTest.ENABLED ? System.nanoTime() : 0L;
        TransparentTexelBleed.apply(rgba, w, h);
        ByteBuffer blocks = null;
        if (TesseraRuntime.isNativeActive() && rgba.isDirect()) {
            try {
                blocks = target == CompressionPipeline.Target.BC7
                        ? TesseraRuntime.backend().compressBc7(rgba, w, h, 0)
                        : TesseraRuntime.backend().compressBc1(rgba, w, h, 0);
            } catch (RuntimeException | UnsatisfiedLinkError e) {
                blocks = null;
            }
        }
        if (blocks == null) {
            blocks = target == CompressionPipeline.Target.BC7
                    ? SoftwareBc7Encoder.encode(rgba, w, h)
                    : SoftwareBc1Encoder.encode(rgba, w, h);
        }
        if (TesseraSelfTest.ENABLED) {
            EncodeStats.addFast(System.nanoTime() - start);
        }
        return blocks;
    }

    /**
     * Self-test only: animation encoding time since the last {@link #reset}, on the
     * render thread (what a tick pays) and on the background thread (best-quality keyframes).
     */
    public static final class EncodeStats {
        public static long calls, nanos, maxNanos, found;
        /** Keyframes not in the cache yet, blended frames looked up by content, deep-level patches. */
        public static long keyframeMisses, blendLookups, blendMisses;
        /** Smallest-level patches on the mip thread, how many needed a new encode, and the time it took. */
        private static long deepPatches, deepMisses, deepNanos;
        /** Everything animations cost the render thread: building frames, lookups, encodes, uploads. */
        public static long drawNanos, maxDrawNanos;
        /** Blends found by frame and progress, without building their pixels. */
        public static long foundByFrame;
        /** Compressed sub-image uploads (one per region and mip level drawn). */
        public static long uploads;
        /** Of those, copied from the GPU frame bank, and frames put in the bank (experimental). */
        public static long bankCopies, bankFills;
        private static long bestCalls, bestNanos;

        private EncodeStats() {
        }

        static void addFast(long used) {
            calls++;
            nanos += used;
            maxNanos = Math.max(maxNanos, used);
        }

        static void addDraw(long used) {
            drawNanos += used;
            maxDrawNanos = Math.max(maxDrawNanos, used);
        }

        static synchronized void addDeep(int patches, int encodes, long used) {
            deepPatches += patches;
            deepMisses += encodes;
            deepNanos += used;
        }

        public static synchronized long deepPatches() {
            return deepPatches;
        }

        public static synchronized long deepMisses() {
            return deepMisses;
        }

        public static synchronized long deepNanos() {
            return deepNanos;
        }

        static synchronized void addBest(long used) {
            bestCalls++;
            bestNanos += used;
        }

        public static synchronized long bestCalls() {
            return bestCalls;
        }

        public static synchronized long bestNanos() {
            return bestNanos;
        }

        public static synchronized void reset() {
            calls = nanos = maxNanos = found = 0;
            keyframeMisses = blendLookups = blendMisses = 0;
            deepPatches = deepMisses = deepNanos = 0;
            drawNanos = maxDrawNanos = 0;
            foundByFrame = 0;
            uploads = bankCopies = bankFills = 0;
            bestCalls = bestNanos = 0;
        }
    }
}
