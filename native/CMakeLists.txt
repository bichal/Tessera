# 3.16 so the Linux build runs on Ubuntu 20.04 (glibc 2.31, CMake 3.16):
# binaries built there load on that and every newer distro.
cmake_minimum_required(VERSION 3.16)
project(tessera_bridge LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(CMAKE_CXX_VISIBILITY_PRESET hidden)
set(CMAKE_VISIBILITY_INLINES_HIDDEN ON)

if(NOT CMAKE_BUILD_TYPE)
    set(CMAKE_BUILD_TYPE Release)
endif()

set(VENDOR_DIR ${CMAKE_CURRENT_SOURCE_DIR}/vendor/bc7enc_rdo)

# Only the block encoders and the decoder: the image-level rdo_bc_encoder (and its RDO post-pass)
# is not used, the bridge splits images into blocks itself.
set(VENDOR_SOURCES
    ${VENDOR_DIR}/bc7enc.cpp
    ${VENDOR_DIR}/rgbcx.cpp
    ${VENDOR_DIR}/bc7decomp.cpp
)

foreach(required bc7enc.h rgbcx.h rgbcx_table4.h rgbcx_table4_small.h bc7decomp.h)
    if(NOT EXISTS ${VENDOR_DIR}/${required})
        message(FATAL_ERROR
            "Vendor sources missing or incomplete in ${VENDOR_DIR} (missing "
            "${required}). This build expects the sources `bichal vendor` fetches ([vendor.*] in "
            "mod.stonecutter.properties.toml). Run:\n    bichal vendor .\nand re-run the build.")
    endif()
endforeach()

# ARM's astc-encoder is its own static library: the bridge below is built with -ffast-math, which
# astcenc must not be (its output is meant to be identical on every instruction set).
set(ASTC_DIR ${CMAKE_CURRENT_SOURCE_DIR}/vendor/astcenc)
if(NOT EXISTS ${ASTC_DIR}/astcenc.h)
    message(FATAL_ERROR
        "astc-encoder sources missing in ${ASTC_DIR}. Run:\n    bichal vendor .\nand re-run the build.")
endif()
file(GLOB ASTC_SOURCES ${ASTC_DIR}/astcenc_*.cpp)
add_library(astcenc STATIC ${ASTC_SOURCES})
target_include_directories(astcenc PUBLIC ${ASTC_DIR})
set_target_properties(astcenc PROPERTIES POSITION_INDEPENDENT_CODE ON)
# The instruction set follows the target: SSE4.1 + POPCNT on x86_64 (the floor of the whole bridge),
# NEON on arm64 (Apple Silicon, Android, Linux AArch64), plain C++ anywhere else.
if(CMAKE_SYSTEM_PROCESSOR MATCHES "x86_64|AMD64")
    target_compile_definitions(astcenc PRIVATE ASTCENC_SSE=41 ASTCENC_AVX=0 ASTCENC_POPCNT=1 ASTCENC_F16C=0
        ASTCENC_NEON=0 ASTCENC_SVE=0)
    if(NOT MSVC)
        target_compile_options(astcenc PRIVATE -msse4.1 -mpopcnt)
    endif()
elseif(CMAKE_SYSTEM_PROCESSOR MATCHES "aarch64|arm64|ARM64")
    target_compile_definitions(astcenc PRIVATE ASTCENC_NEON=1 ASTCENC_SSE=0 ASTCENC_AVX=0 ASTCENC_POPCNT=0
        ASTCENC_F16C=0 ASTCENC_SVE=0)
else()
    target_compile_definitions(astcenc PRIVATE ASTCENC_NEON=0 ASTCENC_SSE=0 ASTCENC_AVX=0 ASTCENC_POPCNT=0
        ASTCENC_F16C=0 ASTCENC_SVE=0)
endif()
if(MSVC)
    target_compile_options(astcenc PRIVATE $<$<CONFIG:Release>:/O2> /fp:precise)
else()
    # Not -ffast-math, and no FMA contraction: results must match astcenc's own builds.
    target_compile_options(astcenc PRIVATE -O3 -ffp-contract=off -fno-exceptions -fno-rtti)
endif()

# Basis Universal's transcoder module, for its bc7f BC7 encoder only. Everything the transcoder
# can switch off is: KTX2 (and with it zstd) and the targets Tessera never transcodes to. XUASTC
# stays, bc7f is built under it. A static library of its own because the file is large and needs
# /bigobj on MSVC; same floating-point settings as the bridge.
set(BASISU_DIR ${CMAKE_CURRENT_SOURCE_DIR}/vendor/basisu)
if(NOT EXISTS ${BASISU_DIR}/basisu_transcoder.cpp)
    message(FATAL_ERROR
        "basis_universal sources missing in ${BASISU_DIR}. Run:\n    bichal vendor .\nand re-run the build.")
endif()
add_library(basisu_bc7f STATIC ${CMAKE_CURRENT_SOURCE_DIR}/bc7f_bridge.cpp ${BASISU_DIR}/basisu_transcoder.cpp)
target_include_directories(basisu_bc7f PRIVATE ${BASISU_DIR})
set_target_properties(basisu_bc7f PROPERTIES POSITION_INDEPENDENT_CODE ON)
target_compile_definitions(basisu_bc7f PRIVATE
    BASISD_SUPPORT_KTX2=0 BASISD_SUPPORT_KTX2_ZSTD=0 BASISD_SUPPORT_PVRTC1=0 BASISD_SUPPORT_PVRTC2=0
    BASISD_SUPPORT_ETC2_EAC_A8=0 BASISD_SUPPORT_ETC2_EAC_RG11=0 BASISD_SUPPORT_ATC=0 BASISD_SUPPORT_FXT1=0
    $<$<CONFIG:Release>:NDEBUG>)
if(MSVC)
    target_compile_options(basisu_bc7f PRIVATE $<$<CONFIG:Release>:/O2> /fp:fast /bigobj /GR- /W0 /Gy)
else()
    target_compile_options(basisu_bc7f PRIVATE -O3 -ffast-math -fno-exceptions -fno-rtti -w
        -ffunction-sections -fdata-sections)
    if(CMAKE_SYSTEM_PROCESSOR MATCHES "x86_64|AMD64")
        target_compile_options(basisu_bc7f PRIVATE -msse4.1)
    endif()
endif()

add_library(tessera_bridge SHARED
    ${VENDOR_SOURCES}
    ${VENDOR_DIR}/tessera_bridge.cpp
    ${CMAKE_CURRENT_SOURCE_DIR}/astc_bridge.cpp
)

target_include_directories(tessera_bridge PRIVATE ${VENDOR_DIR} ${CMAKE_CURRENT_SOURCE_DIR})
target_link_libraries(tessera_bridge PRIVATE astcenc basisu_bc7f)

# The encoder spawns worker threads (std::thread). Without this, on glibc < 2.34
# libpthread is not recorded as NEEDED and pthread_* only resolve because the
# JVM happened to load libpthread first.
# -pthread (driver flag) rather than a bare -lpthread: with the default
# --as-needed and -static-libstdc++, -lpthread lands before libstdc++.a (the
# actual pthread_* user) and the linker drops it.
set(THREADS_PREFER_PTHREAD_FLAG ON)
find_package(Threads REQUIRED)
target_link_libraries(tessera_bridge PRIVATE Threads::Threads)

# JNI headers only -- the library never links libjvm (the JVM that loads it
# provides the symbols). find_package(JNI) would demand AWT/JVM libraries,
# which cross toolchains don't have, so locate the headers directly.
# Override with -DJNI_INCLUDE_ROOT=<jdk>/include for cross builds.
set(JNI_INCLUDE_ROOT "$ENV{JAVA_HOME}/include" CACHE PATH "JDK include directory containing jni.h")
find_path(TESSERA_JNI_H jni.h PATHS ${JNI_INCLUDE_ROOT} NO_DEFAULT_PATH)
if(WIN32)
    set(TESSERA_JNI_MD_SUBDIR win32)
elseif(APPLE)
    set(TESSERA_JNI_MD_SUBDIR darwin)
else()
    set(TESSERA_JNI_MD_SUBDIR linux)
endif()
find_path(TESSERA_JNI_MD_H jni_md.h PATHS ${JNI_INCLUDE_ROOT}/${TESSERA_JNI_MD_SUBDIR} NO_DEFAULT_PATH)
# Explicit checks instead of find_path(... REQUIRED), which needs CMake 3.18.
if(NOT TESSERA_JNI_H OR NOT TESSERA_JNI_MD_H)
    message(FATAL_ERROR "JNI headers not found under ${JNI_INCLUDE_ROOT} (set JAVA_HOME or -DJNI_INCLUDE_ROOT)")
endif()
target_include_directories(tessera_bridge PRIVATE ${TESSERA_JNI_H} ${TESSERA_JNI_MD_H})

target_compile_definitions(tessera_bridge PRIVATE
    $<$<CONFIG:Release>:NDEBUG>
)

if(MSVC)
    target_compile_options(tessera_bridge PRIVATE
            $<$<CONFIG:Release>:/O2 /Oi /Ot>
            /GR-
            /fp:fast
    )
    # No /arch:AVX2: an AVX2-only binary loads fine on pre-AVX2 CPUs and then
    # dies with an illegal-instruction fault the JVM cannot catch. x64 MSVC
    # defaults to SSE2, which every x86_64 CPU supports.
    # No C++ exceptions cross the JNI boundary (every entry point returns a
    # plain status code), so unwinding support is disabled.
    string(REGEX REPLACE "/EH[sc]+" "" CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS}")
    target_compile_options(tessera_bridge PRIVATE /EHs-c-)
else()
    target_compile_options(tessera_bridge PRIVATE
        -O3 -ffast-math -fno-exceptions -fno-rtti -fomit-frame-pointer
    )
    if(CMAKE_SYSTEM_PROCESSOR MATCHES "x86_64|AMD64")
        # Baseline SSE4.1 only (see the MSVC note above). For AVX2, ship a second
        # binary and select it at load time instead of raising this floor.
        target_compile_options(tessera_bridge PRIVATE -msse4.1)
    elseif(CMAKE_SYSTEM_PROCESSOR MATCHES "aarch64|arm64")
        target_compile_options(tessera_bridge PRIVATE -ftree-vectorize)
    endif()
    if(CMAKE_SYSTEM_NAME STREQUAL "Linux")
        # Static C++ runtime: the .so must not depend on the player's
        # libstdc++ version (older distros ship older GLIBCXX symbols).
        target_link_options(tessera_bridge PRIVATE -static-libstdc++ -static-libgcc)
    endif()
    # Drops the parts of the basis_universal transcoder the bc7f encoder never reaches.
    if(APPLE)
        target_link_options(tessera_bridge PRIVATE -Wl,-dead_strip)
    else()
        target_link_options(tessera_bridge PRIVATE -Wl,--gc-sections)
    endif()
    include(CheckIPOSupported)
    check_ipo_supported(RESULT lto_supported)
    if(lto_supported)
        set_target_properties(tessera_bridge PROPERTIES INTERPROCEDURAL_OPTIMIZATION TRUE)
    endif()
endif()

# Output name matches what TesseraNativeBridge (java.lang.foreign side)
# looks for on the classpath: libtessera_bridge.so / .dylib / tessera_bridge.dll
set_target_properties(tessera_bridge PROPERTIES
    OUTPUT_NAME "tessera_bridge"
    PREFIX "${CMAKE_SHARED_LIBRARY_PREFIX}"
)

install(TARGETS tessera_bridge
    RUNTIME DESTINATION natives
    LIBRARY DESTINATION natives
)
