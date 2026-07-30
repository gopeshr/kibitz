cmake_minimum_required(VERSION 3.22.1)
project(kibitz_stockfish CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

file(GLOB_RECURSE STOCKFISH_SOURCES
        "${CMAKE_CURRENT_SOURCE_DIR}/stockfish/*.cpp")

add_library(stockfish SHARED
        stockfish_jni.cpp
        ${STOCKFISH_SOURCES})

target_include_directories(stockfish PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}"
        "${CMAKE_CURRENT_SOURCE_DIR}/stockfish")

# The networks ship as Android assets and are extracted at first launch, so the embedded-net
# path is switched off. Leaving it on would compile 108 MB of weights into the .so.
target_compile_definitions(stockfish PRIVATE NNUE_EMBEDDING_OFF)

target_compile_options(stockfish PRIVATE
        -O3
        -fno-rtti
        -Wno-unused-parameter
        -Wno-deprecated-declarations)

# Stockfish selects its instruction set at compile time; there is no runtime dispatch. These
# mirror the Makefile's ARCH=armv8 profile.
#
# Deliberately *without* -march=armv8.2-a+dotprod: dot-product instructions would be faster
# but are absent on older arm64 cores (Cortex-A53/A57), where the binary would die with
# SIGILL rather than run slowly. A per-ABI variant can add it later if it proves worthwhile.
if (ANDROID_ABI STREQUAL "arm64-v8a")
    target_compile_definitions(stockfish PRIVATE
            IS_64BIT
            USE_POPCNT
            USE_PREFETCH
            USE_NEON=8)
elseif (ANDROID_ABI STREQUAL "x86_64")
    target_compile_definitions(stockfish PRIVATE
            IS_64BIT
            USE_POPCNT
            USE_PREFETCH
            USE_SSE41
            USE_SSSE3
            USE_SSE2)
    target_compile_options(stockfish PRIVATE -msse4.1 -mpopcnt)
endif ()

find_library(log-lib log)
target_link_libraries(stockfish ${log-lib})
