# Audit report: Tessera

**Status:** historical record, not normative. [`GROUND_TRUTH.md`](./GROUND_TRUTH.md) describes the repository as it
is; this file records what the audit asked for and what was done. Statuses:
`implemented`, `partial`, `not applicable` (the audit's premise does not hold for this repository), `not started`.

Branch `ccr-a954e01c-a4i778`. Commit references are on that branch.

## Verdicts on the audit's claims

| Audit claim | Verdict | Evidence |
|---|---|---|
| Tessera renders terrain with Vulkan mesh shaders | **Wrong.** No Vulkan code or dependency exists. Tessera re-encodes vanilla OpenGL atlases to BC7 | `grep -rI vulkan` finds nothing |
| Tessera is a mixin mod for Sodium/Iris that needs `@Redirect` migrated to `@WrapOperation` | **Wrong.** Five client mixins on vanilla `TextureAtlas` and `SpriteContents`: `@Inject` and accessors, no `@Redirect`, no mixins into Sodium, Iris or Embeddium | `src/main/java/.../mixin/` |
| Tessera supports Fabric and macOS | **Wrong.** NeoForge, client only. macOS has no BPTC support | `mod.stonecutter.properties.toml` (`side = "CLIENT"`) |
| The native encoder runs over JNI with the Java fallback | **Was wrong.** JNI names did not match the class, so the committed native encoder did not load. Fixed in source; Linux x86_64 binary rebuilt. Released jars were not checked | commits `c20e07e`, `8bb8179`; §Native below |
| Fixed-point or GPU offload is required | **Not applicable.** Nothing in Tessera needs them; they are not part of the design | — |

## Workstreams

| WS | Item | Status | What exists now |
|---|---|---|---|
| WS-1 | Ground truth | implemented | `docs/audit/GROUND_TRUTH.md`, including build verification |
| WS-1 | Baseline benchmarks (`baseline.json`) | not started | No benchmark harness in Tessera |
| WS-2 | README and public docs in the new structure | not started | README unchanged |
| WS-3 | Javadoc and Doxygen | not started | Javadoc has no `-Xdoclint` gate. Dokka does not apply (Java sources) |
| WS-4 | Java hot path (FFM, `Arena`) | not applicable | Tessera uses JNI, not FFM |
| WS-5 | Native kernel (SIMD, determinism) | not applicable | `bc7enc_rdo` is the encoder; no ISA dispatch in Tessera |
| WS-6 | ASan, UBSan, fuzzing, JMH gate | not started | No sanitizer or fuzz job for `tessera_bridge` |
| WS-7 | CI/CD | partial | `release.yml` gate for Linux x86_64: native build in Docker, glibc floor, JNI symbols, native test. No gate for aarch64, Windows, CodeQL, cosign, Zig |
| WS-8 | Mixin compatibility | not applicable as written | Mixins are on vanilla classes; there is nothing to migrate. A coexistence run with Sodium, Embeddium, Iris is not done |
| WS-9 | GPU offload (exploratory) | not applicable | Tessera does no GPU compute |
| WS-10 | JVM tuning guide | not started | — |
| WS-11 | Community files and templates | not started | No `CONTRIBUTING.md`, `SECURITY.md`, issue templates |
| WS-12 | Repository hygiene | partial | `.gitignore` excludes native binaries; `scripts/check-no-binaries.sh`; no `.editorconfig`, spotless or errorprone |
| WS-13 | This report | implemented | this file |

## What changed on this branch

| Change | Where | Verified by |
|---|---|---|
| JNI prefix `Java_com_*` → `Java_net_bichal_*` | `native/vendor/bc7enc_rdo/tessera_bridge.cpp` | `nm -D`; the gate fails on the old binary (`verify-jni-symbols.sh` run against the binary committed at base `a7673965`) |
| Native decoder `nativeDecompressBc7`, ABI 1 → 2 | same file, `JniCompressionBackend.java` | `NativeBridgeSelfTest`: encode, decode, PSNR |
| Linux x86_64 binary rebuilt | `natives/` (removed from git afterwards) | built in Ubuntu 20.04 (focal) chroot: glibc 2.14 floor, same as before |
| Committed binaries removed | `git rm natives/...` | `scripts/check-no-binaries.sh` |
| JNI gate | `scripts/verify-jni-symbols.sh`, `verifyJniSymbols` task in `build.gradle.kts` (part of `check`) | `-PrequireNative=true` run passes on the new bridge |
| Round-trip self-test | `src/test/.../compress/backend/NativeBridgeSelfTest.java` | `:1.21.1:test`: 2 of 2 pass, none skipped |
| Release gate | `.github/workflows/release.yml` | not run on GitHub from here; needs to be made a required check for tags |

The self-test is named `NativeBridgeSelfTest`, not `TesseraSelfTest`: the existing `TesseraSelfTest` is the in-game
OpenGL check (`-Dtessera.selftest`), and the two would collide.

## Known gaps

- Released jars from before this branch still carry the old JNI names and fall back to the Java encoder.
  They are fixed only when a release is built from this branch.
- The aarch64 and Windows bridges in `natives/` no longer exist in git. CI builds them, but this branch has not run
  CI, and the release gate does not check them yet.
- `selftest` (headless client, OpenGL) and the other Minecraft nodes have not been run for this branch.
- The `nativeCompress` path is exercised through the encode half of the round trip. Its output is not compared with
  the Java encoder.
