# How it works

- Every atlas is re-encoded to BC7 after vanilla's upload, with its full mip chain. Encoding runs on a background
  thread; only the GPU upload happens on the render thread.
- Fully transparent texels are colour-bled before encoding, so cutout and GUI textures get no fringes.
- A content-addressed disk cache (`tessera-cache/`, size-capped, least-recently-used eviction) skips re-encoding
  unchanged atlases.
- Animated sprites are written as BC blocks with `glCompressedTexSubImage2D`: keyframes are encoded once and cached,
  interpolated frames on the fly; at mip levels smaller than a 4x4 block only the affected blocks are patched.
- The native encoder ([bc7enc_rdo](https://github.com/richgel999/bc7enc_rdo)) is loaded over JNI (no launch flags),
  bundled for Windows x86_64, Linux x86_64 and Linux AArch64 (glibc 2.17+). Anywhere else, or if it fails to load,
  the pure-Java encoder takes over.

[Back to Home](Home.md)
