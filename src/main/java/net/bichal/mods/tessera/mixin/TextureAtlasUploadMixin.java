package net.bichal.mods.tessera.mixin;

import net.bichal.mods.tessera.api.AtlasCompressEvent;
import net.bichal.mods.tessera.atlas.AtlasCompressionDriver;
import net.bichal.mods.tessera.atlas.AtlasFormats;
import net.bichal.mods.tessera.atlas.AtlasPolicy;
import net.bichal.mods.tessera.cache.AtlasCache;
import net.bichal.mods.tessera.compat.ModCompat;
import net.bichal.mods.tessera.compat.TesseraCompat;
import net.bichal.mods.tessera.compress.CompressedAnimationUploader;
import net.bichal.mods.tessera.compress.CompressedAtlases;
import net.bichal.mods.tessera.compress.CompressionPipeline;
import net.bichal.mods.tessera.config.Config;
import net.bichal.mods.tessera.gui.DebugOverlay;
import net.bichal.mods.tessera.vram.VramBudgetEngine;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.SpriteLoader;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.ByteBuffer;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.concurrent.CompletableFuture;

/**
 * Compresses an atlas in place, right after vanilla's own {@code upload()}: the same texture and
 * atlas object, every {@code BakedQuad}, UV and vertex byte untouched. Tessera re-assembles the
 * atlas's already placed pixels and re-uploads them block-compressed with
 * {@code glCompressedTexImage2D} into the same texture, respecifying its storage format.
 *
 * <p>Static atlases are compressed with the smallest format their content allows (see
 * {@link AtlasFormats}). Atlases with animated sprites (the block atlas) are compressed too: every
 * animation frame is encoded once and written as compressed blocks by
 * {@link CompressedAnimationUploader}, frames up to 1.21.10 arriving through
 * {@code SpriteContents.upload} (see {@link SpriteContentsUploadMixin}), from 1.21.11 replacing
 * vanilla's GPU passes into the atlas. With Kerria installed, which writes those frames as RGBA8888,
 * they are left as vanilla uploaded them (see {@link AtlasPolicy#mayCompressAnimated}).
 */
@Mixin(TextureAtlas.class)
public abstract class TextureAtlasUploadMixin {

    private static final Logger LOGGER = LoggerFactory.getLogger("Tessera/InPlaceAtlasCompressor");

    /** The texture is about to be recreated as RGBA: until a new chain is uploaded, it is not compressed. */
    @Inject(method = "upload", at = @At("HEAD"))
    private void tessera$beforeUpload(SpriteLoader.Preparations preparations, CallbackInfo ci) {
        TextureAtlas atlas = (TextureAtlas) (Object) this;
        CompressedAtlases.unregister(atlas);
        CompressedAnimationUploader.unregister(atlas);
        DebugOverlay.resetAtlas(atlas.location().toString());
        VramBudgetEngine.recordResident(atlas.location().toString(), -1);
        // A chain still being encoded for the previous texture must not land on this one.
        ++tessera$generation;
    }

    @Inject(method = "upload", at = @At("RETURN"))
    private void tessera$compressInPlace(SpriteLoader.Preparations preparations, CallbackInfo ci) {
        TextureAtlas atlas = (TextureAtlas) (Object) this;
        LOGGER.info("Atlas {} stitched with {} sprites.", atlas.location(), preparations.regions().size());
        // Vanilla has already uploaded the RGBA atlas at this point; any failure
        // below must leave that upload intact instead of failing the reload.
        try {
            tessera$compressInPlaceUnchecked(preparations);
        } catch (Throwable t) {
            LOGGER.error("Tessera in-place compression failed for {} -- keeping vanilla RGBA atlas",
                    atlas.location(), t);
        }
    }

    //? >=1.21.11 {
    /*// Frames of a compressed atlas: drawn by Tessera instead of vanilla's GPU passes into it.
    @Inject(method = "uploadAnimationFrames", at = @At("HEAD"), cancellable = true)
    private void tessera$drawCompressedFrames(CallbackInfo ci) {
        if (CompressedAnimationUploader.drawFrames((TextureAtlas) (Object) this, false)) {
            ci.cancel();
        }
    }
    *///?} else {
    /** cycleAnimationFrames ticks every animated sprite; see SpriteContentsUploadMixin. */
    @Inject(method = "cycleAnimationFrames", at = @At("HEAD"))
    private void tessera$beginAnimationCycle(CallbackInfo ci) {
        CompressedAnimationUploader.beginAnimationCycle((TextureAtlas) (Object) this);
    }

    @Inject(method = "cycleAnimationFrames", at = @At("RETURN"))
    private void tessera$endAnimationCycle(CallbackInfo ci) {
        CompressedAnimationUploader.endAnimationCycle();
    }
    //?}

