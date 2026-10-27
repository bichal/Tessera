# Changelog

Notes for each release are in [`docs/changelogs/`](./docs/changelogs/). This file lists changes as they land,
grouped as Added, Changed, Deprecated, Removed, Fixed and Security. Versions follow SemVer.

## [Unreleased]

### Added

- `NativeBridgeSelfTest`: loads the bundled bridge, checks the ABI it reports, and round-trips a 67 x 37 gradient
  through encode and decode (decoded PSNR must be at least 30 dB).
- Native decoder export `nativeDecompressBc7`, used only by the round-trip test.
- `scripts/verify-jni-symbols.sh` and the Gradle task `verifyJniSymbols` (part of `check`): every `native` method
  must have an exported `Java_*` symbol, and every exported `Java_*` symbol must come from a `native` method.
- `scripts/check-no-binaries.sh`: fails when git tracks a native binary.
- `.github/workflows/release.yml`: release gate for `v*` tags (native build from source in Docker, glibc floor 2.17,
  JNI symbols, native test).

### Changed

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
