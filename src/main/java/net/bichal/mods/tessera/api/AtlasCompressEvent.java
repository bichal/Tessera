package net.bichal.mods.tessera.api;

import net.bichal.mods.tessera.cache.AtlasCache.CompressedFormat;
//? >=1.21.11 {
/*import net.minecraft.resources.Identifier;
*///?} else
import net.minecraft.resources.ResourceLocation;
//? if neoforge {
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.ICancellableEvent;
import net.neoforged.neoforge.common.NeoForge;
//?} else {
/*import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
*///?}

/**
 * Fired on {@code NeoForge.EVENT_BUS} (on Fabric: to the listeners given to
 * {@link #addListener}), on the render thread, around the
 * in-place compression of each atlas after vanilla uploads it.
 *
 * <ul>
 *   <li>{@link Pre}: before compression starts. Cancel it to keep this atlas
 *       uncompressed, or change {@link Pre#setTargetFormat the format}. BC1 has
 *       no alpha channel, so choose it only for atlases without transparency.</li>
 *   <li>{@link Post}: after the compressed chain has been uploaded, with the
 *       VRAM it saved and now occupies.</li>
 * </ul>
 * On Minecraft 1.21 - 1.21.1 the first resource reload runs while mods are still
 * loading, and NeoForge's bus drops events posted before loading ends: the events
 * of that first reload's atlases may never arrive. Later reloads are not affected.
 */
@SuppressWarnings("unused")
//? if neoforge {
public abstract class AtlasCompressEvent extends Event {
//?} else {
/*public abstract class AtlasCompressEvent {

    private static final List<Consumer<AtlasCompressEvent>> LISTENERS = new CopyOnWriteArrayList<>();

    // Fabric has no event bus of its own for this: listeners register here.
    public static void addListener(Consumer<AtlasCompressEvent> listener) {
        LISTENERS.add(listener);
    }
*///?}

    /** Delivers an event to every listener; the event comes back for chaining. */
    public static <E extends AtlasCompressEvent> E post(E event) {
        //? if neoforge {
        return NeoForge.EVENT_BUS.post(event);
        //?} else {
        /*for (Consumer<AtlasCompressEvent> listener : LISTENERS) {
            listener.accept(event);
        }
        return event;
        *///?}
    }

    //? >=1.21.11 {
    /*private final Identifier atlasLocation;
    *///?} else
    private final ResourceLocation atlasLocation;

    //? >=1.21.11 {
    /*public AtlasCompressEvent(Identifier atlasLocation) {
    *///?} else
    public AtlasCompressEvent(ResourceLocation atlasLocation) {
        this.atlasLocation = atlasLocation;
    }

    //? >=1.21.11 {
    /*public Identifier getAtlasLocation() {
    *///?} else
    public ResourceLocation getAtlasLocation() {
        return atlasLocation;
    }

    //? if neoforge {
    public static class Pre extends AtlasCompressEvent implements ICancellableEvent {
    //?} else {
    /*public static class Pre extends AtlasCompressEvent {

        private boolean canceled;

        public boolean isCanceled() {
            return canceled;
        }

        public void setCanceled(boolean canceled) {
            this.canceled = canceled;
        }
    *///?}

        private CompressedFormat targetFormat;

        //? >=1.21.11 {
        /*public Pre(Identifier atlasLocation, CompressedFormat defaultFormat) {
        *///?} else
        public Pre(ResourceLocation atlasLocation, CompressedFormat defaultFormat) {
            super(atlasLocation);
            this.targetFormat = defaultFormat;
        }

        public CompressedFormat getTargetFormat() {
            return targetFormat;
        }

        public void setTargetFormat(CompressedFormat targetFormat) {
            this.targetFormat = targetFormat;
        }
    }

    public static class Post extends AtlasCompressEvent {

        private final CompressedFormat appliedFormat;
        private final long vramBytesSaved;
        private final long residentBytes;

        //? >=1.21.11 {
        /*public Post(Identifier atlasLocation, CompressedFormat appliedFormat, long vramBytesSaved, long residentBytes) {
        *///?} else
        public Post(ResourceLocation atlasLocation, CompressedFormat appliedFormat, long vramBytesSaved, long residentBytes) {
            super(atlasLocation);
            this.appliedFormat = appliedFormat;
            this.vramBytesSaved = vramBytesSaved;
            this.residentBytes = residentBytes;
        }

        public CompressedFormat getAppliedFormat() {
            return appliedFormat;
        }

        public long getVramBytesSaved() {
            return vramBytesSaved;
        }

        public long getResidentBytes() {
            return residentBytes;
        }
    }
}
