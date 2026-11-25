# Changelog

Notes for each release are in [`docs/changelogs/`](./docs/changelogs/). This file lists changes as they land,
grouped as Added, Changed, Deprecated, Removed, Fixed and Security. Versions follow SemVer.

## [Unreleased]

### Added

- `gpuCompression` (off by default, marked experimental in its tooltip and in the log): the switch for encoding BC7
  on the GPU. It needs compute shaders, image load/store, shader storage buffers, `glCopyImageSubData` and BC7
  (OpenGL 4.3; never on macOS), checked on the render thread. Before a GPU encoder may touch an atlas it has to pass a
  startup check: it encodes a fixed test image, the native decoder decodes it, and it must come back the right size,
  above 30 dB PSNR and with no cutout texel on the wrong side of the alpha threshold. F3 shows which encoder BC7 goes
  through, and why not the GPU when it was asked for.
- A BC7 compute-shader encoder (`ComputeBc7Encoder`, `bc7_encode.comp`, GLSL 4.30): modes 6 and 5 for every block,
  modes 1 and 3 (opaque) and 7 (alpha) on the best two-subset partitions by PCA residual. Lines are fitted by PCA,
  quantized with every p-bit choice and refined twice by least squares; no index may move a texel across the cutout
  threshold, and opaque texels are steered to stay at 255. On the vanilla atlases it is within 0.2 dB of the CPU
  encoder's default preset (above it on items and particles). Texels and blocks go through shader storage buffers,
  the program and buffer bindings are restored, and large images are split into dispatches of 65536 blocks. It runs
  in the startup check, and with `gpuCompression` on and the check passed it encodes the BC7 atlases (every mip
  level) while the game loads. A compression thread hands the encode to the render thread through Minecraft's task
  queue and waits (the render thread never waits on compression, so this cannot deadlock); each result is
  spot-checked on the CPU (256 blocks decoded natively, no cutout texel flipped, above 24 dB) before it is used, and
  a failed check sends that image to the CPU. An exception or a 30 s timeout turns the GPU path off for the session.
  Animation frames stay on the CPU: a GPU encode holds the render thread until its result is read back. GPU-encoded
  chains are cached apart from CPU ones (tag `g`). Self-test on llvmpipe: every BC7 atlas through the GPU, none sent
  back, sprite PSNR level with the CPU encoder, no GL errors.
- Atlases with animated sprites (the block atlas) are compressed again, by default (`compressAnimatedAtlases`, on):
  `CompressedAnimationUploader`, `AnimationReader` and the `SpriteContents.upload` mixin are back. Every keyframe is
  precoded once in the background when the atlas is registered, and so is every blended step of the interpolated
  ones (up to 16384 per atlas), each mip level's frames stacked in one native encode, so the render thread no longer
  encodes them; only copying the sprites' images stays on it. The smallest mip levels (where a sprite shares 4x4
  blocks with its neighbours) follow frame starts only, not every blended step. On Fabric 1.21.11 the animation state is read through the access widener
  (`tessera-named-1.21.11.accesswidener`) instead of by name, which an intermediary-named production run would not
  find. Only BC1 and BC7 (4x4 blocks); with Kerria installed
  those atlases stay RGBA8888 and F3 shows their size. Frame 0 alone no longer decides BC1: every frame of an
  animated sprite must be opaque. A chain served from the disk cache rebuilds the deep levels' RGBA it needs for
  per-block frame patches.
- BC1 with one-bit alpha (`BC1A`, `GL_COMPRESSED_RGBA_S3TC_DXT1_EXT`) for static atlases without mip levels whose texels
  are all fully opaque or fully transparent: 4 bits per texel instead of BC7's 8, alpha exact. It starts from the
  native (or Java) opaque BC1 encode and refits only the blocks with transparent texels. Below 32 dB PSNR on the
  visible texels the atlas is encoded as BC7 instead, and that chain is cached under its own key.
