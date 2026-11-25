# Vendoring: bc7enc_rdo

Upstream: https://github.com/richgel999/bc7enc_rdo
Pinned commit: `b9438627eef73a1157e84201b6fa6eb2ffd6d9f0` (verified via `git ls-remote` against upstream `master`)
License: dual MIT / public-domain-equivalent, per upstream `LICENSE`. Compatible with linking into Tessera.

## Why vendored, not a Git submodule

`native/CMakeLists.txt` compiles these sources directly into `tessera_bridge`. `bichal vendor` fetches the
exact upstream commit (`[vendor.bc7enc_rdo]` in `mod.stonecutter.properties.toml`) and applies two small patches (see below) without adding submodule
management to `git clone`.

## What's actually needed

`bc7enc_rdo` ships a CLI tool, an image-level RDO encoder, ISPC bindings, and a PNG codec that
Tessera doesn't use. The bridge splits images into 4x4 blocks itself (on every core) and calls the
block encoders directly, so only these are fetched and compiled:

- `bc7enc.h` / `bc7enc.cpp` — the BC7 block encoder, the second opinion next to bc7f
  (`native/vendor/basisu`) from `compressionQuality` 2 up
- `rgbcx.h` / `rgbcx.cpp`, `rgbcx_table4.h`, `rgbcx_table4_small.h` — the BC1 block encoder
- `bc7decomp.h` / `bc7decomp.cpp` — the BC7 decoder behind `nativeDecompressBc7` (the round-trip check)

Not vendored: `rdo_bc_encoder.*`, `ert.*`, `utils.*`, `dds_defs.h` (the image-level encoder and its
RDO post-pass, which the bridge used to drive single-threaded), `bc7decomp_ref.cpp`, `lodepng.*`,
`test.cpp` and the ISPC backend. Nothing is patched.

## Populating this directory

Run `bichal vendor .` (bichal-build-logic's tool) from the repository root before the first native
build; CI runs it before every native build. It fetches the pinned commit, copies only the files
listed in `[vendor.bc7enc_rdo]` and leaves a `.vendored.json` marker, so a second run is a no-op
until the pin changes. Only `VENDORING.md`, `LICENSE` and
`tessera_bridge.cpp` are tracked in git; the rest is regenerated and git-ignored.
