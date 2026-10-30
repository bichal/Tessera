# Developers

`AtlasCompressEvent.Pre` (cancellable; choose BC7 or BC1 per atlas) and `AtlasCompressEvent.Post` (bytes saved and
resident) are fired on `NeoForge.EVENT_BUS`; on Fabric, register a listener with
`AtlasCompressEvent.addListener`.

Source code, issues and docs: **[github.com/bichal/Tessera](https://github.com/bichal/Tessera)**

Bug reports and PRs are welcome; please open an issue first for larger changes.
[GitHub Issues](https://github.com/bichal/Tessera/issues): include Minecraft/NeoForge/Tessera versions, GPU, OS
and your log.

### Setup

Clone the shared build logic next to Tessera; the build reads it from `../bichal-build-logic`:

```bash
git clone https://github.com/bichal/BichalBuildLogic.git bichal-build-logic
git clone https://github.com/bichal/Tessera.git
```

Celeris is resolved from your local Maven repository (if you `publishToMavenLocal` it yourself) or from
`https://bichal.github.io/Celeris/maven`. Requirements: **JDK 21** for the game, **JDK 25** to run Gradle (the shared build logic needs it).

### Build and run

```bash
./gradlew :1.21.1:build        # compile + test (NeoForge); :1.21.1-fabric:build for Fabric
./gradlew :1.21.1-fabric:selftest   # headless client check, either loader (-PselftestDefaultPolicy=true: shipped defaults)
./gradlew :1.21.1:runClient    # dev client
./gradlew :1.21.1:runData      # regenerate lang files (src/generated/resources)
./gradlew buildAndCollect      # release jars (universal, per-system, sources) in build/libs/<version>/
```

### Native encoder

The JNI library is built from `native/` (CMake) against the vendored bc7enc_rdo sources:

```bash
cd native/vendor/bc7enc_rdo && ./fetch.sh && cd ../../..   # vendor the pinned upstream sources

# Windows x86_64 (Visual Studio + CMake)
cmake -S native -B build-native/windows -A x64
cmake --build build-native/windows --config Release
mkdir -p natives/windows/x86_64 && cp build-native/windows/Release/tessera_bridge.dll natives/windows/x86_64/

# Linux x86_64 + AArch64 (Docker, from any OS)
JAVA_HOME=<path to a JDK> ./native/build-linux.sh
```

The binaries are not committed. CI builds them from `native/` on every platform's runner and bundles them into the
released jars. Locally, a build without them uses the Java encoder.

### Releases and descriptions

This README is also the description on Modrinth and CurseForge (everything outside `publish:off` blocks). Changelogs
live in `docs/changelogs/<version>.md`; see [`docs/README.md`](./docs/README.md) for the release steps and previews.

### Project layout

```
docs/                   # changelogs/, media/ (see docs/README.md)
native/
  CMakeLists.txt        # JNI library build
  build-linux.sh        # Linux x86_64 + aarch64 build in Docker
  vendor/bc7enc_rdo/    # tessera_bridge.cpp + fetch.sh (upstream sources are fetched, not committed)
src/main/java/net/bichal/mods/tessera/
  api/                  # AtlasCompressEvent
  atlas/                # Atlas assembly, mip chains, compression/upload driver
  cache/                # Disk cache
  compress/             # Pipeline, animated-frame uploader, JNI + pure-Java encoders
  config/               # Config and rules
  gui/                  # F3 debug overlay
  mixin/                # TextureAtlas and SpriteContents hooks
  vram/                 # VRAM budget
```

[Back to Home](Home.md)
