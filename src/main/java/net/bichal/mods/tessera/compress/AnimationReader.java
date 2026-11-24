package net.bichal.mods.tessera.compress;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.List;

/**
 * Reads {@code SpriteContents.AnimationState} (1.21.11+), where vanilla picks
 * and blends animation frames on the GPU and exposes neither: the frame shown,
 * the next one and the blend progress, exactly as {@code drawToAtlas} hands
 * them to its shaders. On NeoForge and from 26.1 (Mojang's names at run time) through
 * method handles, because the classes involved are not public; on Fabric 1.21.11, whose
 * run-time names are intermediary ones, through the access widener, which Loom remaps.
 * {@link #AVAILABLE} is false when they cannot be resolved; atlases with animations
 * are then not compressed at all.
 */
final class AnimationReader {

    private static final Logger LOGGER = LoggerFactory.getLogger("Tessera/AnimationReader");
    private static final String SPRITE_CONTENTS = "net.minecraft.client.renderer.texture.SpriteContents$";

    static final boolean AVAILABLE;
    private static final MethodHandle FRAME;
    private static final MethodHandle SUB_FRAME;
    private static final MethodHandle INFO;
    private static final MethodHandle NEEDS_TO_DRAW;
    private static final MethodHandle FRAMES;
    private static final MethodHandle ROW_SIZE;
    private static final MethodHandle INTERPOLATE;
    private static final MethodHandle INDEX;
    private static final MethodHandle TIME;

    /**
     * The frame grid cells (column, row in the sprite's image) shown now and
     * next, and the blend between them (0 when the sprite does not interpolate).
     */
    record Frame(int column, int row, int nextColumn, int nextRow, float progress, boolean interpolated) {
    }

    /**
     * An animation's whole sequence: each frame's index in the sprite's grid and how many ticks it
     * lasts, the grid's row size, and whether frames are blended into the next.
     */
    record Timeline(int[] indices, int[] times, int rowSize, boolean interpolated) {
    }

