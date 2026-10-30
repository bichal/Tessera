package net.bichal.mods.tessera;

import net.bichal.mods.tessera.atlas.AtlasCompressionDriver;
import net.bichal.mods.tessera.compat.ModCompat;
import net.bichal.mods.tessera.compress.Bc1TextureFormatSupport;
import net.bichal.mods.tessera.compress.Bc7GpuSupport;
import net.bichal.mods.tessera.compress.backend.TesseraRuntime;
import net.bichal.mods.tessera.config.Config;
import net.bichal.mods.tessera.gui.KnownEngineBugLogFilter;
import net.bichal.mods.tessera.selftest.TesseraSelfTest;
import net.bichal.mods.tessera.vram.VramBudgetEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.minecraft.client.Minecraft;
//? if neoforge {
import net.bichal.mods.tessera.datagen.DataGenerators;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
//? >=1.21.10 {
/*import net.bichal.mods.tessera.gui.DebugOverlay;
*///?}
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
//?}

//? if neoforge
@Mod(value = Tessera.MOD_ID, dist = Dist.CLIENT)
public final class Tessera {

    public static final String MOD_ID = "tessera";
    private static final Logger LOGGER = LoggerFactory.getLogger("Tessera");

    /**
     * Startup shared by both loaders: log filter, compression backend and the self-test hook.
     */
    public static void init() {
        // Installed at mod construction rather than later: it only configures Log4j2 and
        // touches no GL state, and should run before the very first resource reload (and so
        // the first atlas stitch), which is when MC-293754's log spam starts. See
        // KnownEngineBugLogFilter's own class doc for what this suppresses.
        KnownEngineBugLogFilter.install();

        // Select the compression backend (native JNI or pure Java) up front,
        // so the choice is logged at startup rather than on the first reload.
        TesseraRuntime.backend();

        ModCompat.isKerriaLoaded();

        if (TesseraSelfTest.ENABLED) {
            TesseraSelfTest.register();
        }
    }

    /**
     * Fills the GL capability caches. Runs on the render thread: GL.getCapabilities() only
     * works on a thread with a current context, and these checks are later read from
     * background compression threads that have none.
     */
    public static void warmUpGl() {
        boolean bc7Supported = Bc7GpuSupport.isSupported();
        Bc1TextureFormatSupport.warmUp();
        VramBudgetEngine.warmUp();

        if (Bc7GpuSupport.isKnown() && !bc7Supported) {
            LOGGER.warn("This GPU/driver does not expose GL_COMPRESSED_RGBA_BPTC_UNORM (BC7); "
                    + "atlases stay uncompressed RGBA8.");
        }
        if (Bc1TextureFormatSupport.isKnown() && !Bc1TextureFormatSupport.isSupported()) {
            LOGGER.warn("This GPU/driver does not expose GL_EXT_texture_compression_s3tc (BC1).");
        }
    }

    /**
     * Applies a change of the reload-sensitive settings: rebuilds the cache against a new
     * directory and reloads resources so every atlas is compressed again. Both loaders call it
     * (NeoForge from its config reload event, Fabric from the settings screen).
     */
    public static void applySettingsChange(Config.ReloadSensitiveSnapshot previous, Config.ReloadSensitiveSnapshot current) {
        if (current.equals(previous)) {
            return;
        }

        if (!current.cacheDirectory().equals(previous.cacheDirectory())) {
            AtlasCompressionDriver.invalidateCache();
        }

        LOGGER.info("Tessera settings changed in a way that affects atlas compression ({} -> {}); "
                + "reloading resources to apply.", previous, current);

        // The change may come from a thread other than the render thread (NeoForge's config file
        // watcher), and Minecraft's resource-reload entry point asserts render-thread ownership,
        // so it always hops through Minecraft#execute.
        Minecraft.getInstance().execute(() -> Minecraft.getInstance().reloadResourcePacks());
    }

    //? if neoforge {
    /**
     * Snapshot of the reload-sensitive settings as of the last time they
     * were known-applied (mod construction, or the last
     * {@link #onConfigReloading} that acted on a change). Compared against
     * the just-reloaded values in {@link #onConfigReloading} to decide
     * whether a resource reload is actually warranted -- see
     * {@link Config.ReloadSensitiveSnapshot} for which settings this
     * covers and why only those two.
     */
    private Config.ReloadSensitiveSnapshot lastAppliedSettings;

    public Tessera(IEventBus modEventBus, ModContainer modContainer) {
        modContainer.registerConfig(ModConfig.Type.CLIENT, Config.SPEC);

        modContainer.registerExtensionPoint(
                IConfigScreenFactory.class,
                ConfigurationScreen::new
        );

        init();

        modEventBus.addListener(this::onConfigLoading);

        modEventBus.addListener(this::onClientSetup);
        modEventBus.addListener(this::onConfigReloading);
        //? >=1.21.10 {
        /*// NeoForge's debug-entry event arrived in 21.10; the 1.21.10 build also runs on 1.21.7 - 1.21.9.
        if (net.bichal.mods.tessera.compat.TesseraCompat.hasClass(
                "net.neoforged.neoforge.client.event.RegisterDebugEntriesEvent")) {
            modEventBus.addListener(DebugOverlay::registerDebugEntries);
        }
        *///?}

        DataGenerators.register(modEventBus);

    }

    private void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(Tessera::warmUpGl);
    }

    private void onConfigLoading(ModConfigEvent.Loading event) {
        if (event.getConfig().getSpec() == Config.SPEC) {
            this.lastAppliedSettings = Config.ReloadSensitiveSnapshot.capture();
        }
    }

    /**
     * Fires whenever {@link Config#SPEC}'s backing file changes on disk,
     * including the {@code ConfigurationScreen}'s save-on-close path.
     * Triggers a client resource reload only when one of
     * {@link Config.ReloadSensitiveSnapshot}'s two settings actually
     * changed value -- {@link Config#CACHE_DIRECTORY} (so
     * {@link AtlasCompressionDriver#cache()} rebuilds against the new
     * directory instead of staying pinned to whichever one was first read
     * this session) and {@link Config#DISABLE_ANIMATIONS} (so
     * {@code SpriteRoutingMixin} re-evaluates which sprites are eligible
     * for the static split). Every other setting in this spec is either
     * read fresh next time it's needed with no reload required
     * ({@code compressionQuality}, {@code vramBudgetTargetMb}, dedup
     * settings) or purely cosmetic ({@code showExtendedDebugBreakdown}) --
     * forcing a reload for those would stall a frame on every settings
     * screen close for no behavioral change.
     */
    @SuppressWarnings("JavadocReference")
    private void onConfigReloading(ModConfigEvent.Reloading event) {
        if (event.getConfig().getSpec() != Config.SPEC) {
            return;
        }

        Config.ReloadSensitiveSnapshot current = Config.ReloadSensitiveSnapshot.capture();
        Config.ReloadSensitiveSnapshot previous = this.lastAppliedSettings;
        this.lastAppliedSettings = current;

        applySettingsChange(previous, current);
    }
    //?}
}
