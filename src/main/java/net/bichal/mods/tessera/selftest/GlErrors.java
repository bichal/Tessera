package net.bichal.mods.tessera.selftest;

import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL43;
import org.lwjgl.opengl.GLDebugMessageCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * OpenGL errors during the self-test, each with the Java stack that caused it (synchronous debug
 * output). Mesa answers a bad call with an error where some drivers crash, so every error fails the run.
 */
final class GlErrors {

    private static final Logger LOGGER = LoggerFactory.getLogger("Tessera/SelfTest");
    private static final int MAX_KEPT = 8;
    private static final List<String> ERRORS = new ArrayList<>();
    private static int count;
    private static boolean installed;
    private static GLDebugMessageCallback callback;

    private GlErrors() {
    }

    static void install() {
        if (!GL.getCapabilities().OpenGL43 && !GL.getCapabilities().GL_KHR_debug) {
            LOGGER.warn("Tessera self-test: no GL debug output, GL errors are not checked");
            return;
        }
        callback = GLDebugMessageCallback.create((source, type, id, severity, length, message, user) -> {
            if (type != GL43.GL_DEBUG_TYPE_ERROR) {
                return;
            }
            count++;
            if (ERRORS.size() < MAX_KEPT) {
                StringWriter stack = new StringWriter();
                new Throwable(GLDebugMessageCallback.getMessage(length, message)).printStackTrace(new PrintWriter(stack));
                ERRORS.add(stack.toString());
                LOGGER.error("GL error: {}", stack);
            }
        });
        GL11.glEnable(GL43.GL_DEBUG_OUTPUT);
        GL11.glEnable(GL43.GL_DEBUG_OUTPUT_SYNCHRONOUS);
        GL43.glDebugMessageCallback(callback, 0L);
        installed = true;
    }

    /** Adds the errors seen since the last report; false when there was any. */
    static boolean report(List<String> out) {
        if (!installed) {
            out.add("GL errors: not checked (no debug output)");
            return true;
        }
        out.add("GL errors: " + count);
        for (String error : ERRORS) {
            for (String line : error.split("\\R")) {
                out.add("  " + line);
            }
        }
        boolean ok = count == 0;
        count = 0;
        ERRORS.clear();
        return ok;
    }
}
