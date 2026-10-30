package net.bichal.mods.tessera.config;

//? if neoforge
import net.neoforged.neoforge.common.ModConfigSpec;


public final class Config {

    //? if neoforge
    public static final ModConfigSpec SPEC;

    public static final Setting<Integer> COMPRESSION_QUALITY;
    public static final Setting<Integer> DEDUP_SIMILARITY_THRESHOLD;
    public static final Setting<Integer> VRAM_BUDGET_TARGET_MB;
    public static final Setting<String> CACHE_DIRECTORY;
    public static final Setting<Boolean> DISABLE_NATIVE_COMPRESSION;
    public static final Setting<Boolean> COMPRESS_ANIMATED_ATLASES;
    public static final Setting<Boolean> SHOW_EXTENDED_DEBUG_BREAKDOWN;

    //? if fabric {
    /*static {
        COMPRESSION_QUALITY = FabricConfig.integer("compression.compressionQuality", 4, 0, 7,
                "BC7 compression quality preset, 0 (fastest, lowest fidelity) to 7 (slowest, highest fidelity).");
        DISABLE_NATIVE_COMPRESSION = FabricConfig.bool("compression.disableNativeCompression", false,
                "Disables all atlas compression (native and pure-Java): atlases stay vanilla RGBA8.");
        COMPRESS_ANIMATED_ATLASES = FabricConfig.bool("compression.compressAnimatedAtlases", false,
                "Also block-compress atlases that contain animated textures. Off keeps them RGBA8888. Ignored while Kerria is installed.");
        DEDUP_SIMILARITY_THRESHOLD = FabricConfig.integer("deduplication.dedupSimilarityThreshold", 6, 0, 64,
                "Maximum Hamming distance, 0 to 64, between two 64-bit pHash fingerprints for sprites to count as duplicates.");
        VRAM_BUDGET_TARGET_MB = FabricConfig.integer("vramBudget.vramBudgetTargetMb", 2048, 256, 16384,
                "Advisory VRAM target in megabytes for the compressed atlas; over it only logs a warning.");
        CACHE_DIRECTORY = FabricConfig.string("cache.cacheDirectory", "tessera-cache",
                "Cache directory for compressed atlas data, relative to the game directory.");
        SHOW_EXTENDED_DEBUG_BREAKDOWN = FabricConfig.bool("debug.showExtendedDebugBreakdown", false,
                "Displays the expanded breakdown by atlas and by bucket on the F3 Debug Screen.");
    }
    *///?} else {
    private static <T> Setting<T> wrap(ModConfigSpec.ConfigValue<T> value) {
        return new Setting<>(value::get, value::getDefault, value::set, value::save);
    }

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

        builder.push("compression");

        COMPRESSION_QUALITY = wrap(builder
                .comment(
                        "BC7 compression quality preset, 0 (fastest, lowest fidelity) to 7 (slowest, highest fidelity).",
                        "Maps to a fixed preset table in the native encoder; the pure-Java fallback ignores it."
                )
                .translation("tessera.configuration.compressionQuality")
                .defineInRange("compressionQuality", 4, 0, 7));

        DISABLE_NATIVE_COMPRESSION = wrap(builder
                .comment("Disables all atlas compression (native and pure-Java): atlases stay vanilla RGBA8.")
                .translation("tessera.configuration.disableNativeCompression")
                .define("disableNativeCompression", false));

        COMPRESS_ANIMATED_ATLASES = wrap(builder
                .comment(
                        "Also block-compress atlases that contain animated textures (frames are then written as BC blocks).",
                        "Off by default: those atlases stay RGBA8888. Ignored while Kerria is installed."
                )
                .translation("tessera.configuration.compressAnimatedAtlases")
                .define("compressAnimatedAtlases", false));

        builder.pop();

        builder.push("deduplication");

        DEDUP_SIMILARITY_THRESHOLD = wrap(builder
                .comment(
                        "Maximum Hamming distance, 0 to 64, between two 64-bit pHash fingerprints for two sprites",
                        "to be treated as duplicates. Lower is stricter."
                )
                .translation("tessera.configuration.dedupSimilarityThreshold")
                .defineInRange("dedupSimilarityThreshold", 6, 0, 64));

        builder.pop();

        builder.push("vramBudget");

        VRAM_BUDGET_TARGET_MB = wrap(builder
                .comment(
                        "Advisory VRAM target in megabytes for the compressed atlas. Evaluated once per",
                        "resource-pack or mod-list reload, not polled continuously at runtime.",
                        "BC1/BC7 are both fixed bits-per-pixel formats, so this cannot be enforced by",
                        "lowering compressionQuality -- an atlas over this budget is logged as a warning",
                        "only; lower dedupSimilarityThreshold or accept the overage."
                )
                .translation("tessera.configuration.vramBudgetTargetMb")
                .defineInRange("vramBudgetTargetMb", 2048, 256, 16384));

        builder.pop();

        builder.push("cache");

        CACHE_DIRECTORY = wrap(builder
                .comment("Cache directory for compressed atlas data, relative to the game directory.")
                .translation("tessera.configuration.cacheDirectory")
                .define("cacheDirectory", "tessera-cache"));

        builder.pop();

        builder.push("debug");

        SHOW_EXTENDED_DEBUG_BREAKDOWN = wrap(builder
                .comment("Displays the expanded breakdown by atlas and by bucket on the F3 Debug Screen.")
                .translation("tessera.configuration.showExtendedDebugBreakdown")
                .define("showExtendedDebugBreakdown", false));

        builder.pop();

        SPEC = builder.build();
    }
    //?}

    private Config() {
    }

    public record ReloadSensitiveSnapshot(String cacheDirectory, int compressionQuality) {
        public static ReloadSensitiveSnapshot capture() {
            return new ReloadSensitiveSnapshot(get(CACHE_DIRECTORY), get(COMPRESSION_QUALITY));
        }
    }

    /**
     * The value, or its default while the client config is not loaded yet: some
     * versions (1.21.7) stitch atlases before NeoForge loads client configs.
     */
    public static <T> T get(Setting<T> value) {
        //? if neoforge {
        return SPEC.isLoaded() ? value.get() : value.getDefault();
        //?} else
        /*return value.get();*/
    }
}
