package net.bichal.mods.tessera.mixin;

import net.bichal.mods.tessera.api.AtlasCompressEvent;
import net.bichal.mods.tessera.atlas.AtlasCompressionDriver;
import net.bichal.mods.tessera.atlas.AtlasFormats;
import net.bichal.mods.tessera.atlas.AtlasPolicy;
import net.bichal.mods.tessera.cache.AtlasCache;
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
 * Replaces the previous split-atlas architecture entirely: no second
 * {@code TextureAtlas}, no custom chunk geometry, no manual VAO/VBO draw
 * path. Vanilla stitches and uploads {@code TextureAtlas} exactly as it
 * always does -- every {@code BakedQuad}, every UV, every vertex layout
 * byte stays 100% vanilla. The only thing Tessera does here is, right
 * after vanilla's own {@code upload()} finishes (same texture, same atlas
 * object), re-assemble that atlas's already-placed pixels and re-upload them
 * compressed via {@code glCompressedTexImage2D} into the SAME texture --
 * respecifying its GL storage format in place.
 *
 * <p>This trades away the second BC1 format entirely (everything goes
 * through BC7, which handles both opaque and alpha content) in exchange
 * for eliminating every custom-geometry failure mode the split-atlas
 * design had: UV remapping, vertex stride/layout, sampler binding,
 * face culling, buffer lifecycle -- none of that code exists in this
 * design, because there is no second atlas and no custom draw call.
 *
 * <p>An atlas that holds animated sprites is not touched: vanilla keeps writing
 * its frames as RGBA8888 (with Kerria, Kerria does), so animations are never affected.
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

        // Animated textures stay RGBA8888: an atlas that holds any is left as vanilla
        // uploaded it, so frames reach it the way vanilla writes them (or the way Kerria does).
        if (AtlasPolicy.holdsAnimations(sprites)) {
            LOGGER.info("Atlas {} has animated sprites: kept as RGBA8888.", atlasName);
            CompressedAtlases.keepForAnimations(self);
            return;
        }

        int width = preparations.width();
        int height = preparations.height();
        // The full vanilla mip chain.
        int mipLevel = preparations.mipLevel();

        // Sprite images may be closed once this reload completes, so everything that reads
        // them (the fingerprint, and the pixel assembly on a miss) stays synchronous.
        AtlasCompressionDriver.Fingerprint fingerprint =
                AtlasCompressionDriver.fingerprint(sprites, width, height, mipLevel);

        // Default format by content and driver: BC1 / ASTC 6x6 when every texel is opaque, BC7 / ASTC 4x4
        // otherwise (keeps alpha). Listeners may cancel or pick another one.
        CompressionPipeline.Target chosen = AtlasFormats.choose(fingerprint.opaque());
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

        // An atlas identical to one encoded on an earlier run comes straight off the disk cache.
        CompletableFuture<AtlasCompressionDriver.CompressedAtlas> job;
        AtlasCompressionDriver.CompressedAtlas stored =
                AtlasCompressionDriver.loadChain(atlasName, target, fingerprint, mipLevel);
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
                AtlasCompressionDriver.storeChain(fingerprint, encoded);
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
                                        atlasLocation, format, uncompressed - resident, resident));
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
