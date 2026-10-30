package net.bichal.mods.tessera.compress.backend;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/**
 * Native encoders over JNI: bc7enc_rdo (BC7, BC1) and ARM's astc-encoder (ASTC). Works on every JDK with no launch flags
 * (unlike FFM, which is preview-only on the Java 21 runtime Minecraft 1.21.1
 * ships), and is zero-copy: the native side reads the source and writes the
 * blocks directly through direct-buffer addresses.
 *
 * <p>Loading happens in the static initializer, so any failure (unsupported
 * platform, missing or stale library) surfaces as an initialization error
 * that {@link TesseraRuntime}'s probe catches before falling back to the
 * pure-Java encoder.
 */
final class JniCompressionBackend implements CompressionBackend {

    /** Must match kTesseraJniAbiVersion in tessera_bridge.cpp. */
    static final int EXPECTED_ABI_VERSION = 3;
    private static final int BC7_BYTES_PER_BLOCK = 16;
    private static final int BC1_BYTES_PER_BLOCK = 8;

    static {
        System.load(extractLibrary().toAbsolutePath().toString());
        int abi = nativeAbiVersion();
        if (abi != EXPECTED_ABI_VERSION) {
            throw new UnsatisfiedLinkError("tessera_bridge ABI " + abi + ", expected " + EXPECTED_ABI_VERSION);
        }
    }

    private static native int nativeAbiVersion();

    private static native long nativeCompress(ByteBuffer src, int width, int height, int quality,
                                              boolean bc7, ByteBuffer dst);

    private static native int nativeDecompressBc7(ByteBuffer src, int width, int height, ByteBuffer dst);

    private static native long nativeCompressAstc(ByteBuffer src, int width, int height, int blockWidth,
                                                  int blockHeight, int quality, ByteBuffer dst);

    private static native int nativeDecompressAstc(ByteBuffer src, int width, int height, int blockWidth,
                                                   int blockHeight, ByteBuffer dst);

    /** Bytes of an ASTC image: 16 per block, partial blocks at the edges count whole. */
    private static long astcBytes(int width, int height, int blockWidth, int blockHeight) {
        return (long) ((width + blockWidth - 1) / blockWidth) * ((height + blockHeight - 1) / blockHeight) * 16L;
    }

    /** The ABI version the loaded library reports; the static initializer already checks it. */
    static int abiVersion() {
        return nativeAbiVersion();
    }

