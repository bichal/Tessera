package net.bichal.mods.tessera.mixin;

import net.bichal.mods.tessera.api.AtlasCompressEvent;
import net.bichal.mods.tessera.atlas.AtlasCompressionDriver;
import net.bichal.mods.tessera.atlas.AtlasFormats;
import net.bichal.mods.tessera.atlas.AtlasPolicy;
import net.bichal.mods.tessera.cache.AtlasCache;
import net.bichal.mods.tessera.compat.ModCompat;
import net.bichal.mods.tessera.compat.TesseraCompat;
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
import java.util.concurrent.CompletableFuture;

/**
 * Compresses an atlas in place, right after vanilla's own {@code upload()}: the same texture and
 * atlas object, every {@code BakedQuad}, UV and vertex byte untouched. Tessera re-assembles the
 * atlas's already placed pixels and re-uploads them block-compressed with
 * {@code glCompressedTexImage2D} into the same texture, respecifying its storage format.
 *
 * <p>Atlases are split in two by what they hold. Static atlases are compressed with the smallest
 * format their content allows (see {@link AtlasFormats}). Animated atlases, those holding any
 * animated sprite, are not touched: vanilla keeps writing their frames as RGBA8888 (with Kerria,
 * Kerria does), so animations are never affected. Splitting one atlas into two textures is not an
 * option: chunks, items, particles, Sodium and shader packs all sample a single texture per atlas.
 */
@Mixin(TextureAtlas.class)
public abstract class TextureAtlasUploadMixin {

    private static final Logger LOGGER = LoggerFactory.getLogger("Tessera/InPlaceAtlasCompressor");

    /** The texture is about to be recreated as RGBA: until a new chain is uploaded, it is not compressed. */
    @Inject(method = "upload", at = @At("HEAD"))
    private void tessera$beforeUpload(SpriteLoader.Preparations preparations, CallbackInfo ci) {
        TextureAtlas atlas = (TextureAtlas) (Object) this;
        CompressedAtlases.unregister(atlas);
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

        // The split: static atlases are compressed below; animated ones stay RGBA8888 exactly as
        // vanilla uploaded them, so frames reach them the way vanilla writes them (or the way Kerria does).
        if (AtlasPolicy.classify(sprites) == AtlasPolicy.Kind.ANIMATED) {
            LOGGER.info("Atlas {} has animated sprites: kept as RGBA8888{}.", atlasName,
                    ModCompat.isKerriaLoaded() ? " for Kerria" : "");
            CompressedAtlases.keepForAnimations(self, AtlasPolicy.rgbaChainBytes(width, height, mipLevel));
            return;
        }

        // Sprite images may be closed once this reload completes, so everything that reads
        // them (the fingerprint, and the pixel assembly on a miss) stays synchronous.
        AtlasCompressionDriver.Fingerprint fingerprint =
                AtlasCompressionDriver.fingerprint(sprites, width, height, mipLevel);

        // Default format by content and driver: BC1 / ASTC 6x6 when every texel is opaque, BC1 with
        // one-bit alpha when every texel is opaque or transparent and there are no mip levels, BC7 /
        // ASTC 4x4 otherwise (keeps alpha). Listeners may cancel or pick another one.
        CompressionPipeline.Target chosen = AtlasFormats.choose(fingerprint.alpha(), mipLevel);
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
        if (stored != null) {
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
                        atlasName, target, assembled, width, height, mipLevel);
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
