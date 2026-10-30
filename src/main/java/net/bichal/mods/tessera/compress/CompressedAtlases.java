package net.bichal.mods.tessera.compress;

import net.minecraft.client.renderer.texture.TextureAtlas;

import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The atlases that are block-compressed right now, with their format. Render thread only
 * (read by the self-test, which cannot rely on {@code AtlasCompressEvent.Post}: on 1.21 - 1.21.1
 * the first reload runs during mod loading, when NeoForge drops events posted to its bus).
 */
public final class CompressedAtlases {

    private static final Map<TextureAtlas, CompressionPipeline.Target> ATLASES = new IdentityHashMap<>();
    private static final java.util.Set<TextureAtlas> KEPT_FOR_ANIMATIONS =
            java.util.Collections.newSetFromMap(new IdentityHashMap<>());
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

    /** An atlas left as RGBA8888 because it holds animated sprites. */
    public static void keepForAnimations(TextureAtlas atlas) {
        KEPT_FOR_ANIMATIONS.add(atlas);
    }

    public static java.util.List<TextureAtlas> keptForAnimations() {
        return new java.util.ArrayList<>(KEPT_FOR_ANIMATIONS);
    }

    /** Compressed uploads so far (the self-test waits for it to settle). */
    public static int registrations() {
        return registrations;
    }

    public static Map<TextureAtlas, CompressionPipeline.Target> all() {
        return new LinkedHashMap<>(ATLASES);
    }
}
