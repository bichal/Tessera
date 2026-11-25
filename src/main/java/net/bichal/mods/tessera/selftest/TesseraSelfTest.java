package net.bichal.mods.tessera.selftest;

import net.bichal.mods.tessera.cache.AtlasCache;
import net.bichal.mods.tessera.compat.ModCompat;
import net.bichal.mods.tessera.compat.TesseraCompat;
import net.bichal.mods.tessera.atlas.AtlasPolicy;
import net.bichal.mods.tessera.compress.CompressedAnimationUploader;
import net.bichal.mods.tessera.compress.CompressedAnimationUploader.EncodeStats;
import net.bichal.mods.tessera.compress.CompressedAtlases;
import net.bichal.mods.tessera.atlas.AtlasFormats;
import net.bichal.mods.tessera.compress.AstcGpuSupport;
import net.bichal.mods.tessera.compress.Bc1TextureFormatSupport;
import net.bichal.mods.tessera.compress.Bc7GpuSupport;
import net.bichal.mods.tessera.compress.CompressionPipeline;
import net.bichal.mods.tessera.compress.gpu.GpuCompression;
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
 *   <li>animations keep running on compressed atlases: after ticking the atlas, animated
 *       sprites show one of their frames, and some changed;</li>
 *   <li>an atlas with animated sprites that Tessera leaves alone (Kerria installed, setting
 *       off) is still plain RGBA8.</li>
 * </ul>
 * Writes {@code tessera-selftest.txt} in the game directory; its first line ends
 * with PASS or FAIL.
 */
public final class TesseraSelfTest {

    public static final boolean ENABLED = Boolean.getBoolean("tessera.selftest");

    private static final Logger LOGGER = LoggerFactory.getLogger("Tessera/SelfTest");
    private static final int SETTLE_TICKS = 200, GIVE_UP_TICKS = 6000, ANIMATION_TICKS = 60;
    private static final double MIN_STATIC_PSNR = 28.0, MIN_FRAME_PSNR = 20.0;
    // BC1 stores 4 bits per texel (BC7: 8): detailed opaque atlases such as paintings land near 25 dB.
    private static final double MIN_STATIC_PSNR_BC1 = 24.0;
    private static final int GL_COMPRESSED_RGB_S3TC_DXT1 = 0x83F0, GL_COMPRESSED_RGBA_S3TC_DXT1 = 0x83F1;

    // Compressed atlases come from CompressedAtlases, not AtlasCompressEvent.Post:
    // on 1.21 - 1.21.1 the first reload runs during mod loading, when NeoForge's bus
    // still drops events, so the early atlases' events never arrive.
    private static int seenRegistrations = -1;
    private static int ticks, lastCompressedTick = -1, animatedChanged;
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
    /** -Dtessera.selftest.dump=<dir>: writes each compressed atlas as raw RGBA, original and decoded, for comparisons. */
    private static final String DUMP_DIR = System.getProperty("tessera.selftest.dump");
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

    /**
     * A second round after the first: the mipmap setting changes and every atlas reloads, as when a
     * player changes a graphics setting in game (the textures of the first round are compressed by then).
     * {@code -Dtessera.selftest.reload=false} skips it.
     */
    private static final boolean RELOAD_ROUND = !"false".equals(System.getProperty("tessera.selftest.reload"));
    private static int round, registrationsAtReload = -1;
    private static final List<String> FIRST_ROUND = new ArrayList<>();

