package net.bichal.mods.tessera.compat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Detection of other mods that touch the same textures as Tessera.
 *
 * <p>Kerria replaces how animation frames reach the atlas (GPU-side copies of
 * cached frames, persistently mapped upload buffers). Both assume the atlas is
 * plain RGBA8. Tessera splits atlases in two and only compresses the static
 * ones (no animated sprite), so every atlas Kerria writes frames into stays
 * exactly as vanilla uploaded it, with or without Kerria.
 */
public final class ModCompat {

    private static final Logger LOGGER = LoggerFactory.getLogger("Tessera/ModCompat");

    /** Mod ids Kerria is published under (Modrinth slug {@code kerria-opt}, CurseForge slug {@code kerria}). */
    private static final String[] KERRIA_IDS = {"kerria", "kerria_opt", "kerriaopt"};

    private static volatile Boolean kerria;

    private ModCompat() {
    }

    /** Whether Kerria is installed. Decided once; the mod list does not change while the game runs. */
    public static boolean isKerriaLoaded() {
        Boolean known = kerria;
        if (known == null) {
            boolean found = false;
            for (String id : KERRIA_IDS) {
                if (isLoaded(id)) {
                    found = true;
                    break;
                }
            }
            if (found) {
                LOGGER.info("Kerria detected: atlases with animated textures stay uncompressed (RGBA8888).");
            }
            kerria = known = found;
        }
        return known;
    }

    /** Whether a mod with this id is installed on the running loader. */
    public static boolean isLoaded(String modId) {
        try {
            //? if fabric {
            /*return net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded(modId);
            *///?} else if >=1.21.10 {
            /*var loader = net.neoforged.fml.loading.FMLLoader.getCurrentOrNull();
            return loader != null && loader.getLoadingModList().getModFileById(modId) != null;
            *///?} else {
            var list = net.neoforged.fml.loading.LoadingModList.get();
            return list != null && list.getModFileById(modId) != null;
            //?}
        } catch (Throwable t) {
            LOGGER.debug("Could not look up mod '{}'", modId, t);
            return false;
        }
    }
}
