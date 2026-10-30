package net.bichal.mods.tessera.compress;

import org.lwjgl.opengl.GL;

/**
 * Whether the driver takes ASTC LDR textures ({@code GL_KHR_texture_compression_astc_ldr}): the
 * compressed format of mobile and Apple GPUs, so what Tessera uses on Android launchers and on
 * Apple Silicon when the OpenGL implementation underneath exposes it (a Metal- or Vulkan-based
 * one does; desktop GPUs mostly do not). Same pattern, and same render-thread rule, as
 * {@link Bc7GpuSupport}: GL.getCapabilities() belongs to the thread with the context, so
 * {@link #warmUp()} runs on the render thread first and background callers read the cached answer.
 */
public final class AstcGpuSupport {

    private static volatile Boolean supported;

    private AstcGpuSupport() {
    }

    public static void warmUp() {
        isSupported();
    }

    /** Whether a GL context has answered yet. */
    public static boolean isKnown() {
        return supported != null;
    }

    public static boolean isSupported() {
        Boolean cached = supported;
        if (cached != null) {
            return cached;
        }
        return detectAndCache();
    }

    private static synchronized boolean detectAndCache() {
        if (supported != null) {
            return supported;
        }
        boolean found;
        try {
            found = GL.getCapabilities().GL_KHR_texture_compression_astc_ldr;
        } catch (Throwable ignored) {
            // No GL context on this thread yet: do not cache a false negative.
            return false;
        }
        supported = found;
        return found;
    }
}
