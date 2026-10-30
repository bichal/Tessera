package net.bichal.mods.tessera.compat;

import java.nio.file.Path;

/** Loader-specific lookups, in one place. */
public final class Platform {

    private Platform() {
    }

    /** The game directory ({@code .minecraft} or the instance folder). */
    public static Path gameDir() {
        //? if fabric {
        /*return net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir();
        *///?} else
        return net.neoforged.fml.loading.FMLPaths.GAMEDIR.get();
    }
}
