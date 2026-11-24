package net.bichal.mods.tessera.compress;

import net.minecraft.client.renderer.texture.TextureAtlas;

import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The atlases that are block-compressed right now, with their format, and the animated ones left
 * RGBA8888 (the two halves of the split, see {@code AtlasPolicy}). Render thread only
 * (read by the self-test, which cannot rely on {@code AtlasCompressEvent.Post}: on 1.21 - 1.21.1
 * the first reload runs during mod loading, when NeoForge drops events posted to its bus).
 */
public final class CompressedAtlases {

    private static final Map<TextureAtlas, CompressionPipeline.Target> ATLASES = new IdentityHashMap<>();
    /** Atlases left RGBA8888 because they hold animated sprites, with the bytes they take. */
    private static final Map<TextureAtlas, Long> KEPT_FOR_ANIMATIONS = new IdentityHashMap<>();
    private static int registrations;

    private CompressedAtlases() {
    }

    /** After a successful compressed upload of {@code atlas}. */
    public static void register(TextureAtlas atlas, CompressionPipeline.Target target) {
        ATLASES.put(atlas, target);
        registrations++;
    }

    /** When the atlas's texture is about to be recreated as RGBA. */
    public static void unregister(TextureAtlas atlas) {
        ATLASES.remove(atlas);
        KEPT_FOR_ANIMATIONS.remove(atlas);
    }

    /** An atlas left as RGBA8888 because it holds animated sprites; {@code rgbaBytes} with its mip levels. */
    public static void keepForAnimations(TextureAtlas atlas, long rgbaBytes) {
        KEPT_FOR_ANIMATIONS.put(atlas, rgbaBytes);
    }

    public static java.util.List<TextureAtlas> keptForAnimations() {
        return new java.util.ArrayList<>(KEPT_FOR_ANIMATIONS.keySet());
    }

    /** Bytes the atlases kept for animations take as RGBA8888 (shown in F3 next to the compressed ones). */
    public static long keptForAnimationsBytes() {
        long total = 0;
        for (long bytes : KEPT_FOR_ANIMATIONS.values()) {
            total += bytes;
        }
        return total;
    }

    /** Compressed uploads so far (the self-test waits for it to settle). */
    public static int registrations() {
        return registrations;
    }

    public static Map<TextureAtlas, CompressionPipeline.Target> all() {
        return new LinkedHashMap<>(ATLASES);
    }
}
