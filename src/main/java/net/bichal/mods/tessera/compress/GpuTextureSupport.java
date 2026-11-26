package net.bichal.mods.tessera.compress;

import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLCapabilities;

import java.util.function.Predicate;

/**
 * Whether the driver takes each compressed format Tessera uploads.
 *
 * <p>{@code GL.getCapabilities()} is thread-local to the thread with a current GL context, and
 * atlas work can reach these checks from a background executor. {@link #warmUp()} therefore runs on
 * the render thread first; a call that finds no context returns {@code false} without caching it,
 * so a background thread never memoizes a false negative.
 */
public enum GpuTextureSupport {
    /** S3TC never became core GL, so the extension is the only signal. */
    BC1(caps -> caps.GL_EXT_texture_compression_s3tc),
    /** BPTC: core since GL 4.2. */
    BC7(caps -> caps.OpenGL42 || caps.GL_ARB_texture_compression_bptc),
    /** ASTC LDR: mobile and Apple GPUs (Metal- or Vulkan-based GL layers); desktop GPUs mostly lack it. */
    ASTC(caps -> caps.GL_KHR_texture_compression_astc_ldr);

    private final Predicate<GLCapabilities> check;
    private volatile Boolean supported;

    GpuTextureSupport(Predicate<GLCapabilities> check) {
        this.check = check;
    }

    /** Detects now, on the render thread; a no-op once cached. */
    public void warmUp() {
        isSupported();
    }

    /** Whether a GL context has answered yet (setup work may run off the render thread, e.g. 26.3). */
    public boolean isKnown() {
        return supported != null;
    }

    public boolean isSupported() {
        Boolean cached = supported;
        return cached != null ? cached : detectAndCache();
    }

    private synchronized boolean detectAndCache() {
        if (supported != null) {
            return supported;
        }
        boolean found;
        try {
            found = check.test(GL.getCapabilities());
        } catch (Throwable ignored) {
            return false; // no GL context on this thread yet
        }
        supported = found;
        return found;
    }
}
