project(pairipshield)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED True)

add_library(pairipshield SHARED
    shield.cpp
)

# dobby.h lives in the parent cpp directory, shared with the hook engine.
target_include_directories(pairipshield PRIVATE ${CMAKE_SOURCE_DIR})

# Prebuilt .so files staged into jniLibs by scripts/build_native_deps.sh.
set(LIB_FOLDER ${CMAKE_SOURCE_DIR}/../jniLibs/${CMAKE_ANDROID_ARCH_ABI})

target_link_options(pairipshield PRIVATE -Wl,-z,max-page-size=16384)

target_link_libraries(pairipshield
    ${LIB_FOLDER}/libdobby.so
    log
)
