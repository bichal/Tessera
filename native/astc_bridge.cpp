// astc_bridge.cpp

#include "astc_bridge.h"

#include "astcenc.h"

#include <algorithm>
#include <memory>
#include <thread>
#include <vector>

namespace tessera_astc {

namespace {

/// astcenc's 0-100 quality for each Tessera preset (0 = fastest ... 7 = best).
/// 60 is astcenc's "medium", the default preset 4; 98 and 100 are "thorough" and "exhaustive".
constexpr float kQuality[8] = {0.0f, 10.0f, 25.0f, 40.0f, 60.0f, 80.0f, 98.0f, 100.0f};

constexpr astcenc_swizzle kIdentity = {ASTCENC_SWZ_R, ASTCENC_SWZ_G, ASTCENC_SWZ_B, ASTCENC_SWZ_A};

struct ContextDeleter {
    void operator()(astcenc_context* context) const { astcenc_context_free(context); }
};

}  // namespace

int64_t Compress(const uint8_t* rgba, uint32_t width, uint32_t height, uint32_t block_w, uint32_t block_h,
                 int quality_preset, uint8_t* out, uint64_t out_capacity) {
    if (width == 0 || height == 0) {
        return -1;
    }
    const uint64_t blocks_x = (static_cast<uint64_t>(width) + block_w - 1u) / block_w;
    const uint64_t blocks_y = (static_cast<uint64_t>(height) + block_h - 1u) / block_h;
    const uint64_t out_size = blocks_x * blocks_y * 16u;
    if (out_capacity < out_size) {
        return -1;
    }

    astcenc_config config;
    const float quality = kQuality[std::max(0, std::min(7, quality_preset))];
    if (astcenc_config_init(ASTCENC_PRF_LDR, block_w, block_h, 1, quality, 0, &config) != ASTCENC_SUCCESS) {
        return -1;
    }

    // One worker per block row at most, and no more than the machine has cores.
    const unsigned hardware = std::max(1u, std::thread::hardware_concurrency());
    const unsigned threads = static_cast<unsigned>(std::min<uint64_t>(hardware, blocks_y));

    astcenc_context* raw = nullptr;
    if (astcenc_context_alloc(&config, threads, &raw, nullptr) != ASTCENC_SUCCESS) {
        return -1;
    }
    std::unique_ptr<astcenc_context, ContextDeleter> context(raw);

    void* slice = const_cast<uint8_t*>(rgba);
    astcenc_image image{width, height, 1, ASTCENC_TYPE_U8, &slice};

    std::vector<astcenc_error> status(threads, ASTCENC_SUCCESS);
    std::vector<std::thread> workers;
    for (unsigned i = 1; i < threads; ++i) {
        workers.emplace_back([&, i] {
            status[i] = astcenc_compress_image(context.get(), &image, &kIdentity, out, out_size, i);
        });
    }
    status[0] = astcenc_compress_image(context.get(), &image, &kIdentity, out, out_size, 0);
    for (std::thread& worker : workers) {
        worker.join();
    }
    for (astcenc_error error : status) {
        if (error != ASTCENC_SUCCESS) {
            return -1;
        }
    }
    return static_cast<int64_t>(out_size);
}

int Decompress(const uint8_t* blocks, uint64_t blocks_len, uint32_t width, uint32_t height,
               uint32_t block_w, uint32_t block_h, uint8_t* rgba_out) {
    astcenc_config config;
    if (astcenc_config_init(ASTCENC_PRF_LDR, block_w, block_h, 1, 0.0f, ASTCENC_FLG_DECOMPRESS_ONLY, &config)
        != ASTCENC_SUCCESS) {
        return -1;
    }
    astcenc_context* raw = nullptr;
    if (astcenc_context_alloc(&config, 1, &raw, nullptr) != ASTCENC_SUCCESS) {
        return -1;
    }
    std::unique_ptr<astcenc_context, ContextDeleter> context(raw);

    void* slice = rgba_out;
    astcenc_image image{width, height, 1, ASTCENC_TYPE_U8, &slice};
    return astcenc_decompress_image(context.get(), blocks, blocks_len, &image, &kIdentity, 0) == ASTCENC_SUCCESS
        ? 0 : -1;
}

}  // namespace tessera_astc
