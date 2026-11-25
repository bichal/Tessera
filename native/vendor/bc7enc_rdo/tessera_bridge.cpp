// tessera_bridge.cpp

#include "astc_bridge.h"
#include "bc7f_bridge.h"
#include "bc7enc.h"
#include "bc7decomp.h"
#include "rgbcx.h"

#include <cstring>
#include <cstdint>
#include <algorithm>
#include <mutex>
#include <thread>
#include <vector>

// Constants and Configuration

/// BC7 is encoded by bc7f (Basis Universal); from preset 2 up each block is also encoded by bc7enc
/// and keeps whichever decodes closer to the source. The two fail on different blocks (bc7enc
/// wins many of the partly transparent ones), so even a light bc7enc pass gains 0.5-1 dB.
/// On the vanilla atlases, preset 4 is above the old bc7enc-only preset 7 and 3-4x faster.
struct Bc7encPreset {
    int uber_level;      ///< -1: bc7f alone
    int max_partitions;
};
constexpr Bc7encPreset kBc7encBestOf[8] = {
    {-1, 0},  // 0: bc7f, partially analytical
    {-1, 0},  // 1: bc7f, every candidate measured
    {0, 0},   // 2
    {0, 8},   // 3
    {1, 16},  // 4 (default)
    {2, 24},  // 5
    {3, 40},  // 6
    {6, 64},  // 7
};

/// BC1 quality levels (0-7 scale) mapped to rgbcx's internal levels.
///
/// rgbcx::encode_bc1() only starts using 3-color blocks at level >= 5,
/// so presets below that intentionally sit under the 3-color threshold.
/// This matches the "fastest, lowest fidelity" framing of preset 0.
constexpr int kBc1QualityLevels[8] = {
    2,   // Fastest, lowest quality
    4,   // Fast
    6,   // Balanced (3-color blocks enabled)
    8,   // Balanced higher
    10,  // Good quality
    13,  // High quality
    16,  // Very high quality
    18,  // Maximum quality
};

/// Images below this many blocks (a 128x128 texture) are encoded on the calling thread: animation
/// frames and the small mip levels would spend more on starting threads than on encoding.
constexpr uint64_t kMinBlocksForThreads = 1024;

std::once_flag g_encoders_init;

void InitEncoders() {
    std::call_once(g_encoders_init, [] {
        rgbcx::init(rgbcx::bc1_approx_mode::cBC1Ideal);
        bc7enc_compress_block_init();
    });
}

/// Copies the 4x4 block at (bx, by), repeating the last row and column for partial edge blocks.
static void ReadBlock(const uint8_t* rgba8, uint32_t width, uint32_t height, uint32_t bx, uint32_t by,
                      uint8_t* block) {
    for (uint32_t y = 0; y < 4; ++y) {
        const uint32_t sy = std::min(by * 4u + y, height - 1u);
        for (uint32_t x = 0; x < 4; ++x) {
            const uint32_t sx = std::min(bx * 4u + x, width - 1u);
            std::memcpy(block + (y * 4u + x) * 4u, rgba8 + (static_cast<uint64_t>(sy) * width + sx) * 4u, 4);
        }
    }
}

/// Runs encode_row(by) for every block row, split in contiguous bands over the machine's cores.
/// Blocks are independent, so the output is the same whatever the thread count.
template <class EncodeRow>
static void ForEachBlockRow(uint32_t blocks_x, uint32_t blocks_y, EncodeRow encode_row) {
    const uint64_t total = static_cast<uint64_t>(blocks_x) * blocks_y;
    const unsigned hardware = std::max(1u, std::thread::hardware_concurrency());
    const unsigned threads = total < kMinBlocksForThreads ? 1u
            : static_cast<unsigned>(std::min<uint64_t>(hardware, blocks_y));
    auto band = [&](uint32_t from, uint32_t to) {
        for (uint32_t by = from; by < to; ++by) {
            encode_row(by);
        }
    };
    if (threads <= 1) {
        band(0, blocks_y);
        return;
    }
    std::vector<std::thread> workers;
    workers.reserve(threads - 1);
    for (unsigned i = 1; i < threads; ++i) {
        workers.emplace_back(band, static_cast<uint32_t>(static_cast<uint64_t>(blocks_y) * i / threads),
                             static_cast<uint32_t>(static_cast<uint64_t>(blocks_y) * (i + 1) / threads));
    }
    band(0, static_cast<uint32_t>(blocks_y / threads));
    for (auto& worker : workers) {
        worker.join();
    }
}

