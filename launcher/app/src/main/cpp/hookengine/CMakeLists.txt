project(hookengine)

# lsplant.hpp needs C++20 (std::span, std::string_view in InitInfo). Scope the
# standard bump to this target; the rest of the tree stays on C++17.
set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED True)

add_library(hookengine SHARED
    hookengine.cpp
    # XZ Embedded (vendored, public domain — see xz/XZ_README.md): decodes
    # the .gnu_debugdata section of the stripped on-device libart.so, which
    # is where its full .symtab lives.
    xz/xz_crc32.c
    xz/xz_dec_bcj.c
    xz/xz_dec_lzma2.c
    xz/xz_dec_stream.c
)

# Vendored LSPlant public header (include/lsplant.hpp, from the pinned
# LSPosed/LSPlant tree in provenance/lsplant.env) plus the parent cpp dir for
# dobby.h, the same include layout fusion uses.
target_include_directories(hookengine PRIVATE include xz ${CMAKE_SOURCE_DIR})

# Prebuilt .so files staged into jniLibs by scripts/build_native_deps.sh; the
# same by-path linking pattern fusion uses for libdobby.so.
set(LIB_FOLDER ${CMAKE_SOURCE_DIR}/../jniLibs/${CMAKE_ANDROID_ARCH_ABI})

# The STL comes from the module-wide -DANDROID_STL=c++_static, matching how
# liblsplant.so is built by scripts/build_native_deps.sh, so the std::function
# based lsplant::InitInfo crosses the boundary between two NDK r30 libc++
# builds of the same ABI.
target_link_options(hookengine PRIVATE -Wl,-z,max-page-size=16384)

target_link_libraries(hookengine
    ${LIB_FOLDER}/liblsplant.so
    ${LIB_FOLDER}/libdobby.so
    log
)