    /**
     * Decodes BC7 blocks to RGBA8 with the bundled decoder. Used by the round-trip
     * check ({@code NativeBridgeSelfTest}), not by the game.
     *
     * @return width * height RGBA8 texels, or {@code null} if the decoder rejects the input
     */
    static ByteBuffer decompressBc7(ByteBuffer bc7, int width, int height) {
        if (!bc7.isDirect()) {
            throw new IllegalArgumentException("decompressBc7 requires a direct source buffer");
        }
        ByteBuffer dst = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.LITTLE_ENDIAN);
        return nativeDecompressBc7(bc7.slice(), width, height, dst) == 0 ? dst : null;
    }

    /**
     * Decodes ASTC LDR blocks to RGBA8 with astc-encoder's decoder: an implementation independent of
     * Tessera's own encoders, used by the round-trip checks, not by the game.
     *
     * @return width * height RGBA8 texels, or {@code null} if the decoder rejects the input
     */
    static ByteBuffer decompressAstc(ByteBuffer astc, int width, int height, int blockWidth, int blockHeight) {
        if (!astc.isDirect()) {
            throw new IllegalArgumentException("decompressAstc requires a direct source buffer");
        }
        ByteBuffer dst = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.LITTLE_ENDIAN);
        return nativeDecompressAstc(astc.slice(), width, height, blockWidth, blockHeight, dst) == 0 ? dst : null;
    }

    @Override
    public ByteBuffer compressAstc(ByteBuffer rgba8Direct, int width, int height, int blockWidth, int blockHeight,
                                   int qualityPreset, boolean guardQuality) {
        if (!rgba8Direct.isDirect()) {
            throw new IllegalArgumentException("JniCompressionBackend requires a direct source buffer");
        }
        long size = astcBytes(width, height, blockWidth, blockHeight);
        if (size > Integer.MAX_VALUE) {
            return null;
        }
        ByteBuffer dst = ByteBuffer.allocateDirect((int) size).order(ByteOrder.LITTLE_ENDIAN);
        long written = nativeCompressAstc(rgba8Direct.slice(), width, height, blockWidth, blockHeight, qualityPreset, dst);
        if (written <= 0) {
            return null;
        }
        dst.limit((int) written);
        return dst;
    }

    @Override
    public boolean supportsAstc() {
        return true;
    }

    @Override
    public ByteBuffer compressBc7(ByteBuffer rgba8Direct, int width, int height, int qualityPreset) {
        return compress(rgba8Direct, width, height, qualityPreset, true);
    }

    @Override
    public ByteBuffer compressBc1(ByteBuffer rgba8Direct, int width, int height, int qualityPreset) {
        return compress(rgba8Direct, width, height, qualityPreset, false);
    }

    private static ByteBuffer compress(ByteBuffer rgba8Direct, int width, int height, int quality, boolean bc7) {
        if (!rgba8Direct.isDirect()) {
            throw new IllegalArgumentException("JniCompressionBackend requires a direct source buffer");
        }
        // Native code reads from the buffer's base address, so pass a slice
        // starting at the current position.
        ByteBuffer src = rgba8Direct.slice();
        long blocks = (long) ((width + 3) >> 2) * ((height + 3) >> 2);
        long size = blocks * (bc7 ? BC7_BYTES_PER_BLOCK : BC1_BYTES_PER_BLOCK);
        if (size > Integer.MAX_VALUE) {
            return null;
        }
        ByteBuffer dst = ByteBuffer.allocateDirect((int) size).order(ByteOrder.LITTLE_ENDIAN);
        long written = nativeCompress(src, width, height, quality, bc7, dst);
        if (written <= 0) {
            return null; // -1: encoder failure; < -1: undersized dst (should be unreachable)
        }
        dst.limit((int) written);
        return dst;
    }

    @Override
    public boolean supportsBc7() {
        return true;
    }

    @Override
    public boolean supportsBc1() {
        return true;
    }

    @Override
    public String name() {
        return "native (JNI)";
    }

    private static String platformDirectory() {
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch").toLowerCase(Locale.ROOT);
        boolean isAarch64 = arch.equals("aarch64") || arch.equals("arm64");
        boolean isX64 = arch.equals("amd64") || arch.equals("x86_64");
        if (os.contains("win") && isX64) {
            return "windows-x86_64";
        }
        if (os.contains("mac") && (isX64 || isAarch64)) {
            return isAarch64 ? "macos-aarch64" : "macos-x86_64";
        }
        if (os.contains("linux") && (isX64 || isAarch64)) {
            if (isAndroid()) {
                // Launchers such as Pojav run the game on Android's bionic libc: a glibc build does not load.
                throw new UnsatisfiedLinkError("No bundled tessera_bridge for Android (bionic libc)");
            }
            return isAarch64 ? "linux-aarch64" : "linux-x86_64";
        }
        // Windows on ARM, 32-bit: no bundled binary.
        throw new UnsatisfiedLinkError("Unsupported platform for tessera_bridge: " + os + "/" + arch);
    }

    /** Whether this JVM runs on Android (a Linux kernel with Android's own userland). */
    private static boolean isAndroid() {
        return System.getenv("ANDROID_ROOT") != null || Files.exists(Path.of("/system/build.prop"));
    }

    private static String libraryFileName() {
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            return "tessera_bridge.dll";
        }
        return os.contains("mac") ? "libtessera_bridge.dylib" : "libtessera_bridge.so";
    }

    /**
     * Extracts the bundled library into a content-addressed temp directory and
     * reuses it on later launches (Windows cannot delete a loaded DLL, so a
     * fresh temp dir per launch would leak one copy each time).
     */
    private static Path extractLibrary() {
        String resourcePath = "natives/" + platformDirectory() + "/" + libraryFileName();
        try (InputStream in = JniCompressionBackend.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new UnsatisfiedLinkError("Bundled native library not found on classpath: " + resourcePath);
            }
            byte[] bytes = in.readAllBytes();
            String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes), 0, 8);
            Path dir = Path.of(System.getProperty("java.io.tmpdir"), "tessera-natives", digest);
            Path extracted = dir.resolve(libraryFileName());
            if (Files.isRegularFile(extracted) && Files.size(extracted) == bytes.length) {
                return extracted;
            }
            Files.createDirectories(dir);
            // Write-then-move so a concurrent launch never loads a half-written file.
            Path tmp = Files.createTempFile(dir, "lib", ".part");
            try {
                Files.write(tmp, bytes);
                Files.move(tmp, extracted, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException moveFailure) {
                Files.deleteIfExists(tmp);
                if (!Files.isRegularFile(extracted)) {
                    throw moveFailure; // otherwise another process won the race; use its copy
                }
            }
            return extracted;
        } catch (IOException | NoSuchAlgorithmException e) {
            UnsatisfiedLinkError error = new UnsatisfiedLinkError("Failed extracting " + resourcePath);
            error.initCause(e);
            throw error;
        }
    }
}