// Core Encoding Implementation

/// Encodes straight into a caller-owned buffer: no output allocation, no
/// second copy on the Java side.
///
/// @return bytes written, -1 on invalid input, or
///         -(required size) - 2 if out_capacity is too small.
static int64_t EncodeInto(
    const uint8_t* rgba8,
    uint32_t width,
    uint32_t height,
    int32_t quality_preset,
    bool bc7,
    uint8_t* out,
    uint64_t out_capacity
) {
    if (rgba8 == nullptr || out == nullptr || width == 0 || height == 0) {
        return -1;
    }
    const uint32_t blocks_x = (width + 3u) / 4u;
    const uint32_t blocks_y = (height + 3u) / 4u;
    const uint32_t block_bytes = bc7 ? 16u : 8u;
    const uint64_t size = static_cast<uint64_t>(blocks_x) * blocks_y * block_bytes;
    if (size > out_capacity) {
        return -static_cast<int64_t>(size) - 2;
    }
    InitEncoders();
    const int preset = std::max(0, std::min(7, quality_preset));

    if (!bc7) {
        const int level = kBc1QualityLevels[preset];
        ForEachBlockRow(blocks_x, blocks_y, [&](uint32_t by) {
            uint8_t block[64];
            for (uint32_t bx = 0; bx < blocks_x; ++bx) {
                ReadBlock(rgba8, width, height, bx, by, block);
                rgbcx::encode_bc1(level, out + (static_cast<uint64_t>(by) * blocks_x + bx) * 8u, block, true, true);
            }
        });
        return static_cast<int64_t>(size);
    }

    const uint32_t flags = tessera_bc7f::FlagsForPreset(preset);
    const Bc7encPreset& second = kBc7encBestOf[preset];
    const bool best_of = second.uber_level >= 0;
    bc7enc_compress_block_params bc7enc_params;
    if (best_of) {
        bc7enc_compress_block_params_init(&bc7enc_params);
        bc7enc_compress_block_params_init_linear_weights(&bc7enc_params);
        bc7enc_params.m_uber_level = static_cast<uint32_t>(second.uber_level);
        bc7enc_params.m_max_partitions = static_cast<uint32_t>(second.max_partitions);
    }
    ForEachBlockRow(blocks_x, blocks_y, [&](uint32_t by) {
        uint8_t block[64];
        for (uint32_t bx = 0; bx < blocks_x; ++bx) {
            uint8_t* dst = out + (static_cast<uint64_t>(by) * blocks_x + bx) * 16u;
            ReadBlock(rgba8, width, height, bx, by, block);
            tessera_bc7f::EncodeBlock(dst, block, flags);
            if (best_of) {
                uint8_t alternative[16];
                bc7enc_compress_block(alternative, block, &bc7enc_params);
                if (tessera_bc7f::BlockError(alternative, block) < tessera_bc7f::BlockError(dst, block)) {
                    std::memcpy(dst, alternative, 16);
                }
            }
        }
    });
    return static_cast<int64_t>(size);
}

// JNI entry points
//
// JNI rather than java.lang.foreign: FFM is preview-only on Java 21 (the
// runtime Minecraft 1.21.1 ships), so it would not load for players without
// --enable-preview. JNI works on every JDK with no flags, and with direct
// ByteBuffers on both sides nothing is copied across the boundary.

