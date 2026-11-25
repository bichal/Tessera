package net.bichal.mods.tessera.compress.gpu;

import net.bichal.mods.tessera.compress.backend.TesseraRuntime;
import net.bichal.mods.tessera.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The experimental GPU encoding setting ({@code gpuCompression}): whether it is on, whether this GPU can
 * do it, and whether the encoder passed its startup check. Atlases only go through the GPU in state
 * {@link State#ACTIVE}; in every other state they are encoded on the CPU as usual.
 *
 * <p>{@link #refresh()} runs on the render thread (at startup and when the setting changes): it detects
 * the capabilities and runs {@link GpuEncoderCheck} once per session. The check needs the native BC7
 * decoder, so without the native bridge the GPU path stays off.
 */
public final class GpuCompression {

    public enum State {
        /** The setting is off. */
        OFF,
        /** The context lacks something the encoder needs (macOS, old drivers). */
        UNSUPPORTED,
        /** The native BC7 decoder the startup check needs did not load. */
        UNVERIFIABLE,
        /** The encoder failed its startup check this session. */
        FAILED,
        /** The encoder passed its startup check; atlases are not routed to it yet, so they stay on the CPU. */
        CHECKED,
        /** Encoding atlases on the GPU. */
        ACTIVE
    }

    public record Status(State state, String detail) {
    }

    private static final Logger LOGGER = LoggerFactory.getLogger("Tessera/GpuCompression");

    /** The GPU encoder of this build. */
    private static final GpuBc7Encoder ENCODER = new ComputeBc7Encoder();
    /** Whether atlas compression sends BC7 to the GPU encoder; until it does, a passed check is {@link State#CHECKED}. */
    private static final boolean ROUTED = false;

    private static volatile Status status = new Status(State.OFF, "");
    /** Outcome of the startup check, run at most once per session. */
    private static GpuEncoderCheck.Result checkResult;
    private static boolean warned;

    private GpuCompression() {
    }

    public static Status status() {
        return status;
    }

    /** The encoder to use, or {@code null} to encode on the CPU. */
    public static GpuBc7Encoder activeEncoder() {
        return status.state() == State.ACTIVE ? ENCODER : null;
    }

    /** Render thread only: re-evaluates the state after startup or a settings change and logs it. */
    public static void refresh() {
        Status previous = status;
        status = evaluate();
        if (status.state() != State.OFF && !warned) {
            warned = true;
            LOGGER.warn("GPU compression is EXPERIMENTAL: atlases encoded on the GPU may look worse, and some "
                    + "drivers may stutter or crash. Turn gpuCompression off if anything looks wrong.");
        }
        if (!status.equals(previous) && status.state() != State.OFF) {
            if (status.state() == State.ACTIVE) {
                LOGGER.info("GPU compression active: {} ({})", ENCODER.name(), status.detail());
            } else if (status.state() == State.CHECKED) {
                LOGGER.info("GPU encoder {} passed its startup check ({}); atlases are still encoded on the CPU "
                        + "in this build", ENCODER.name(), status.detail());
            } else {
                LOGGER.warn("GPU compression requested but not used, atlases are encoded on the CPU: {}",
                        status.detail());
            }
        }
    }

    private static Status evaluate() {
        if (!Config.get(Config.GPU_COMPRESSION)) {
            return new Status(State.OFF, "");
        }
        GpuCapabilities caps = GpuCapabilities.detect();
        if (!caps.supported()) {
            return new Status(State.UNSUPPORTED, caps.reason() + " on " + caps.renderer());
        }
        if (!TesseraRuntime.isNativeActive()) {
            return new Status(State.UNVERIFIABLE, "the native bridge (needed to check the GPU encoder) did not load");
        }
        if (checkResult == null) {
            checkResult = GpuEncoderCheck.run(ENCODER, TesseraRuntime::decodeBc7);
        }
        if (!checkResult.passed()) {
            return new Status(State.FAILED, "startup check failed: " + checkResult.detail());
        }
        return new Status(ROUTED ? State.ACTIVE : State.CHECKED, checkResult.detail() + ", "
                + String.format("%.1f ms", checkResult.nanos() / 1e6) + " for the test image");
    }

    /** F3 text: which encoder BC7 goes through, with the reason when the GPU was asked for and is not used. */
    public static String overlayLabel() {
        Status current = status;
        String cpu = TesseraRuntime.isNativeActive() ? "CPU (native)" : "CPU (Java)";
        return switch (current.state()) {
            case OFF -> cpu;
            case ACTIVE -> "§eGPU (experimental)§r";
            case UNSUPPORTED -> cpu + " §7(GPU unsupported)§r";
            case UNVERIFIABLE -> cpu + " §7(GPU: cannot check)§r";
            case FAILED -> cpu + " §c(GPU check failed)§r";
            case CHECKED -> cpu + " §7(GPU check passed, not used yet)§r";
        };
    }
}
