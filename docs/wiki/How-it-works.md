# How it works

- Atlases are split in two by what they hold:
  - **Static atlases** (no animated texture) are re-encoded after vanilla's upload, with their full mip chain, in the
    smallest format their content allows.
  - **Animated atlases** (any animated texture, such as the block atlas with water, lava and fire) are never
    compressed: they stay RGBA8888, so animation frames reach them the way vanilla writes them.
    [Kerria](https://modrinth.com/mod/kerria-opt) speeds up exactly that upload, so the two work side by side:
    Tessera on the static atlases, Kerria on the animated ones. F3 shows both halves.

  An atlas stays one texture: chunks, items, particles, Sodium and shader packs all sample a single texture per
  atlas, so its animated sprites cannot be moved to a second one without changing how the game renders.
- The format of a static atlas follows its content and the GPU:

  | Static atlas | PC graphics cards | ARM GPUs (ASTC) |
  |---|---|---|
  | all texels opaque | BC1, 4 bits per texel | ASTC 6x6, 3.56 bits per texel |
  | texels opaque or fully transparent, no mipmaps | BC1 with 1-bit alpha, 4 bits per texel | ASTC 4x4, 8 bits per texel |
  | any partial transparency | BC7, 8 bits per texel | ASTC 4x4, 8 bits per texel |

  BC1 with 1-bit alpha keeps alpha exact at half of BC7's size. It is checked after encoding: an atlas whose colours
  would fall below 32 dB PSNR is encoded as BC7 instead (and remembered in the cache). Mipmapped atlases never take
  it, because averaging a sprite's edge into its mip levels produces partial alpha.

  Where a GPU has none of them, the atlas stays as vanilla. Tessera checks the driver at startup
  (`GL_EXT_texture_compression_s3tc`, `GL_ARB_texture_compression_bptc`, `GL_KHR_texture_compression_astc_ldr`), and
  after uploading checks that the driver took the format; if not, the atlas stays as vanilla uploaded it.
- Encoding runs on a background thread; only the GPU upload happens on the render thread.
- Fully transparent texels are colour-bled before encoding, so cutout and GUI textures get no fringes.
- A content-addressed disk cache (`tessera-cache/`, size-capped, least-recently-used eviction) skips re-encoding
  unchanged atlases. Entries are stored compressed with Celeris (zstd), typically at about a tenth of their size.
  An atlas is keyed by a digest of its layout and sprite pixels: the whole encoded mip chain is one file, so a launch
  with nothing changed skips pixel assembly, mip generation and encoding.
- The native encoders ([bc7enc_rdo](https://github.com/richgel999/bc7enc_rdo) for BC7 and BC1, ARM's
  [astc-encoder](https://github.com/ARM-software/astc-encoder) for ASTC) are loaded over JNI (no launch flags), bundled
  for Windows x86_64, Linux x86_64 and AArch64 (glibc 2.17+) and macOS (Apple Silicon and Intel). Anywhere else, or if
  they fail to load (Android launchers use bionic libc), pure-Java encoders take over. The Java ASTC encoder is simpler
  (one colour line per block): it checks its own result and leaves an atlas as vanilla rather than store one that would
  look worse than about 27 dB PSNR.

[Back to Home](Home.md)