    @Unique
    private void tessera$compressInPlaceUnchecked(SpriteLoader.Preparations preparations) {
        if (Config.get(Config.DISABLE_NATIVE_COMPRESSION)) {
            return;
        }

        TextureAtlas self = (TextureAtlas) (Object) this;
        var atlasLocation = self.location();
        String atlasName = atlasLocation.toString();
        if (!AtlasPolicy.isCompressible(atlasName)) {
            return;
        }

        Collection<TextureAtlasSprite> sprites = preparations.regions().values();
        if (sprites.isEmpty()) {
            return;
        }

        int width = preparations.width();
        int height = preparations.height();
        // The full vanilla mip chain.
        int mipLevel = preparations.mipLevel();

        boolean animated = AtlasPolicy.classify(sprites) == AtlasPolicy.Kind.ANIMATED;

        // Sprite images may be closed once this reload completes, so everything that reads
        // them (the fingerprint, and the pixel assembly on a miss) stays synchronous.
        AtlasCompressionDriver.Fingerprint fingerprint =
                AtlasCompressionDriver.fingerprint(sprites, width, height, mipLevel);
        AtlasFormats.Alpha alpha = fingerprint.alpha();
        if (animated && alpha == AtlasFormats.Alpha.OPAQUE && !AtlasPolicy.animationFramesOpaque(sprites)) {
            // The fingerprint reads frame 0; a later frame with transparency needs a format with alpha.
            alpha = AtlasFormats.Alpha.BLENDED;
        }

        // Default format by content and driver: BC1 / ASTC 6x6 when every texel is opaque, BC1 with
        // one-bit alpha when every texel is opaque or transparent and there are no mip levels, BC7 /
        // ASTC 4x4 otherwise (keeps alpha). Listeners may cancel or pick another one.
        CompressionPipeline.Target chosen = AtlasFormats.choose(alpha, mipLevel);
        if (animated && chosen == CompressionPipeline.Target.BC1_ALPHA) {
            chosen = AtlasFormats.fallback(chosen); // frames are encoded per block as BC1 or BC7
        }
        if (chosen == null) {
            LOGGER.info("Atlas {}: this driver takes none of BC1, BC7, ASTC; kept as RGBA8888.", atlasName);
            return;
        }
        AtlasCompressEvent.Pre pre = AtlasCompressEvent.post(
                new AtlasCompressEvent.Pre(atlasLocation, chosen.cacheFormat()));
        if (pre.isCanceled()) {
            return;
        }
        AtlasCache.CompressedFormat format = pre.getTargetFormat();
        CompressionPipeline.Target target = CompressionPipeline.Target.of(format);
        if (!target.gpuSupported()) {
            return;
        }
        if (target.isAstc() && AtlasPolicy.isGuiAtlas(atlasName)) {
            // Its transparent texels must decode as exactly 0, which only the BC formats are checked for.
            LOGGER.info("Atlas {}: GUI atlas kept as RGBA8888 ({} cannot keep its transparent texels exact).",
                    atlasName, target);
            return;
        }

        // Animated atlases: compressed with every frame precoded (CompressedAnimationUploader), or
        // left RGBA8888 as vanilla uploaded them (Kerria installed, setting off, format without 4x4 blocks).
        int alignedLevel = mipLevel;
        IdentityHashMap<Object, TextureAtlasSprite> animatedSprites = null;
        if (animated) {
            animatedSprites = TesseraCompat.animatedSprites(self);
            String keptBecause = !AtlasPolicy.mayCompressAnimated(target)
                    ? (ModCompat.isKerriaLoaded() ? "Kerria writes its frames" : "animated atlases are not compressed with " + target)
                    : !CompressedAnimationUploader.canAnimate(true) ? "animation states cannot be read"
                    : animatedSprites == null ? "animation states do not match its animated sprites"
                    : null;
            if (keptBecause != null) {
                LOGGER.info("Atlas {} has animated sprites: kept as RGBA8888 ({}).", atlasName, keptBecause);
                CompressedAtlases.keepForAnimations(self, AtlasPolicy.rgbaChainBytes(width, height, mipLevel));
                return;
            }
            // Frames are written per 4x4 block, so each animated region (with its padding, 1.21.11+)
            // must stay block aligned; past that level their blocks are patched in a retained RGBA copy.
            int[] animatedRects = sprites.stream()
                    .filter(sprite -> TesseraCompat.isAnimated(sprite.contents()))
                    .flatMapToInt(sprite -> {
                        int padding = TesseraCompat.padding(sprite);
                        return java.util.stream.IntStream.of(sprite.getX(), sprite.getY(),
                                sprite.contents().width() + 2 * padding, sprite.contents().height() + 2 * padding);
                    })
                    .toArray();
            alignedLevel = CompressedAnimationUploader.alignedMipLevels(mipLevel, animatedRects);
            if (alignedLevel < mipLevel) {
                LOGGER.info("Atlas {}: animated sprites patched per block at mip levels {}..{}.",
                        atlasName, alignedLevel + 1, mipLevel);
            }
        }
        int retainFrom = alignedLevel < mipLevel ? alignedLevel + 1 : Integer.MAX_VALUE;

        // Where the chosen format can refuse an image (BC1 with one-bit alpha below its quality
        // floor), the format to encode instead. Its chain is cached apart, so it is only found
        // for an atlas that was refused before.
        CompressionPipeline.Target fallback = AtlasFormats.fallback(target);

        // An atlas identical to one encoded on an earlier run comes straight off the disk cache.
        CompletableFuture<AtlasCompressionDriver.CompressedAtlas> job;
        AtlasCompressionDriver.CompressedAtlas stored =
                AtlasCompressionDriver.loadChain(atlasName, target, fingerprint, mipLevel, false);
        if (stored == null && fallback != null) {
            stored = AtlasCompressionDriver.loadChain(atlasName, fallback, fingerprint, mipLevel, true);
        }
        if (stored != null && retainFrom <= mipLevel) {
            // From the cache, but the deep levels' RGBA (for per-block frame patches) is rebuilt.
            AtlasCompressionDriver.AssembledAtlas atlasPixels = AtlasCompressionDriver.assembleAtlasBuffer(
                    atlasName, sprites, width, height);
            if (atlasPixels == null) {
                return;
            }
            AtlasCompressionDriver.CompressedAtlas chain = stored;
            job = CompletableFuture.supplyAsync(() -> chain.withRetained(AtlasCompressionDriver.retainedLevels(
                    atlasPixels.pixels(), width, height, mipLevel, retainFrom)), TesseraCompat.backgroundExecutor());
        } else if (stored != null) {
            job = CompletableFuture.completedFuture(stored);
        } else {
            AtlasCompressionDriver.AssembledAtlas atlasPixels = AtlasCompressionDriver.assembleAtlasBuffer(
                    atlasName, sprites, width, height);
            if (atlasPixels == null) {
                return;
            }
            ByteBuffer assembled = atlasPixels.pixels();
            // Encoding (and mip generation) runs off the render thread; only the GL
            // upload hops back.
            job = CompletableFuture.supplyAsync(() -> {
                AtlasCompressionDriver.CompressedAtlas encoded = AtlasCompressionDriver.compress(
                        atlasName, target, assembled, width, height, mipLevel, retainFrom);
                boolean refused = encoded.levels().isEmpty() && fallback != null;
                if (refused) {
                    encoded = AtlasCompressionDriver.compress(atlasName, fallback, assembled, width, height, mipLevel);
                }
                AtlasCompressionDriver.storeChain(fingerprint, encoded, refused);
                return encoded;
            }, TesseraCompat.backgroundExecutor());
        }

        // The generation stamp drops results from a reload that was superseded while
        // compressing, so a stale chain never overwrites a newer atlas.
        int generation = tessera$generation;
        int animatedAlignedLevel = alignedLevel;
        IdentityHashMap<Object, TextureAtlasSprite> animationStates = animatedSprites;
        job
                .whenComplete((compressed, failure) -> {
                    if (failure != null) {
                        LOGGER.error("Background compression failed for {} -- keeping vanilla RGBA atlas",
                                atlasName, failure);
                        return;
                    }
                    if (compressed == null || compressed.levels().isEmpty()) {
                        return;
                    }
                    Minecraft.getInstance().execute(() -> {
                        if (generation != tessera$generation) {
                            return; // superseded by a newer reload
                        }
                        try {
                            int glId = TesseraCompat.glId(self);
                            long resident = AtlasCompressionDriver.upload(glId, compressed);
                            if (resident >= 0) {
                                CompressedAtlases.register(self, compressed.target());
                                if (animationStates != null) {
                                    // From now on animation frames are written as compressed blocks.
                                    int uploadedLevels = Math.min(mipLevel, compressed.levels().size() - 1);
                                    CompressedAnimationUploader.register(self, glId, compressed.target(), uploadedLevels,
                                            animatedAlignedLevel, compressed.retainedRgba(), animationStates);
                                }
                                long uncompressed = 0L;
                                for (AtlasCompressionDriver.CompressedLevel level : compressed.levels()) {
                                    uncompressed += (long) level.width() * level.height() * 4;
                                }
                                AtlasCompressEvent.post(new AtlasCompressEvent.Post(
                                        atlasLocation, compressed.target().cacheFormat(), uncompressed - resident, resident));
                            }
                        } catch (Throwable t) {
                            LOGGER.error("Compressed upload failed for {} -- keeping vanilla RGBA atlas",
                                    atlasName, t);
                        }
                    });
                });
    }

    /** Bumped on every upload; written and compared on the render thread only. */
    @Unique
    private int tessera$generation;
}