- After uploading level 0, Tessera checks that the driver stored it compressed; atlases with immutable texture
  storage are left alone. Either way the atlas stays as vanilla uploaded it instead of being half compressed.

- Whole-atlas cache entries: a digest of an atlas's layout and sprite pixels keys its entire encoded mip chain in one
  file (`...-chain-q<quality>...`), so an unchanged atlas skips pixel assembly, mip generation, per-level hashing and
  encoding on the next launch.
- The native sources (bc7enc_rdo, astc-encoder) are pinned in `[vendor.*]` of `mod.stonecutter.properties.toml` and
  fetched by `bichal vendor` (bichal-build-logic), which CI runs before every native build; the `fetch.sh` scripts
  are gone and the bc7enc_rdo patches live in `native/vendor/patches/`.
- ASTC for ARM GPUs (Apple Silicon, Android): a `Target` per block format (BC1, BC7, ASTC 4x4, ASTC 6x6) with its
  own block size, `AstcGpuSupport` (`GL_KHR_texture_compression_astc_ldr`), and `AtlasFormats`, which picks BC1 / ASTC
  6x6 for opaque atlases and BC7 / ASTC 4x4 for atlases with transparency, from what the driver takes.
- ARM's astc-encoder in the native bridge (`nativeCompressAstc`, `nativeDecompressAstc`, ABI 3), built with NEON on
  arm64 and SSE4.1 on x86_64, and macOS (Apple Silicon and Intel) among the native platforms.
- A pure-Java ASTC encoder for platforms without the bridge (Android launchers). It refuses an image whose estimated
  PSNR is below 27 dB (4x4) or 24 dB (6x6), leaving that atlas as vanilla.
- `-Dtessera.formats=astc|bc` and the self-test properties `-PselftestFormats` and `-PselftestCompat`.

- Fabric builds for every Minecraft version (`<version>-fabric` nodes): settings in `config/tessera.properties`, F3 lines
  through mixins, `AtlasCompressEvent.addListener`, and a client self-test run (`:<version>-fabric:selftest`).
- `ModCompat`: Kerria detection (logged; Kerria and Tessera work on different atlases).
- `CompressionConsumptionTest`: GPU footprint of BC1 and BC7 against RGBA8888 and the time of one encode.

- `NativeBridgeSelfTest`: loads the bundled bridge, checks the ABI it reports, and round-trips a 67 x 37 gradient
  through encode and decode (decoded PSNR must be at least 30 dB).
- Native decoder export `nativeDecompressBc7`, used only by the round-trip test.
- `scripts/verify-jni-symbols.sh` and the Gradle task `verifyJniSymbols` (part of `check`): every `native` method
  must have an exported `Java_*` symbol, and every exported `Java_*` symbol must come from a `native` method.
- `scripts/check-no-binaries.sh`: fails when git tracks a native binary.
- `.github/workflows/release.yml`: release gate for `v*` tags (native build from source in Docker, glibc floor 2.17,
  JNI symbols, native test).

### Changed

- Animations cost the render thread about an eighth of what they did on 1.21.11+ (self-test, vanilla block atlas:
  4.5 ms per tick down to 0.6 ms, the largest tick 9.5 ms down to 4.1 ms). Every interpolated frame used to be
  rebuilt texel by texel and hashed each tick only to find its blocks in the content cache; now its content key is
  remembered by frame and progress (recorded by the precode, and by the first draw of a blend the precode missed), so
  a known blend is a map lookup. The padded-region texel mapping is worked out once per frame and level instead of
  per texel (also cuts the background precode from 3.3 s to 2.2 s), and frames encoded on the render thread go
  through the native encoder at preset 0 (bc7f) instead of the pure-Java one.
- Background results (precoded frames, best-quality keyframes) reach the render thread through Celeris's lock-free
  `AsyncResultQueue` instead of a `ConcurrentLinkedQueue`; an overflow queue keeps anything that does not fit.