    /**
     * Up to 1.21.10 the sequence sits in {@code SpriteContents.animatedTexture}, read by name (Mojang's
     * names at run time: NeoForge; a Fabric production run, which uses intermediary names, gets
     * {@code null} and simply skips precoding blended frames).
     */
    static Timeline legacyTimeline(Object contents) {
        try {
            java.lang.reflect.Field field = contents.getClass().getDeclaredField("animatedTexture");
            field.setAccessible(true);
            Object info = field.get(contents);
            return info == null ? null : timelineOf(info);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    /** Reads an {@code AnimatedTexture} (any version) by its field names. */
    private static Timeline timelineOf(Object info) throws ReflectiveOperationException {
        Class<?> type = info.getClass();
        List<?> frames = (List<?>) field(type, "frames").get(info);
        int rowSize = field(type, "frameRowSize").getInt(info);
        boolean interpolate = field(type, "interpolateFrames").getBoolean(info);
        int[] indices = new int[frames.size()];
        int[] times = new int[frames.size()];
        for (int i = 0; i < indices.length; i++) {
            Object frame = frames.get(i);
            indices[i] = field(frame.getClass(), "index").getInt(frame);
            times[i] = field(frame.getClass(), "time").getInt(frame);
        }
        return new Timeline(indices, times, rowSize, interpolate);
    }

    private static java.lang.reflect.Field field(Class<?> type, String name) throws NoSuchFieldException {
        java.lang.reflect.Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    //? if fabric && >=1.21.11 {
    /*static {
        AVAILABLE = true;
        FRAME = SUB_FRAME = INFO = NEEDS_TO_DRAW = FRAMES = ROW_SIZE = INTERPOLATE = INDEX = TIME = null;
    }

    static boolean needsToDraw(Object state) {
        return ((net.minecraft.client.renderer.texture.SpriteContents.AnimationState) state).needsToDraw();
    }

    static Frame read(Object state) {
        var animation = (net.minecraft.client.renderer.texture.SpriteContents.AnimationState) state;
        var info = animation.animationInfo;
        java.util.List<net.minecraft.client.renderer.texture.SpriteContents.FrameInfo> frames = info.frames;
        int rowSize = info.frameRowSize;
        var current = frames.get(animation.frame);
        int index = current.index();
        int next = frames.get((animation.frame + 1) % frames.size()).index();
        float progress = info.interpolateFrames
                ? (int) ((float) animation.subFrame / current.time() * 1000.0F) / 1000.0F : 0.0F;
        return new Frame(index % rowSize, index / rowSize, next % rowSize, next / rowSize, progress, info.interpolateFrames);
    }

    static Timeline timeline(Object state) {
        var info = ((net.minecraft.client.renderer.texture.SpriteContents.AnimationState) state).animationInfo;
        int[] indices = new int[info.frames.size()];
        int[] times = new int[indices.length];
        for (int i = 0; i < indices.length; i++) {
            indices[i] = info.frames.get(i).index();
            times[i] = info.frames.get(i).time();
        }
        return new Timeline(indices, times, info.frameRowSize, info.interpolateFrames);
    }
    *///?} else {
    static {
        MethodHandle[] h = new MethodHandle[9];
        boolean ok = false;
        try {
            ClassLoader loader = AnimationReader.class.getClassLoader();
            Class<?> state = Class.forName(SPRITE_CONTENTS + "AnimationState", false, loader);
            Class<?> info = Class.forName(SPRITE_CONTENTS + "AnimatedTexture", false, loader);
            Class<?> frame = Class.forName(SPRITE_CONTENTS + "FrameInfo", false, loader);
            h[0] = getter(state, "frame", int.class);
            h[1] = getter(state, "subFrame", int.class);
            h[2] = getter(state, "animationInfo", info);
            h[3] = MethodHandles.privateLookupIn(state, MethodHandles.lookup())
                    .findVirtual(state, "needsToDraw", MethodType.methodType(boolean.class))
                    .asType(MethodType.methodType(boolean.class, Object.class));
            h[4] = getter(info, "frames", List.class);
            h[5] = getter(info, "frameRowSize", int.class);
            h[6] = getter(info, "interpolateFrames", boolean.class);
            h[7] = getter(frame, "index", int.class);
            h[8] = getter(frame, "time", int.class);
            ok = true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOGGER.warn("Tessera: cannot read sprite animation state ({}); atlases with animations stay uncompressed",
                    e.toString());
        }
        AVAILABLE = ok;
        FRAME = h[0];
        SUB_FRAME = h[1];
        INFO = h[2];
        NEEDS_TO_DRAW = h[3];
        FRAMES = h[4];
        ROW_SIZE = h[5];
        INTERPOLATE = h[6];
        INDEX = h[7];
        TIME = h[8];
    }

    private AnimationReader() {
    }

    private static MethodHandle getter(Class<?> owner, String field, Class<?> type) throws ReflectiveOperationException {
        Class<?> erased = type.isPrimitive() ? type : Object.class;
        return MethodHandles.privateLookupIn(owner, MethodHandles.lookup())
                .findGetter(owner, field, type)
                .asType(MethodType.methodType(erased, Object.class));
    }

    /** AnimationState.needsToDraw(): interpolating, or the frame index just changed. */
    static boolean needsToDraw(Object state) {
        try {
            return (boolean) NEEDS_TO_DRAW.invokeExact(state);
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    /** What AnimationState.drawToAtlas would draw now. */
    static Frame read(Object state) {
        try {
            int frame = (int) FRAME.invokeExact(state);
            int subFrame = (int) SUB_FRAME.invokeExact(state);
            Object info = (Object) INFO.invokeExact(state);
            List<?> frames = (List<?>) (Object) FRAMES.invokeExact(info);
            int rowSize = (int) ROW_SIZE.invokeExact(info);
            boolean interpolate = (boolean) INTERPOLATE.invokeExact(info);
            Object current = frames.get(frame);
            int index = (int) INDEX.invokeExact(current);
            int time = (int) TIME.invokeExact(current);
            int next = (int) INDEX.invokeExact((Object) frames.get((frame + 1) % frames.size()));
            // drawToAtlas passes (int) (subFrame / time * 1000) to the shader, which divides by 1000.
            float progress = interpolate ? (int) ((float) subFrame / time * 1000.0F) / 1000.0F : 0.0F;
            return new Frame(index % rowSize, index / rowSize, next % rowSize, next / rowSize, progress, interpolate);
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    /** The whole sequence of an AnimationState (1.21.11+), or {@code null} when it cannot be read. */
    static Timeline timeline(Object state) {
        try {
            Object info = (Object) INFO.invokeExact(state);
            List<?> frames = (List<?>) (Object) FRAMES.invokeExact(info);
            int[] indices = new int[frames.size()];
            int[] times = new int[indices.length];
            for (int i = 0; i < indices.length; i++) {
                indices[i] = (int) INDEX.invokeExact((Object) frames.get(i));
                times[i] = (int) TIME.invokeExact((Object) frames.get(i));
            }
            return new Timeline(indices, times, (int) ROW_SIZE.invokeExact(info), (boolean) INTERPOLATE.invokeExact(info));
        } catch (Throwable t) {
            return null;
        }
    }
    //?}
}
