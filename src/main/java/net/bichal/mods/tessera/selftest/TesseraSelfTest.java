package net.bichal.mods.tessera.selftest;

import net.bichal.mods.tessera.cache.AtlasCache;
import net.bichal.mods.tessera.compat.ModCompat;
import net.bichal.mods.tessera.compat.TesseraCompat;
import net.bichal.mods.tessera.compress.CompressedAtlases;
import net.bichal.mods.tessera.compress.Bc7GpuSupport;
import net.bichal.mods.tessera.compress.CompressionPipeline;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
//? if neoforge {
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
//?}
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL21;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Client self-test ({@code -Dtessera.selftest=true}, Gradle task {@code selftest}):
 * once the first resource reload has been compressed, reads every compressed
 * atlas back through OpenGL and checks it against the sprites it was built from,
 * then quits. Runs headless in CI (Xvfb + Mesa).
 *
 * <ul>
 *   <li>every mip level of the texture holds the compressed format (a level left
 *       uncompressed makes the texture incomplete: black in game);</li>
 *   <li>level 0, decoded by the driver, matches the static sprites (PSNR; small
 *       icon atlases such as mob_effects land near 29-31 dB at the default quality,
 *       a broken or misplaced upload well under 20);</li>
 *   <li>an atlas that holds animated sprites was left as RGBA8: animations are never
 *       compressed (vanilla, or Kerria, writes their frames).</li>
 * </ul>
 * Writes {@code tessera-selftest.txt} in the game directory; its first line ends
 * with PASS or FAIL.
 */
public final class TesseraSelfTest {

    public static final boolean ENABLED = Boolean.getBoolean("tessera.selftest");

    private static final Logger LOGGER = LoggerFactory.getLogger("Tessera/SelfTest");
    private static final int SETTLE_TICKS = 200, GIVE_UP_TICKS = 6000;
    private static final double MIN_STATIC_PSNR = 28.0;
    // BC1 stores 4 bits per texel (BC7: 8): detailed opaque atlases such as paintings land near 25 dB.
    private static final double MIN_STATIC_PSNR_BC1 = 24.0;
    private static final int GL_COMPRESSED_RGBA_BPTC_UNORM = 0x8E8C;
    private static final int GL_COMPRESSED_RGB_S3TC_DXT1 = 0x83F0, GL_COMPRESSED_RGBA_S3TC_DXT1 = 0x83F1;

    // Compressed atlases come from CompressedAtlases, not AtlasCompressEvent.Post:
    // on 1.21 - 1.21.1 the first reload runs during mod loading, when NeoForge's bus
    // still drops events, so the early atlases' events never arrive.
    private static int seenRegistrations = -1;
    private static int ticks, lastCompressedTick = -1;
    private static boolean done;

    private TesseraSelfTest() {
    }