- The self-test reports everything animations cost the render thread per tick, not only the encodes, and how many
  blends were found by frame and progress.
- BC7 is encoded by bc7f (Basis Universal's real-time BC7 encoder, which uses the whole format) and, from
  `compressionQuality` 2 up, also by bc7enc: each 4x4 block keeps whichever decodes closer to the source. On the vanilla
  atlases the default preset is 0.15-1.5 dB PSNR above the old encoder at any of its presets, and 2.5-4 times faster
  on four cores.
  `compressionQuality` 0 and 1 are bc7f alone (one atlas in a few milliseconds). Fully opaque blocks keep alpha 255
  (bc7f's mode 6 p-bit optimisation is off). Cache entries from the old encoder are not reused (`v3`).
- The native BC7 and BC1 encoders run on every core: the bridge splits each image into bands of block rows. Until now
  they ran on one thread (the upstream loops are OpenMP, which the bridge was never built with), whatever the comments
  said. Images under 1024 blocks (animation frames, small mip levels) stay on the calling thread.
- bc7enc_rdo is vendored down to its block encoders and BC7 decoder (no `rdo_bc_encoder`, `ert`, `utils`), without
  the patch; basis_universal's transcoder module is a new `[vendor.*]` entry.
- The VRAM budget is advisory, as its setting says: an atlas over it is still compressed (and a warning logged).
  Before, it was left RGBA8888, which takes two to eight times more than the compressed chain.
- Disk cache: only the whole-chain entry is written (per-level entries were a second copy of the same blocks); old
  per-level entries are still read. Entries are packed at zstd level 9 instead of 3.
- F3 no longer says "UNSUPPORTED (macOS)": macOS takes BC1 for opaque atlases, so the status follows what was
  compressed.

- The upload no longer assumes 4x4 blocks or OpenGL 4.3: level sizes come from the format's block size, and the GL
  debug group is only used where the context has it (macOS and OpenGL ES layers do not).

- The disk cache stores entries compressed with Celeris's codec (native zstd when loaded, deflate otherwise): 2668 KiB of
  blocks from a vanilla client took 307 KiB. The entry layout changed (`TSR3`); old entries are misses and get replaced.

- Animated textures stay RGBA8888: an atlas holding animated sprites is never compressed. The code that wrote animation
  frames as BC blocks is gone (`CompressedAnimationUploader`, `AnimationReader`, the `SpriteContents.upload` mixin).
- The format follows the content: an atlas whose texels are all opaque is stored as BC1, one with transparency as BC7.

- Native libraries are no longer committed. CI builds them per platform. Local builds without them use the Java
  encoder.
- JNI ABI is 2 (adds `nativeDecompressBc7`).
- The Linux x86_64 bridge is rebuilt from the fixed source in an Ubuntu 20.04 chroot. It requires glibc 2.14 at most,
  as before.

### Removed

- The committed binaries under `natives/` (linux-x86_64, linux-aarch64, windows-x86_64). The aarch64 and Windows
  libraries will be built by CI again; the Java encoder is used until a release includes them.

### Fixed

- A Fabric production client (intermediary names on 1.21.x) left every atlas uncompressed: the background executor,
  the GL id of a texture, the 1.21.1 pixel getter and the animation timeline were looked up by Mojang name only. Each
  lookup now tries the intermediary name as well; `selftestProd` runs the self-test with the remapped jar.
- Intel's Windows driver crashed when an atlas was compressed again after a graphics setting change: it applies the
  pixel unpack state vanilla leaves set (row length, skips) to compressed uploads. Tessera resets it before uploading.
- The JNI entry points were exported as `Java_com_bichal_*` and `Java_com_panzer_*`, but the Java class is
  `net.bichal.mods.tessera.compress.backend.JniCompressionBackend`. The committed binary did not load, so jars built from it
  used the Java encoder without an error. Released jars were not checked. Entry points are now `Java_net_bichal_*`.
