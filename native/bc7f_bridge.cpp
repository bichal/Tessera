// bc7f_bridge.cpp

#include "bc7f_bridge.h"

#include "basisu_transcoder.h"
#include "basisu_transcoder_internal.h"

#include <cstring>
#include <mutex>

namespace tessera_bc7f {

namespace {

using namespace basist::bc7f;

// No p-bit optimisation on mode 6: with it, opaque texels can come back with alpha 254, which
// shows on translucent render types. Without it the visible error is the same (within 0.05 dB on
// the vanilla atlases) and opaque alpha stays 255.
constexpr uint32_t kPartial = cPackBC7FlagDefaultPartiallyAnalytical & ~cPackBC7FlagPBitOptMode6;
constexpr uint32_t kFull = cPackBC7FlagDefaultNonAnalytical & ~cPackBC7FlagPBitOptMode6;

std::once_flag g_init;

void Init() {
    std::call_once(g_init, [] { basist::basisu_transcoder_init(); });
}

void ToPixels(basist::color_rgba* pixels, const uint8_t* rgba_block) {
    for (int i = 0; i < 16; i++) {
        pixels[i].set(rgba_block[i * 4], rgba_block[i * 4 + 1], rgba_block[i * 4 + 2], rgba_block[i * 4 + 3]);
    }
}

}  // namespace

uint32_t FlagsForPreset(int quality_preset) {
    // Preset 0 resolves the mode from block statistics and measures only the complex blocks;
    // the others measure every candidate (about twice the time, still a few ms per atlas).
    return quality_preset >= 1 ? kFull : kPartial;
}

void EncodeBlock(uint8_t* out_block, const uint8_t* rgba_block, uint32_t flags) {
    Init();
    basist::color_rgba pixels[16];
    ToPixels(pixels, rgba_block);
    fast_pack_bc7_auto_rgba(out_block, pixels, flags);
}

uint32_t BlockError(const uint8_t* block, const uint8_t* rgba_block) {
    Init();
    basist::color_rgba pixels[16];
    ToPixels(pixels, rgba_block);
    return calc_sse(block, pixels);
}

}  // namespace tessera_bc7f
