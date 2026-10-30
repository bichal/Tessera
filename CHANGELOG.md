# Changelog

Notes for each release are in [`docs/changelogs/`](./docs/changelogs/). This file lists changes as they land,
grouped as Added, Changed, Deprecated, Removed, Fixed and Security. Versions follow SemVer.

## [Unreleased]

### Added

- Whole-atlas cache entries: a digest of an atlas's layout and sprite pixels keys its entire encoded mip chain in one
  file (`...-chain-q<quality>...`), so an unchanged atlas skips pixel assembly, mip generation, per-level hashing and
  encoding on the next launch. Per-level entries remain for atlases that changed.
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

- The JNI entry points were exported as `Java_com_bichal_*` and `Java_com_panzer_*`, but the Java class is
  `net.bichal.mods.tessera.compress.backend.JniCompressionBackend`. The committed binary did not load, so jars built from it
  used the Java encoder without an error. Released jars were not checked. Entry points are now `Java_net_bichal_*`.
