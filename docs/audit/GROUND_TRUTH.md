# Ground truth: Tessera as it is today

**Status:** WS-1 deliverable, written before any code change. Normative for the audit work: when this file and the
original audit disagree, this file wins until the maintainer says otherwise.

**Snapshot:** branch `master` at `a767396` (Tessera `0.3.1`, built against Celeris `0.3.1.1`), build logic
`bichal/BichalBuildLogic` at `aab7d10`. Verified on 2026-10-27 on a 4-vCPU x86-64 Linux container.

Celeris has its own file: [`bichal/Celeris` `docs/audit/GROUND_TRUTH.md`](https://github.com/bichal/Celeris/blob/master/docs/audit/GROUND_TRUTH.md).
Shared facts (build logic, Stonecutter nodes, Gradle on JDK 25) are stated there and not repeated here.

## 1. What Tessera is

A **client-only NeoForge mod** (mod id `tessera`, `side = "CLIENT"`, package `net.bichal.mods.tessera`) that lowers
the video memory used by Minecraft's texture atlases. After vanilla uploads an atlas, Tessera re-encodes it to **BC7**
(BC1 optional per atlas) with its full mip chain and re-uploads it with `glCompressedTexImage2D` into the same GL
texture. Encoding runs on a background executor; only the upload happens on the render thread. Animated sprites are
patched as BC blocks with `glCompressedTexSubImage2D`. A content-addressed, size-capped (512 MB, LRU) disk cache in
`tessera-cache/` skips re-encoding unchanged atlases.

It is **not**:

- a Vulkan renderer: there is no Vulkan, no mesh shaders, no terrain renderer. Everything is OpenGL through vanilla's
  texture objects;
- a Fabric mod: only NeoForge nodes are built (no `loaders` in `[stonecutter]`);
- a Sodium/Iris/Embeddium compatibility layer: it does not touch chunk geometry, shaders or the render pipeline,
  which is why it coexists with them without dedicated code.

Licenses: code AGPL-3.0-only, assets and docs CC BY-NC-SA 4.0, bundled `bc7enc_rdo` MIT OR Unlicense.

## 2. Repository layout (real)

```
Tessera/
├── build.gradle.kts, settings.gradle.kts, stonecutter.gradle.kts, gradle.properties  (bichal-build-logic)
├── mod.stonecutter.properties.toml
├── src/main/java/net/bichal/mods/tessera/
│   ├── api/        AtlasCompressEvent (Pre: cancellable, BC7/BC1 choice; Post: bytes saved), TesseraFeatures
│   ├── atlas/      AtlasCompressionDriver, AtlasPolicy, MipChainBuilder, NativeFamilyDetector, SpriteFamilyAnalyzer
│   ├── cache/      AtlasCache
│   ├── compat/     TesseraCompat (reflection over APIs that change between Minecraft versions)
│   ├── compress/   CompressionPipeline, CompressedAnimationUploader, Bc7GpuSupport, Bc1TextureFormatSupport,
│   │               backend/ (CompressionBackend, JniCompressionBackend, SoftwareCompressionBackend, TesseraRuntime),
│   │               software/ (pure-Java encoder)
│   ├── config/     Config (NeoForge ModConfigSpec), RulesManager
│   ├── gui/        DebugOverlay (F3 savings), KnownEngineBugLogFilter
│   ├── mixin/      5 classes (below)
│   ├── selftest/   TesseraSelfTest (headless client check of compressed atlases)
│   └── vram/, keybinds/, datagen/, util/
├── src/test/java/  AtlasPolicyTest (one test class)
├── native/         CMakeLists.txt, build-linux.sh (Docker), vendor/bc7enc_rdo/ (tessera_bridge.cpp + fetch.sh;
│                   upstream sources are fetched, not committed)
├── natives/        committed prebuilt libtessera_bridge for linux-x86_64, linux-aarch64, windows-x86_64
└── .github/workflows/  ci.yml (bichal-build-logic mod-ci.yml), descriptions.yml
```

## 3. Mixins (real)

`tessera.mixins.json`: `required: true`, `minVersion: 0.8`, client-only, `injectors.defaultRequire: 1`,
**no `priority`** (default 1000). No MixinExtras dependency.

| Class | Target | Injections |
|---|---|---|
| `TextureAtlasUploadMixin` | `TextureAtlas` | `@Inject` `upload` HEAD and RETURN; `uploadAnimationFrames` HEAD (cancellable); `cycleAnimationFrames` HEAD and RETURN |
| `SpriteContentsUploadMixin` | `SpriteContents` | `@Inject` `upload` HEAD (cancellable), up to 1.21.10 |
| `TextureAtlasAccessor`, `TextureAtlasSpriteAccessor`, `KeyboardHandlerAccessor` | — | `@Accessor` / `@Invoker` only |

There are **no `@Redirect`**, no `@ModifyVariable`/`@ModifyConstant`, no `@Pseudo`, and no mixin into Sodium, Iris or
Embeddium classes. The only targets are vanilla texture classes, which those mods do not replace.

## 4. Native code (real)

`tessera_bridge`: C++17 JNI wrapper over `bc7enc_rdo` (vendored by `fetch.sh` at a pinned commit, with patches), hidden
visibility, statically linked libstdc++ on Linux, `-pthread`. Built for Windows x86_64 (MSVC), Linux x86_64 and
Linux AArch64 (glibc 2.17+, Docker). No macOS build: macOS's OpenGL lacks BC7 (`GL_ARB_texture_compression_bptc`).
Loaded over JNI only (no FFM, no launch flag). If loading fails, `TesseraRuntime` catches `Throwable`, logs the reason
and uses `SoftwareCompressionBackend`.

## 5. Build, test and CI (real)

| Need | Real command |
|---|---|
| Build + test (active node) | `./gradlew :1.21.1:build` |
| Dev client | `./gradlew :1.21.1:runClient` |
| Lang files | `./gradlew :1.21.1:runData` |
| Headless self-test | `./gradlew :1.21.1:selftest` (client under software GL, checks every compressed atlas against its sprites, writes `tessera-selftest.txt`) |
| Release jars | `./gradlew buildAndCollect` |
| Native (Linux) | `cd native/vendor/bc7enc_rdo && ./fetch.sh && cd ../../.. && JAVA_HOME=<jdk> ./native/build-linux.sh` |

CI (`ci.yml` → bichal-build-logic `mod-ci.yml`): `build buildAndCollect selftest` on every push/PR; `bichal compat`
runs the self-test in a real client for every `game_versions` entry; `v*` tags publish to GitHub, Modrinth and
CurseForge.

Minecraft nodes: `1.21.1` (1.21 – 1.21.4), `1.21.10` (1.21.5 – 1.21.10), `1.21.11`, `26.1` (26.1 – 26.3). Java 21
for 1.21.x, Java 25 for 26.x. Gradle itself must run on JDK 25 (README says JDK 21: wrong for a fresh checkout).

## 6. Defects found while verifying (not fixed in WS-1)

| # | Severity | Finding | Evidence | Effect |
|---|---|---|---|---|
| T1 | **P0** (fixed in source) | JNI names did not match the Java class. Source exported `Java_com_bichal_mods_tessera_...`, the committed binaries export `Java_com_panzer_mods_tessera_...`; the class is `net.bichal.mods.tessera.compress.backend.JniCompressionBackend` | `native/vendor/bc7enc_rdo/tessera_bridge.cpp:146,154`; `nm -D natives/linux/x86_64/libtessera_bridge.so`; package rename in `8cc879b` | The native BC7 encoder never loaded from the released jars; every user got the pure-Java encoder, silently. Same root cause as Celeris D1. Source now exports `Java_net_bichal_mods_tessera_...`; the committed binaries in `natives/` still carry the old names until rebuilt |
| T2 | P2 | Committed `natives/` binaries predate the `panzer → bichal` rename and match no Java package | `git log -- natives/` | Local builds without `-Pbichal.native.build=true` bundle stale binaries |
| T3 | P3 | `[compression] default_quality_preset = 3` is passed as `-Dtessera.compression.defaultQualityPreset` to dev runs, but no code reads it; the real default is `compressionQuality = 4` in `Config` | `grep -rn defaultQualityPreset src` → nothing | Dead configuration that contradicts the README |
| T4 | P3 | One unit test class (`AtlasPolicyTest`); correctness rests on the headless self-test | `src/test` | Regressions outside the atlas policy are caught only by the client run |

## 7. Audit assumptions vs reality

| Real feature | What the audit assumed | Gap | Action |
|---|---|---|---|
| OpenGL BC7/BC1 re-encoding of vanilla atlases, background encoding, disk cache | Vulkan mesh-shader terrain renderer + "VRAM BC7 optimizer" | No Vulkan, no terrain rendering | Strike Vulkan from every workstream; WS-13 records it |
| 5 mixin classes on vanilla `TextureAtlas`/`SpriteContents`, `@Inject` + accessors, no `@Redirect` | Mixin layer over Sodium/Iris with `@Redirect`s to migrate | Nothing to migrate to `@WrapOperation`; no Sodium/Iris targets | WS-8 shrinks to: verify coexistence (Sodium, Embeddium, Iris, none) with the existing self-test, set an explicit priority only if a conflict is found |
| NeoForge only, client only | NeoForge + Fabric | No Fabric build | Matrix rows for Fabric do not apply (Sodium/Iris on NeoForge exist as Sodium/Iris NeoForge ports and Embeddium) |
| Native encoder over JNI, Java fallback | — | T1: native path dead in released jars | Fix T1 before any performance work |
| Headless client self-test in CI per Minecraft version | Integration tests to be created | Harness already exists | WS-8: add mod combinations to the existing `selftest`/`bichal compat` run |
| No CONTRIBUTING, SECURITY, CODE_OF_CONDUCT, issue templates | Present | Missing | WS-11 |

## 8. How this was verified

```bash
git ls-files | sed 's|/[^/]*$||' | sort | uniq -c
grep -rn "@Redirect\|@Inject\|@WrapOperation\|@Pseudo\|priority" src/main
grep -rIl -i "vulkan" .                                   # no results
grep -n "Java_" native/vendor/bc7enc_rdo/tessera_bridge.cpp
nm -D --defined-only natives/linux/x86_64/libtessera_bridge.so | grep ' T Java_'
grep -rn "defaultQualityPreset" src                       # no results
```

## 10. Build verification status

At the time of this commit the Gradle build was still running in the sandbox. Its result is recorded in a follow-up
commit on the same branch; nothing in this file claims a build result that was not observed.

## 9. Build verification status

`./gradlew :1.21.1:build` with JDK 25 and bichal-build-logic from this sandbox (Maven Central replaced by Google's
mirror, as for Celeris): **BUILD SUCCESSFUL**. Only 2 tests run (`AtlasPolicyTest`); none of them exercises the JNI
backend.

Native bridge, checked separately:

| Library | Exports | Loads through `JniCompressionBackend` | glibc floor |
|---|---|---|---|
| committed `natives/linux/x86_64/libtessera_bridge.so` (before this change) | `Java_com_panzer_mods_tessera_...` | **No**: `UnsatisfiedLinkError` for `nativeAbiVersion()` | 2.14 |
| rebuilt from this source in an Ubuntu 20.04 (focal) chroot, `bc7enc_rdo@b9438627` | `Java_net_bichal_mods_tessera_...` | **Yes**: `abi=1`, matching `EXPECTED_ABI_VERSION = 1` | 2.14 |

`natives/linux/x86_64/libtessera_bridge.so` is the focal-built one. `aarch64` (Linux) and `windows/x86_64` are **not**
rebuilt: no cross toolchains here, and the Windows build needs MSVC. They still export `com_panzer` names and fail to
load. Rebuild them through `native/build-linux.sh` (Docker) and the Windows CMake steps in the README.

Not run here: `selftest` (headless client), `buildAndCollect`, the other Minecraft nodes, and a comparison of the
native and Java encoders on the same atlases. The probe checks the library loads and reports the expected ABI; it does
not call `nativeCompress`.
