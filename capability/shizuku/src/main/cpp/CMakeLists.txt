cmake_minimum_required(VERSION 3.22.1)

project("pocketpilot_adb")

set(CMAKE_CXX_STANDARD 17)

add_compile_options(-Werror=format -fdata-sections -ffunction-sections -fno-exceptions -fno-rtti -fno-threadsafe-statics)

if (NOT CMAKE_BUILD_TYPE STREQUAL "Debug")
    add_compile_options(-Os -flto -fvisibility=hidden -fvisibility-inlines-hidden)
    add_link_options(-flto -Wl,--exclude-libs,ALL -Wl,--gc-sections -Wl,--strip-all)
endif ()

# BoringSSL and libc++ come as prefab packages (see capability/shizuku/build.gradle.kts).
find_package(boringssl REQUIRED CONFIG)
find_package(cxx REQUIRED CONFIG)

add_library(pocketpilot_adb SHARED adb_pairing.cpp)

target_link_libraries(pocketpilot_adb log boringssl::crypto_static cxx::cxx)