    private static void tick() {
        if (done) {
            return;
        }
        ticks++;
        Minecraft mc = Minecraft.getInstance();
        if (ticks == 1) {
            GlErrors.install();
        }
        if (CompressedAtlases.registrations() != seenRegistrations) {
            seenRegistrations = CompressedAtlases.registrations();
            lastCompressedTick = ticks;
            EncodeStats.reset();
        }
        // Measure the steady state: the precode of animation frames (background) is still running.
        if (CompressedAnimationUploader.pendingUpgrades() > 0) {
            lastCompressedTick = ticks;
            EncodeStats.reset();
        }
        // The block atlas is the largest and usually the last one done: compressed, or kept as RGBA8
        // when its animations are left to vanilla (or Kerria). Then wait for the frame precode.
        boolean blocksDone = CompressedAtlases.all().keySet().stream().anyMatch(atlas -> isBlocks(atlas.location().toString()))
                || CompressedAtlases.keptForAnimations().stream().anyMatch(atlas -> isBlocks(atlas.location().toString()));
        boolean settled = !overlayShown(mc) && blocksDone && ticks - lastCompressedTick >= SETTLE_TICKS
                && CompressedAnimationUploader.pendingUpgrades() == 0
                && CompressedAtlases.registrations() > registrationsAtReload;
        if (!settled && ticks < GIVE_UP_TICKS * (round + 1)) {
            return;
        }
        List<String> lines = new ArrayList<>();
        // Steady state since the last atlas was compressed and its frames precoded: what animations cost per client tick.
        int window = Math.max(1, ticks - lastCompressedTick);
        lines.add(String.format("animation encoding on the render thread over %d ticks: %d encodes (%d more found"
                        + " by content), %.3f ms per tick (largest %.3f ms); native encodes in the background: %d, %.1f ms",
                window, EncodeStats.calls, EncodeStats.found, EncodeStats.nanos / 1e6 / window,
                EncodeStats.maxNanos / 1e6, EncodeStats.bestCalls(), EncodeStats.bestNanos() / 1e6));
        lines.add(String.format("  all animation work on the render thread: %.3f ms per tick (largest call %.3f ms)",
                EncodeStats.drawNanos / 1e6 / window, EncodeStats.maxDrawNanos / 1e6));
        lines.add(String.format("  keyframes not precoded yet: %d; blended frames: %d looked up (%d by frame and"
                        + " progress), %d encoded; smallest-level patches: %d, %d encoded",
                EncodeStats.keyframeMisses, EncodeStats.blendLookups, EncodeStats.foundByFrame, EncodeStats.blendMisses,
                EncodeStats.deepPatches, EncodeStats.deepMisses));
        boolean pass;
        try {
            pass = check(lines);
        } catch (Throwable t) {
            LOGGER.error("Tessera self-test crashed", t);
            lines.add("crashed: " + t);
            pass = false;
        }
        pass &= GlErrors.report(lines);
        if (RELOAD_ROUND && round == 0) {
            // Same checks again after the reload a graphics setting change triggers.
            FIRST_ROUND.add("first round: " + (pass ? "PASS" : "FAIL"));
            lines.forEach(line -> FIRST_ROUND.add("  " + line));
            round = 1;
            registrationsAtReload = CompressedAtlases.registrations();
            animatedChanged = 0;
            lastCompressedTick = ticks;
            int mipmaps = mc.options.mipmapLevels().get();
            int changed = mipmaps == 4 ? 2 : 4;
            LOGGER.info("Tessera self-test: mipmap levels {} -> {}, reloading", mipmaps, changed);
            mc.options.mipmapLevels().set(changed);
            mc.updateMaxMipLevel(changed);
            mc.reloadResourcePacks();
            return;
        }
        done = true;
        if (!FIRST_ROUND.isEmpty()) {
            pass &= FIRST_ROUND.get(0).endsWith("PASS");
            lines.add(0, "second round, after changing the mipmap levels and reloading:");
            lines.addAll(0, FIRST_ROUND);
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
        out.add("renderer " + GL11.glGetString(GL11.GL_RENDERER) + ", " + GL11.glGetString(GL11.GL_VERSION));
        out.add("GPU formats: BC1 " + Bc1TextureFormatSupport.isSupported() + ", BC7 " + Bc7GpuSupport.isSupported()
                + ", ASTC " + AstcGpuSupport.isSupported());
        out.add("GPU compression: " + GpuCompression.status().state() + " " + GpuCompression.status().detail());
        if (AtlasFormats.choose(false) == null && AtlasFormats.choose(true) == null) {
            out.add("the GL driver takes none of BC1, BC7 and ASTC");
            return false;
        }
        Map<TextureAtlas, CompressionPipeline.Target> compressed = CompressedAtlases.all();
        if (compressed.isEmpty()) {
            out.add("no atlas was compressed within " + GIVE_UP_TICKS + " ticks");
            return false;
        }
        boolean ok = true;
        // The block atlas is compressed whenever its animations may be (no Kerria, setting on, BC formats).
        CompressionPipeline.Target blocksFormat = AtlasFormats.choose(false);
        if (blocksFormat != null && AtlasPolicy.mayCompressAnimated(blocksFormat)
                && compressed.keySet().stream().noneMatch(atlas -> isBlocks(atlas.location().toString()))) {
            out.add("the block atlas was not compressed");
            ok = false;
        }
        // The atlases left alone for their animations are still plain RGBA8.
        for (TextureAtlas atlas : CompressedAtlases.keptForAnimations()) {
            TesseraCompat.bindTexture(TesseraCompat.glId(atlas));
            int format = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_INTERNAL_FORMAT);
            boolean rgba = format == GL11.GL_RGBA8 || format == GL11.GL_RGBA; // vanilla uploads with the unsized GL_RGBA
            out.add(String.format("%s has animations: internal format 0x%X (%s)", atlas.location(), format,
                    rgba ? "RGBA8888, as expected" : "NOT RGBA8888"));
            ok &= rgba;
        }
        out.add("Kerria installed: " + ModCompat.isKerriaLoaded());
        boolean anyAnimated = false;
        for (var entry : compressed.entrySet()) {
            ok &= checkAtlas(entry.getKey().location().toString(), entry.getKey(), entry.getValue(), out);
            anyAnimated |= TesseraCompat.textures(entry.getKey()).stream()
                    .anyMatch(sprite -> TesseraCompat.isAnimated(sprite.contents()));
        }
        // Some animations are slow (a GUI icon may hold a frame for seconds), but
        // across every compressed atlas some must have moved on.
        if (anyAnimated && animatedChanged == 0) {
            out.add("no animated sprite changed in any compressed atlas");
            ok = false;
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
                    : format == expectedFormat.glFormat();
            if (!expected || w != Math.max(1, width >> level) || h != Math.max(1, height >> level)) {
                out.add(String.format("  %s level %d: %dx%d format 0x%X (expected %s %dx%d)", name, level, w, h, format,
                        expectedFormat, Math.max(1, width >> level), Math.max(1, height >> level)));
                ok = false;
            }
            levels++;
        }

        ByteBuffer pixels = readLevel0(width, height);
        try {
            // Static sprites: the decoded atlas against the sprites' own pixels.
            List<TextureAtlasSprite> animated = new ArrayList<>();
            Error staticError = new Error();
            for (TextureAtlasSprite sprite : TesseraCompat.textures(atlas)) {
                if (TesseraCompat.isAnimated(sprite.contents())) {
                    animated.add(sprite);
                } else {
                    compare(pixels, width, sprite, 0, staticError);
                }
            }
            double staticPsnr = staticError.psnr();
            // BC1 (with or without alpha) and ASTC 6x6 store 4 bits per texel or less, BC7 and ASTC 4x4 eight.
            boolean smallFormat = expectedFormat == CompressionPipeline.Target.BC1
                    || expectedFormat == CompressionPipeline.Target.BC1_ALPHA
                    || expectedFormat == CompressionPipeline.Target.ASTC_6X6;
            double minStatic = smallFormat ? MIN_STATIC_PSNR_BC1 : MIN_STATIC_PSNR;
            boolean staticOk = staticPsnr >= minStatic;
            out.add(String.format("%s %s %dx%d, %d levels (max %d): sprites PSNR %.1f dB over %d texels%s",
                    name, expectedFormat, width, height, levels, maxLevel, staticPsnr, staticError.count,
                    staticOk ? "" : " (below " + minStatic + ")"));
            ok &= staticOk;
            if (DUMP_DIR != null) {
                dump(name, expectedFormat, atlas, pixels, width, height);
            }
            if (!animated.isEmpty()) {
                ok &= checkAnimations(name, atlas, glId, width, height, pixels, animated, out);
            }
        } finally {
            MemoryUtil.memFree(pixels);
        }
        return ok;
    }