#include <jni.h>

/// Bumped on any signature/semantics change; checked by JniCompressionBackend
/// so a stale library in the extraction cache is rejected instead of misbehaving.
static constexpr jint kTesseraJniAbiVersion = 3;

extern "C" {

JNIEXPORT jint JNICALL
Java_net_bichal_mods_tessera_compress_backend_JniCompressionBackend_nativeAbiVersion(JNIEnv*, jclass) {
    return kTesseraJniAbiVersion;
}

/// @param src  direct ByteBuffer, RGBA8 at its base address (Java passes a slice)
/// @param dst  direct ByteBuffer receiving BC blocks at its base address
/// @return bytes written, -1 on failure, or -(required) - 2 if dst is too small
JNIEXPORT jlong JNICALL
Java_net_bichal_mods_tessera_compress_backend_JniCompressionBackend_nativeCompress(
    JNIEnv* env, jclass, jobject src, jint width, jint height, jint quality, jboolean bc7, jobject dst
) {
    if (width <= 0 || height <= 0) {
        return -1;
    }
    auto* in = static_cast<const uint8_t*>(env->GetDirectBufferAddress(src));
    auto* out = static_cast<uint8_t*>(env->GetDirectBufferAddress(dst));
    jlong in_capacity = env->GetDirectBufferCapacity(src);
    jlong out_capacity = env->GetDirectBufferCapacity(dst);
    // Bounds are re-checked here, not trusted from Java: an undersized input
    // would otherwise be an out-of-bounds native read.
    if (in == nullptr || out == nullptr || in_capacity < 0 || out_capacity < 0
        || static_cast<uint64_t>(in_capacity) < static_cast<uint64_t>(width) * static_cast<uint64_t>(height) * 4u) {
        return -1;
    }
    return static_cast<jlong>(EncodeInto(in, static_cast<uint32_t>(width), static_cast<uint32_t>(height),
                                         quality, bc7 == JNI_TRUE, out, static_cast<uint64_t>(out_capacity)));
}

/// Decodes BC7 blocks back to RGBA8 (the round trip the self-test checks).
/// @param src  direct ByteBuffer of BC7 blocks, row-major, 16 bytes each
/// @param dst  direct ByteBuffer receiving width * height RGBA8 texels
/// @return 0 on success, -1 on bad arguments or an block the decoder rejects
JNIEXPORT jint JNICALL
Java_net_bichal_mods_tessera_compress_backend_JniCompressionBackend_nativeDecompressBc7(
    JNIEnv* env, jclass, jobject src, jint width, jint height, jobject dst
) {
    if (width <= 0 || height <= 0) {
        return -1;
    }
    auto* in = static_cast<const uint8_t*>(env->GetDirectBufferAddress(src));
    auto* out = static_cast<uint8_t*>(env->GetDirectBufferAddress(dst));
    jlong in_capacity = env->GetDirectBufferCapacity(src);
    jlong out_capacity = env->GetDirectBufferCapacity(dst);
    const uint64_t blocks_x = (static_cast<uint64_t>(width) + 3u) / 4u;
    const uint64_t blocks_y = (static_cast<uint64_t>(height) + 3u) / 4u;
    const uint64_t need_in = blocks_x * blocks_y * 16u;
    const uint64_t need_out = static_cast<uint64_t>(width) * static_cast<uint64_t>(height) * 4u;
    if (in == nullptr || out == nullptr || in_capacity < 0 || out_capacity < 0
        || static_cast<uint64_t>(in_capacity) < need_in || static_cast<uint64_t>(out_capacity) < need_out) {
        return -1;
    }
    for (uint64_t by = 0; by < blocks_y; ++by) {
        for (uint64_t bx = 0; bx < blocks_x; ++bx) {
            bc7decomp::color_rgba texels[16];
            if (!bc7decomp::unpack_bc7(in + (by * blocks_x + bx) * 16u, texels)) {
                return -1;
            }
            // Partial blocks at the right and bottom edges are cropped.
            for (uint64_t ty = 0; ty < 4 && by * 4u + ty < static_cast<uint64_t>(height); ++ty) {
                for (uint64_t tx = 0; tx < 4 && bx * 4u + tx < static_cast<uint64_t>(width); ++tx) {
                    const uint64_t x = bx * 4u + tx;
                    const uint64_t y = by * 4u + ty;
                    std::memcpy(out + (y * static_cast<uint64_t>(width) + x) * 4u,
                                texels[ty * 4u + tx].m_comps, 4);
                }
            }
        }
    }
    return 0;
}


JNIEXPORT jlong JNICALL
Java_net_bichal_mods_tessera_compress_backend_JniCompressionBackend_nativeCompressAstc(
    JNIEnv* env, jclass, jobject src, jint width, jint height, jint block_w, jint block_h, jint quality, jobject dst
) {
    if (width <= 0 || height <= 0 || block_w < 4 || block_h < 4 || block_w > 12 || block_h > 12) {
        return -1;
    }
    auto* in = static_cast<const uint8_t*>(env->GetDirectBufferAddress(src));
    auto* out = static_cast<uint8_t*>(env->GetDirectBufferAddress(dst));
    jlong in_capacity = env->GetDirectBufferCapacity(src);
    jlong out_capacity = env->GetDirectBufferCapacity(dst);
    // Re-checked here, not trusted from Java: an undersized input would be an out-of-bounds native read.
    if (in == nullptr || out == nullptr || in_capacity < 0 || out_capacity < 0
        || static_cast<uint64_t>(in_capacity) < static_cast<uint64_t>(width) * static_cast<uint64_t>(height) * 4u) {
        return -1;
    }
    return static_cast<jlong>(tessera_astc::Compress(
        in, static_cast<uint32_t>(width), static_cast<uint32_t>(height), static_cast<uint32_t>(block_w),
        static_cast<uint32_t>(block_h), quality, out, static_cast<uint64_t>(out_capacity)));
}

JNIEXPORT jint JNICALL
Java_net_bichal_mods_tessera_compress_backend_JniCompressionBackend_nativeDecompressAstc(
    JNIEnv* env, jclass, jobject src, jint width, jint height, jint block_w, jint block_h, jobject dst
) {
    if (width <= 0 || height <= 0 || block_w < 4 || block_h < 4 || block_w > 12 || block_h > 12) {
        return -1;
    }
    auto* in = static_cast<const uint8_t*>(env->GetDirectBufferAddress(src));
    auto* out = static_cast<uint8_t*>(env->GetDirectBufferAddress(dst));
    jlong in_capacity = env->GetDirectBufferCapacity(src);
    jlong out_capacity = env->GetDirectBufferCapacity(dst);
    const uint64_t blocks_x = (static_cast<uint64_t>(width) + static_cast<uint64_t>(block_w) - 1u) / static_cast<uint64_t>(block_w);
    const uint64_t blocks_y = (static_cast<uint64_t>(height) + static_cast<uint64_t>(block_h) - 1u) / static_cast<uint64_t>(block_h);
    const uint64_t need_in = blocks_x * blocks_y * 16u;
    const uint64_t need_out = static_cast<uint64_t>(width) * static_cast<uint64_t>(height) * 4u;
    if (in == nullptr || out == nullptr || in_capacity < 0 || out_capacity < 0
        || static_cast<uint64_t>(in_capacity) < need_in || static_cast<uint64_t>(out_capacity) < need_out) {
        return -1;
    }
    return tessera_astc::Decompress(in, need_in, static_cast<uint32_t>(width), static_cast<uint32_t>(height),
                                    static_cast<uint32_t>(block_w), static_cast<uint32_t>(block_h), out);
}

}  // extern "C"
