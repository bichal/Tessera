# Vendoring: basis_universal

Upstream: https://github.com/BinomialLLC/basis_universal
Pinned commit: `073eb6a0a34a23d77e75e54f41dcd9653a52da83`
License: Apache-2.0 (`LICENSE`; its `NOTICE` attribution is reproduced in the jar's `META-INF/NOTICE`).

Tessera uses one thing from it: bc7f, the real-time analytical BC7 encoder in the transcoder module
(`basist::bc7f::fast_pack_bc7_auto_rgba`). `bichal vendor` copies `transcoder/basisu*` (plus `LICENSE`
and `NOTICE`) from the pinned commit (`[vendor.basis_universal]` in `mod.stonecutter.properties.toml`);
nothing is patched. `native/bc7f_bridge.cpp` is the only file that includes it.

`native/CMakeLists.txt` builds it as the static library `basisu_bc7f` with KTX2, zstd and the transcode
targets Tessera never uses switched off (`BASISD_SUPPORT_*=0`). XUASTC stays on: bc7f and its tables are
initialised under it. The bridge links with `--gc-sections` / `-dead_strip`, which leaves about 300 KB
of it in the library.