    /** The sprites' own pixels and what the driver decodes, side by side as raw RGBA files (see DUMP_DIR). */
    private static void dump(String name, CompressionPipeline.Target format, TextureAtlas atlas, ByteBuffer decoded,
                             int width, int height) {
        ByteBuffer original = MemoryUtil.memCalloc(width * height * 4).order(ByteOrder.LITTLE_ENDIAN);
        try {
            for (TextureAtlasSprite sprite : TesseraCompat.textures(atlas)) {
                int padding = TesseraCompat.padding(sprite);
                for (int y = 0; y < sprite.contents().height(); y++) {
                    for (int x = 0; x < sprite.contents().width(); x++) {
                        original.putInt(((sprite.getY() + padding + y) * width + sprite.getX() + padding + x) * 4,
                                TesseraCompat.spriteAbgr(sprite, 0, x, y));
                    }
                }
            }
            String safe = name.replaceAll("[^A-Za-z0-9._-]", "_");
            Path dir = Path.of(DUMP_DIR);
            Files.createDirectories(dir);
            byte[] bytes = new byte[width * height * 4];
            original.get(0, bytes);
            Files.write(dir.resolve(safe + ".orig.rgba"), bytes);
            decoded.get(0, bytes);
            Files.write(dir.resolve(safe + ".dec.rgba"), bytes);
            Files.writeString(dir.resolve(safe + ".txt"), width + " " + height + " " + format + "\n");
        } catch (IOException e) {
            LOGGER.warn("Could not dump {}", name, e);
        } finally {
            MemoryUtil.memFree(original);
        }
    }

