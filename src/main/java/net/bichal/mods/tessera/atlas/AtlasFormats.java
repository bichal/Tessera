package net.bichal.mods.tessera.atlas;

import net.bichal.mods.tessera.compress.CompressionPipeline.Target;
import net.bichal.mods.tessera.compress.backend.TesseraRuntime;

import java.util.List;
import java.util.function.Predicate;

/**
 * Which block format an atlas gets, from what it holds and what this machine can sample.
 *
 * <p>Opaque atlases take the smallest format with the least loss: BC1 (4 bits per texel) on
 * desktop GPUs, else ASTC 6x6 (3.56 bits) on mobile and Apple GPUs. Atlases whose texels are all
 * either fully opaque or fully transparent, with no mip levels, take BC1 with one-bit alpha (4 bits,
 * alpha exact). Atlases with partial transparency need full alpha: BC7, else ASTC 4x4 (both 8 bits).
 * Each list falls back to the larger formats when the smaller ones are not available, so every GPU
 * that has any of them gets something.
 *
 * <p>Mipmapped atlases never take one-bit alpha: averaging a sprite's edge into its mip levels
 * produces partial alpha, which that format would round to 0 or 255.
 */
public final class AtlasFormats {

    /** What the alpha channel of an atlas's sprites holds. */
    public enum Alpha {
        /** Every texel has alpha 255. */
        OPAQUE,
        /** Every texel has alpha 0 or 255. */
        BINARY,
        /** Some texel has alpha strictly between 0 and 255. */
        BLENDED;

        /** Folds one texel's alpha into what is known so far. */
        public Alpha with(int alpha) {
            if (alpha == 0xFF || this == BLENDED) {
                return this;
            }
            return alpha == 0 ? BINARY : BLENDED;
        }
    }

    private static final List<Target> OPAQUE = List.of(Target.BC1, Target.ASTC_6X6, Target.BC7, Target.ASTC_4X4);
    private static final List<Target> BINARY = List.of(Target.BC1_ALPHA, Target.BC7, Target.ASTC_4X4);
    private static final List<Target> ALPHA = List.of(Target.BC7, Target.ASTC_4X4);

    private AtlasFormats() {
    }

    /** The format for an atlas on this machine, or {@code null} when it can sample none of them. */
    public static Target choose(boolean opaque) {
        return choose(opaque ? Alpha.OPAQUE : Alpha.BLENDED, 0, AtlasFormats::usable);
    }

    /**
     * The format for an atlas on this machine, or {@code null} when it can sample none of them.
     *
     * @param mipLevel the atlas's highest mip level (0: no mipmaps)
     */
    public static Target choose(Alpha alpha, int mipLevel) {
        return choose(alpha, mipLevel, AtlasFormats::usable);
    }

    /** {@link #choose(boolean)} with the machine's capabilities given, so the preference order can be tested. */
    public static Target choose(boolean opaque, Predicate<Target> usable) {
        return choose(opaque ? Alpha.OPAQUE : Alpha.BLENDED, 0, usable);
    }

    /** {@link #choose(Alpha, int)} with the machine's capabilities given, so the preference order can be tested. */
    public static Target choose(Alpha alpha, int mipLevel, Predicate<Target> usable) {
        List<Target> order = switch (alpha) {
            case OPAQUE -> OPAQUE;
            case BINARY -> mipLevel == 0 ? BINARY : ALPHA;
            case BLENDED -> ALPHA;
        };
        for (Target target : order) {
            if (usable.test(target)) {
                return target;
            }
        }
        return null;
    }

    /**
     * The format to fall back to when {@code target} was refused for an image (BC1 with one-bit
     * alpha below its quality floor), or {@code null} when there is none.
     */
    public static Target fallback(Target target) {
        if (target == Target.BC1_ALPHA) {
            for (Target alpha : ALPHA) {
                if (usable(alpha)) {
                    return alpha;
                }
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
