// bc7f_bridge.h: the part of the bridge that talks to Basis Universal's bc7f BC7 encoder.
// Its own translation unit, so the transcoder headers stay out of tessera_bridge.cpp.
#pragma once

#include <cstdint>

namespace tessera_bc7f {

/// bc7f flags for Tessera's 0-7 quality preset.
uint32_t FlagsForPreset(int quality_preset);

/// Encodes one 4x4 block of RGBA8 texels (row-major, 64 bytes) into 16 bytes of BC7.
/// Thread-safe; the encoder's tables are initialised on the first call.
void EncodeBlock(uint8_t* out_block, const uint8_t* rgba_block, uint32_t flags);

/// Squared RGBA error of an encoded block against its source texels.
uint32_t BlockError(const uint8_t* block, const uint8_t* rgba_block);

}  // namespace tessera_bc7f