    public static void register() {
        //? if fabric {
        /*net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(
                client -> { if (WORLD_SHOT) worldShotTick(); else if (SCREEN_PREVIEW) screenPreviewTick(); else tick(); });
        *///?} else
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, e -> { if (WORLD_SHOT) worldShotTick(); else tick(); });
    }

    /**
     * -Dtessera.selftest.world=<commands file>: opens (creating it on the first run) a creative superflat
     * world, builds a showcase of textures with the commands of that file, stands the player in front of
     * it and leaves {@code tessera-world-ready} in the game directory for a screenshot. 1.21.1 only.
     */
    private static final String WORLD_SHOT_COMMANDS = System.getProperty("tessera.selftest.world");
    private static final boolean WORLD_SHOT = WORLD_SHOT_COMMANDS != null;
    private static final String WORLD_NAME = "tessera-shot";
    private static int worldJoinedTick = -1;
    private static List<String> worldCommands;

    private static void worldShotTick() {
        //? <1.21.10 {
        ticks++;
        Minecraft mc = Minecraft.getInstance();
        try {
            if (mc.level == null || mc.player == null) {
                if (ticks == 150) {
                    var flows = mc.createWorldOpenFlows();
                    if (mc.getLevelSource().levelExists(WORLD_NAME)) {
                        flows.openWorld(WORLD_NAME, () -> LOGGER.error("Tessera world shot: the world did not open"));
                    } else {
                        var settings = new net.minecraft.world.level.LevelSettings(WORLD_NAME,
                                net.minecraft.world.level.GameType.CREATIVE, false, net.minecraft.world.Difficulty.PEACEFUL,
                                true, new net.minecraft.world.level.GameRules(),
                                net.minecraft.world.level.WorldDataConfiguration.DEFAULT);
                        flows.createFreshLevel(WORLD_NAME, settings, net.minecraft.world.level.levelgen.WorldOptions.defaultWithRandomSeed(),
                                access -> access.lookupOrThrow(net.minecraft.core.registries.Registries.WORLD_PRESET)
                                        .getOrThrow(net.minecraft.world.level.levelgen.presets.WorldPresets.FLAT).value()
                                        .createWorldDimensions(),
                                mc.screen);
                    }
                }
                return;
            }
            if (worldJoinedTick < 0) {
                worldJoinedTick = ticks;
                worldCommands = Files.readAllLines(java.nio.file.Path.of(WORLD_SHOT_COMMANDS));
                LOGGER.info("Tessera world shot: in the world, running {} commands", worldCommands.size());
            }
            int since = ticks - worldJoinedTick;
            // One command every third tick (the server drops commands sent faster), after the world has settled.
            int steps = worldCommands.size() * 3;
            if (since >= 100 && since < 100 + steps) {
                if ((since - 100) % 3 == 0) {
                    String command = worldCommands.get((since - 100) / 3).strip();
                    if (!command.isEmpty()) {
                        mc.player.connection.sendCommand(command);
                    }
                }
            } else if (since == 100 + steps + 500) {
                Files.writeString(mc.gameDirectory.toPath().resolve("tessera-world-ready"), "ready");
            } else if (since == 100 + steps + 620) {
                mc.stop();
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.error("Tessera world shot failed", e);
            mc.stop();
        }
        //?} else
        if (ticks++ == 0) LOGGER.warn("Tessera world shot: only built for 1.21.1");
    }

    /** Fabric: -Dtessera.selftest.screen=true opens the settings screen, marks the game directory, then closes it. */
    private static final boolean SCREEN_PREVIEW = Boolean.getBoolean("tessera.selftest.screen");

    //? if fabric
    /*private static net.bichal.mods.tessera.client.TesseraConfigScreen settingsScreen;*/

    private static void screenPreviewTick() {
        //? if fabric {
        /*ticks++;
        Minecraft mc = Minecraft.getInstance();
        try {
            var game = mc.gameDirectory.toPath();
            if (ticks == 400) {
                settingsScreen = new net.bichal.mods.tessera.client.TesseraConfigScreen(mc.screen);
                mc.setScreen(settingsScreen);
            } else if (ticks == 460) {
                Files.writeString(game.resolve("tessera-screen-open"), "open");
            } else if (ticks == 700) {
                mc.setScreen(settingsScreen.sectionScreen(0));
            } else if (ticks == 760) {
                Files.writeString(game.resolve("tessera-screen-section"), "section");
            } else if (ticks == 1000) {
                settingsScreen.onClose(); // writes the settings and returns to the previous screen
                Files.writeString(game.resolve("tessera-screen-closed"), "closed");
            } else if (ticks == 1060) {
                mc.stop();
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.error("Tessera settings screen preview failed", e);
            mc.stop();
        }
        *///?}
    }

    private static void tick() {
        if (done) {
            return;
        }
        ticks++;
        Minecraft mc = Minecraft.getInstance();
        if (CompressedAtlases.registrations() != seenRegistrations) {
            seenRegistrations = CompressedAtlases.registrations();
            lastCompressedTick = ticks;
        }
        // Atlases with animations stay RGBA8, so the block atlas is never among the compressed ones:
        // settle once some atlas was compressed and no more arrive.
        boolean settled = !overlayShown(mc) && !CompressedAtlases.all().isEmpty()
                && ticks - lastCompressedTick >= SETTLE_TICKS;
        if (!settled && ticks < GIVE_UP_TICKS) {
            return;
        }
        done = true;
        List<String> lines = new ArrayList<>();
        boolean pass;
        try {
            pass = check(lines);
        } catch (Throwable t) {
            LOGGER.error("Tessera self-test crashed", t);
            lines.add("crashed: " + t);
            pass = false;
        }
        lines.add(0, "tessera selftest " + System.getProperty("tessera.selftest.version", "?") + ": " + (pass ? "PASS" : "FAIL"));
        lines.forEach(LOGGER::info);
        try {
            Files.write(mc.gameDirectory.toPath().resolve("tessera-selftest.txt"), lines);
        } catch (IOException e) {
            LOGGER.error("Tessera self-test: cannot write the report", e);
        }
        mc.stop();
    }

    private static boolean check(List<String> out) {
        if (!Bc7GpuSupport.isSupported()) {
            out.add("the GL driver has no BC7 (" + GL11.glGetString(GL11.GL_RENDERER) + ")");
            return false;
        }
        out.add("renderer " + GL11.glGetString(GL11.GL_RENDERER) + ", " + GL11.glGetString(GL11.GL_VERSION));
        Map<TextureAtlas, CompressionPipeline.Target> compressed = CompressedAtlases.all();
        if (compressed.isEmpty()) {
            out.add("no atlas was compressed within " + GIVE_UP_TICKS + " ticks");
            return false;
        }
        boolean ok = true;
        // Animated textures stay RGBA8888: no compressed atlas may hold an animated sprite...
        for (TextureAtlas atlas : compressed.keySet()) {
            if (TesseraCompat.textures(atlas).stream().anyMatch(sprite -> TesseraCompat.isAnimated(sprite.contents()))) {
                out.add(atlas.location() + " holds animated sprites but was compressed");
                ok = false;
            }
        }
        // ...and the atlases that were skipped for holding some are still plain RGBA8.
        for (TextureAtlas atlas : CompressedAtlases.keptForAnimations()) {
            TesseraCompat.bindTexture(TesseraCompat.glId(atlas));
            int format = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_INTERNAL_FORMAT);
            boolean rgba = format == GL11.GL_RGBA8 || format == GL11.GL_RGBA; // vanilla uploads with the unsized GL_RGBA
            out.add(String.format("%s has animations: internal format 0x%X (%s)", atlas.location(), format,
                    rgba ? "RGBA8888, as expected" : "NOT RGBA8888"));
            ok &= rgba;
        }
        out.add("Kerria installed: " + ModCompat.isKerriaLoaded());
        for (var entry : compressed.entrySet()) {
            ok &= checkAtlas(entry.getKey().location().toString(), entry.getKey(), entry.getValue(), out);
        }
        return ok;
    }

    private static boolean isBlocks(String atlasName) {
        return atlasName.endsWith("textures/atlas/blocks.png");
    }

    private static boolean checkAtlas(String name, TextureAtlas atlas, CompressionPipeline.Target expectedFormat,
                                      List<String> out) {
        int glId = TesseraCompat.glId(atlas);
        TesseraCompat.bindTexture(glId);
        int width = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH);
        int height = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT);
        int maxLevel = GL11.glGetTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LEVEL);
        boolean ok = true;

        // Every level the sampler may reach must exist, compressed, at the right size.
        int levels = 0;
        for (int level = 0; level <= Math.min(maxLevel, 16); level++) {
            int w = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, level, GL11.GL_TEXTURE_WIDTH);
            int h = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, level, GL11.GL_TEXTURE_HEIGHT);
            if (w == 0 && h == 0 && level > 0 && (width >> level) == 0 && (height >> level) == 0) {
                break;
            }
            int format = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, level, GL11.GL_TEXTURE_INTERNAL_FORMAT);
            boolean expected = expectedFormat == CompressionPipeline.Target.BC1
                    ? format == GL_COMPRESSED_RGB_S3TC_DXT1 || format == GL_COMPRESSED_RGBA_S3TC_DXT1
                    : format == GL_COMPRESSED_RGBA_BPTC_UNORM;
            if (!expected || w != Math.max(1, width >> level) || h != Math.max(1, height >> level)) {
                out.add(String.format("  %s level %d: %dx%d format 0x%X (expected %s %dx%d)", name, level, w, h, format,
                        expectedFormat, Math.max(1, width >> level), Math.max(1, height >> level)));
                ok = false;
            }
            levels++;
        }

        ByteBuffer pixels = readLevel0(width, height);
        try {
            // The decoded atlas against the sprites' own pixels.
            Error staticError = new Error();
            for (TextureAtlasSprite sprite : TesseraCompat.textures(atlas)) {
                compare(pixels, width, sprite, 0, staticError);
            }
            double staticPsnr = staticError.psnr();
            double minStatic = expectedFormat == CompressionPipeline.Target.BC1 ? MIN_STATIC_PSNR_BC1 : MIN_STATIC_PSNR;
            boolean staticOk = staticPsnr >= minStatic;
            out.add(String.format("%s %s %dx%d, %d levels (max %d): sprites PSNR %.1f dB over %d texels%s",
                    name, expectedFormat, width, height, levels, maxLevel, staticPsnr, staticError.count,
                    staticOk ? "" : " (below " + minStatic + ")"));
            ok &= staticOk;
        } finally {
            MemoryUtil.memFree(pixels);
        }
        return ok;
    }

    /** Level 0 of the bound texture as RGBA8, decoded by the driver. */
    private static ByteBuffer readLevel0(int width, int height) {
        ByteBuffer buffer = MemoryUtil.memAlloc(width * height * 4).order(ByteOrder.LITTLE_ENDIAN);
        int packBuffer = GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING);
        GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
        GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 4);
        GL11.glPixelStorei(GL11.GL_PACK_ROW_LENGTH, 0);
        GL11.glPixelStorei(GL11.GL_PACK_SKIP_PIXELS, 0);
        GL11.glPixelStorei(GL11.GL_PACK_SKIP_ROWS, 0);
        GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buffer);
        GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, packBuffer);
        return buffer;
    }

    /** Squared error of a sprite's region (without padding) against one of its frames; transparent texels skipped. */
    private static void compare(ByteBuffer atlas, int atlasWidth, TextureAtlasSprite sprite, int frame, Error error) {
        int padding = TesseraCompat.padding(sprite);
        int x0 = sprite.getX() + padding, y0 = sprite.getY() + padding;
        for (int y = 0; y < sprite.contents().height(); y++) {
            for (int x = 0; x < sprite.contents().width(); x++) {
                int expected = TesseraCompat.spriteAbgr(sprite, frame, x, y);
                if ((expected >>> 24) == 0) {
                    continue;
                }
                int actual = atlas.getInt(((y0 + y) * atlasWidth + x0 + x) * 4);
                for (int shift = 0; shift < 32; shift += 8) {
                    int d = ((expected >>> shift) & 0xFF) - ((actual >>> shift) & 0xFF);
                    error.sum += (long) d * d;
                }
                error.count++;
            }
        }
    }

    /**
     * Whether an overlay (the resource-loading screen) is up: {@code Minecraft.getOverlay()}
     * up to 26.1, {@code Minecraft.gui.overlay()} from 26.2. Looked up by name so one
     * jar checks both.
     */
    private static boolean overlayShown(Minecraft mc) {
        try {
            try {
                return Minecraft.class.getMethod("getOverlay").invoke(mc) != null;
            } catch (NoSuchMethodException e) {
                Object gui = Minecraft.class.getField("gui").get(mc);
                return gui.getClass().getMethod("overlay").invoke(gui) != null;
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Tessera self-test: no way to see the loading overlay", e);
        }
    }

    private static final class Error {
        long sum;
        long count;

        double psnr() {
            if (count == 0) {
                return Double.POSITIVE_INFINITY;
            }
            double mse = (double) sum / (count * 4);
            return mse == 0 ? Double.POSITIVE_INFINITY : 10 * Math.log10(255.0 * 255.0 / mse);
        }
    }
}