    /** Ticks the atlas (as the client does every tick) and checks the animated sprites. */
    private static boolean checkAnimations(String name, TextureAtlas atlas, int glId, int width, int height,
                                           ByteBuffer before, List<TextureAtlasSprite> animated, List<String> out) {
        for (int i = 0; i < ANIMATION_TICKS; i++) {
            atlas.tick();
        }
        TesseraCompat.bindTexture(glId);
        ByteBuffer after = readLevel0(width, height);
        try {
            int changed = 0, matching = 0;
            List<String> mismatched = new ArrayList<>();
            for (TextureAtlasSprite sprite : animated) {
                if (differs(before, after, width, sprite)) {
                    changed++;
                }
                double best = 0.0;
                for (int frame : TesseraCompat.uniqueFrames(sprite.contents())) {
                    Error e = new Error();
                    compare(after, width, sprite, frame, e);
                    best = Math.max(best, e.psnr());
                }
                if (best >= MIN_FRAME_PSNR) {
                    matching++;
                } else {
                    mismatched.add(String.format("%s %.1f dB (%dx%d at %d,%d, %d frames)", sprite.contents().name(), best,
                            sprite.contents().width(), sprite.contents().height(), sprite.getX(), sprite.getY(),
                            TesseraCompat.uniqueFrames(sprite.contents()).length));
                }
            }
            animatedChanged += changed;
            boolean ok = matching == animated.size();
            out.add(String.format("%s: %d animated sprites, %d changed after %d ticks, %d show one of their frames%s",
                    name, animated.size(), changed, ANIMATION_TICKS, matching, ok ? "" : " FAIL:"));
            mismatched.forEach(m -> out.add("    " + m));
            return ok;
        } finally {
            MemoryUtil.memFree(after);
        }
    }

    private static boolean differs(ByteBuffer a, ByteBuffer b, int atlasWidth, TextureAtlasSprite sprite) {
        int padding = TesseraCompat.padding(sprite);
        for (int y = 0; y < sprite.contents().height(); y++) {
            int row = ((sprite.getY() + padding + y) * atlasWidth + sprite.getX() + padding) * 4;
            for (int x = 0; x < sprite.contents().width(); x++) {
                if (a.getInt(row + x * 4) != b.getInt(row + x * 4)) {
                    return true;
                }
            }
        }
        return false;
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
     * up to 26.1 ({@code method_18506} in a Fabric production run), {@code Minecraft.gui.overlay()}
     * from 26.2. Looked up by name so one jar checks both.
     */
    private static boolean overlayShown(Minecraft mc) {
        for (String name : new String[]{"getOverlay", "method_18506"}) {
            try {
                return Minecraft.class.getMethod(name).invoke(mc) != null;
            } catch (NoSuchMethodException e) {
                // the next name, then 26.2's gui.overlay()
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Tessera self-test: no way to see the loading overlay", e);
            }
        }
        try {
            Object gui = Minecraft.class.getField("gui").get(mc);
            return gui.getClass().getMethod("overlay").invoke(gui) != null;
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
