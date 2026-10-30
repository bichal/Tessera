package net.bichal.mods.tessera.atlas;

import net.bichal.mods.tessera.compress.CompressionPipeline.Target;
import net.bichal.mods.tessera.compress.backend.TesseraRuntime;

import java.util.List;
import java.util.function.Predicate;

/**
 * Which block format an atlas gets, from what it holds and what this machine can sample.
 *
 * <p>Opaque atlases take the smallest format with the least loss: BC1 (4 bits per texel) on
 * desktop GPUs, else ASTC 6x6 (3.56 bits) on mobile and Apple GPUs. Atlases with transparency need
 * alpha: BC7, else ASTC 4x4 (both 8 bits). Opaque atlases fall back to the alpha formats when
 * the cheaper ones are not available, so every GPU that has any of the four gets something.
 */
public final class AtlasFormats {

    private static final List<Target> OPAQUE = List.of(Target.BC1, Target.ASTC_6X6, Target.BC7, Target.ASTC_4X4);
    private static final List<Target> ALPHA = List.of(Target.BC7, Target.ASTC_4X4);

    private AtlasFormats() {
    }

    /** The format for an atlas on this machine, or {@code null} when it can sample none of them. */
    public static Target choose(boolean opaque) {
        return choose(opaque, AtlasFormats::usable);
    }

    /** {@link #choose(boolean)} with the machine's capabilities given, so the preference order can be tested. */
    public static Target choose(boolean opaque, Predicate<Target> usable) {
        for (Target target : opaque ? OPAQUE : ALPHA) {
            if (usable.test(target)) {
                return target;
            }
        }
        return null;
    }

    /**
     * -Dtessera.formats=astc or bc limits Tessera to one family of formats (default: both), to try an ARM
     * GPU's path on a desktop one or the other way round.
     */
    private static final String FAMILY = System.getProperty("tessera.formats", "auto");

    /** The GPU takes the format and some backend can encode it. */
    private static boolean usable(Target target) {
        if (FAMILY.equalsIgnoreCase("astc") && !target.isAstc() || FAMILY.equalsIgnoreCase("bc") && target.isAstc()) {
            return false;
        }
        if (!target.gpuSupported()) {
            return false;
        }
        return !target.isAstc()
                || TesseraRuntime.backend().supportsAstc() || TesseraRuntime.softwareBackend().supportsAstc();
    }
}
