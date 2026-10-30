# Vendoring: astc-encoder

Upstream: https://github.com/ARM-software/astc-encoder
Pinned commit: `d0f91386350222fcbf6da533c0f7a3f9b5c6b783`
License: Apache-2.0 (`LICENSE`, also in the jar's `META-INF/NOTICE`).

`native/CMakeLists.txt` compiles the library sources (`Source/astcenc*.cpp`, without the
command line tool `astcenccli_*`) into `tessera_bridge`, as a separate static library: the bridge
is built with `-ffast-math`, which astcenc must not be (its results are meant to be bit-exact
across ISAs). `bichal vendor` copies them from the pinned commit (`[vendor.astcenc]` in `mod.stonecutter.properties.toml`); nothing is patched.

The instruction set follows the target: SSE4.1 + POPCNT on x86_64 (the same floor as the rest of
the bridge), NEON on arm64 (Apple Silicon, Android, Linux AArch64), plain C++ elsewhere.
