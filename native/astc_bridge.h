// astc_bridge.h: the part of the bridge that talks to ARM's astc-encoder.
#pragma once

#include <cstdint>

namespace tessera_astc {

/// Compresses width x height RGBA8 texels (linear, not sRGB) to ASTC LDR blocks of block_w x block_h.
/// quality_preset is Tessera's 0-7 scale. Returns the bytes written (16 per block) or -1.
int64_t Compress(const uint8_t* rgba, uint32_t width, uint32_t height, uint32_t block_w, uint32_t block_h,
                 int quality_preset, uint8_t* out, uint64_t out_capacity);

/// Decompresses ASTC LDR blocks to width x height RGBA8 texels. Returns 0, or -1 if the input is bad.
/// Only for the round-trip check; the game lets the GPU decode.
int Decompress(const uint8_t* blocks, uint64_t blocks_len, uint32_t width, uint32_t height,
               uint32_t block_w, uint32_t block_h, uint8_t* rgba_out);

}  // namespace tessera_astc
