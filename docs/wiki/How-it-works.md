# How it works

- Every atlas is re-encoded after vanilla's upload, with its full mip chain. The format follows the content and the GPU:

  | Atlas | PC graphics cards | ARM GPUs (ASTC) |
  |---|---|---|
  | all texels opaque | BC1, 4 bits per texel | ASTC 6x6, 3.56 bits per texel |
  | any transparency | BC7, 8 bits per texel | ASTC 4x4, 8 bits per texel |

  Where a GPU has none of them, the atlas stays as vanilla. Tessera checks the driver at startup
  (`GL_EXT_texture_compression_s3tc`, `GL_ARB_texture_compression_bptc`, `GL_KHR_texture_compression_astc_ldr`).
- An atlas that holds animated textures is never compressed: it stays RGBA8888, so animation frames reach it the way
  vanilla writes them. [Kerria](https://modrinth.com/mod/kerria-opt) speeds up exactly that upload, so the two work on
  different atlases: Tessera on the static ones, Kerria on the animated ones.
- Encoding runs on a background thread; only the GPU upload happens on the render thread.
- Fully transparent texels are colour-bled before encoding, so cutout and GUI textures get no fringes.
- A content-addressed disk cache (`tessera-cache/`, size-capped, least-recently-used eviction) skips re-encoding
  unchanged atlases. Entries are stored compressed with Celeris (zstd), typically at about a tenth of their size.
  An atlas is keyed twice: by a digest of its layout and sprite pixels (the whole encoded mip chain in one file, so a
  launch with nothing changed skips pixel assembly, mip generation and encoding) and, when only some of it changed,
  by each mip level's pixels.
- The native encoders ([bc7enc_rdo](https://github.com/richgel999/bc7enc_rdo) for BC7 and BC1, ARM's
  [astc-encoder](https://github.com/ARM-software/astc-encoder) for ASTC) are loaded over JNI (no launch flags), bundled
  for Windows x86_64, Linux x86_64 and AArch64 (glibc 2.17+) and macOS (Apple Silicon and Intel). Anywhere else, or if
  they fail to load (Android launchers use bionic libc), pure-Java encoders take over. The Java ASTC encoder is simpler
  (one colour line per block): it checks its own result and leaves an atlas as vanilla rather than store one that would
  look worse than about 27 dB PSNR.

[Back to Home](Home.md)
