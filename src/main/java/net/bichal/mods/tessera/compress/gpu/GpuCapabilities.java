package net.bichal.mods.tessera.compress.gpu;

import net.bichal.mods.tessera.compress.GpuTextureSupport;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;

/**
 * What a GPU BC7 encoder needs from the context: compute shaders, image load/store and shader storage
 * buffers to run it, {@code glCopyImageSubData} to move its output (one RGBA32UI texel per block) into
 * a BC7 texture without a round trip through the CPU, and BC7 itself. All core in OpenGL 4.3, which
 * macOS never reaches (it stops at 4.1), so there it is always unsupported.
 *
 * <p>{@code GL.getCapabilities()} belongs to the thread that has the context: {@link #detect()} runs on
 * the render thread, everything else reads the cached result.
 */
public record GpuCapabilities(boolean supported, String reason, String renderer) {

    private static volatile GpuCapabilities detected;

    /** Render thread only. Detects once and caches; later calls return the cached result. */
    public static GpuCapabilities detect() {
        GpuCapabilities cached = detected;
        if (cached != null) {
            return cached;
        }
        GpuCapabilities result;
        try {
            var caps = GL.getCapabilities();
            String renderer = String.valueOf(GL11.glGetString(GL11.GL_RENDERER));
            if (!(caps.OpenGL43 || caps.GL_ARB_compute_shader)) {
                result = new GpuCapabilities(false, "no compute shaders (needs OpenGL 4.3)", renderer);
            } else if (!(caps.OpenGL42 || caps.GL_ARB_shader_image_load_store)) {
                result = new GpuCapabilities(false, "no image load/store", renderer);
            } else if (!(caps.OpenGL43 || caps.GL_ARB_shader_storage_buffer_object)) {
                result = new GpuCapabilities(false, "no shader storage buffers", renderer);
            } else if (!(caps.OpenGL43 || caps.GL_ARB_copy_image)) {
                result = new GpuCapabilities(false, "no glCopyImageSubData", renderer);
            } else if (!GpuTextureSupport.BC7.isSupported()) {
                result = new GpuCapabilities(false, "the driver has no BC7", renderer);
            } else {
                result = new GpuCapabilities(true, "", renderer);
            }
        } catch (Throwable noContext) {
            // No context on this thread: not cached, the render thread answers later.
            return new GpuCapabilities(false, "not detected yet", "");
        }
        detected = result;
        return result;
    }

    /** The cached result, or {@code null} before the render thread detected it. */
    public static GpuCapabilities cached() {
        return detected;
    }
}
