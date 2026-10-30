// tessera_bridge.cpp

#include "astc_bridge.h"
#include "rdo_bc_encoder.h"
#include "bc7decomp.h"

#include <cstring>
#include <cstdint>
#include <algorithm>
#include <thread>

// Constants and Configuration

/// Quality preset for BC7 compression.
struct QualityPreset {
    int bc7_uber_level;           ///< BC7 encoder uber quality level
    int max_partitions_to_scan;   ///< Maximum partitions to scan for RDO
};

/// BC7 quality presets (0-7 scale).
///
/// Higher values = better quality but slower compression.
/// Values are balanced to provide a smooth quality/performance tradeoff.
constexpr QualityPreset kQualityPresets[8] = {
    {0, 8},   // Fastest, lowest quality
    {1, 16},  // Fast
    {2, 24},  // Balanced
    {2, 32},  // Balanced higher
    {3, 40},  // Good quality
    {4, 48},  // High quality
    {5, 56},  // Very high quality
    {6, 64},  // Maximum quality
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

// Parameter Configuration

/// Create BC7 compression parameters for a given quality preset.
///
/// @param quality_preset Quality level (0-7, clamped to valid range)
/// @return Configured rdo_bc_params for BC7 compression
rdo_bc::rdo_bc_params ParamsForPreset(int32_t quality_preset) {
    // Clamp to valid range
    int clamped = std::max(0, std::min(7, quality_preset));
    const QualityPreset& preset = kQualityPresets[clamped];

    rdo_bc::rdo_bc_params params;
    params.m_bc7_uber_level = preset.bc7_uber_level;
    params.m_bc7enc_max_partitions_to_scan = preset.max_partitions_to_scan;
    params.m_rdo_multithreading = true;

    // Detect and use available hardware threads
    unsigned int detected_threads = std::thread::hardware_concurrency();
    params.m_rdo_max_threads = (detected_threads > 0) ?
        static_cast<uint32_t>(detected_threads) : 1;

    params.m_status_output = false;  // Keep logs clean
    return params;
}

/// Create BC1 compression parameters for a given quality preset.
///
/// @param quality_preset Quality level (0-7, clamped to valid range)
/// @return Configured rdo_bc_params for BC1 compression
rdo_bc::rdo_bc_params Bc1ParamsForPreset(int32_t quality_preset) {
    // Clamp to valid range
    int clamped = std::max(0, std::min(7, quality_preset));

    rdo_bc::rdo_bc_params params;
    params.m_dxgi_format = DXGI_FORMAT_BC1_UNORM;
    params.m_bc1_quality_level = kBc1QualityLevels[clamped];
    params.m_rdo_multithreading = true;

    // Detect and use available hardware threads
    unsigned int detected_threads = std::thread::hardware_concurrency();
    params.m_rdo_max_threads = (detected_threads > 0) ?
        static_cast<uint32_t>(detected_threads) : 1;

    params.m_status_output = false;  // Keep logs clean
    return params;
}

// Core Encoding Implementation

/// Encodes straight into a caller-owned buffer: no output allocation, no
/// second copy on the Java side.
///
/// @return bytes written, -1 on invalid input/encoder failure, or
///         -(required size) - 2 if out_capacity is too small.
static int64_t EncodeInto(
    const uint8_t* rgba8,
    uint32_t width,
    uint32_t height,
    const rdo_bc::rdo_bc_params& params,
    uint8_t* out,
    uint64_t out_capacity
) {
    if (rgba8 == nullptr || out == nullptr || width == 0 || height == 0) {
        return -1;
    }
    utils::image_u8 source_image(width, height);
    std::memcpy(source_image.get_pixels().data(), rgba8, static_cast<size_t>(width) * height * 4);

    rdo_bc::rdo_bc_params local_params = params;
    rdo_bc::rdo_bc_encoder encoder;
    if (!encoder.init(source_image, local_params) || !encoder.encode()) {
        return -1;
    }
    uint64_t size = encoder.get_total_blocks_size_in_bytes();
    if (size > out_capacity) {
        return -static_cast<int64_t>(size) - 2;
    }
    std::memcpy(out, encoder.get_blocks(), size);
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
    const rdo_bc::rdo_bc_params params = bc7 ? ParamsForPreset(quality) : Bc1ParamsForPreset(quality);
    return static_cast<jlong>(EncodeInto(in, static_cast<uint32_t>(width), static_cast<uint32_t>(height),
                                         params, out, static_cast<uint64_t>(out_capacity)));
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
