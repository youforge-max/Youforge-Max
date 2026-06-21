cmake_minimum_required(VERSION 3.22.1)
project(youforge_llama CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Trim the llama.cpp build to just the library — no examples/tools/tests/server.
set(LLAMA_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL           OFF CACHE BOOL "" FORCE)
# OpenMP is not reliably available under the NDK; disable to avoid link errors.
set(GGML_OPENMP          OFF CACHE BOOL "" FORCE)

# Strip the absolute build path from compiled binaries: __FILE__/assert strings in
# llama.cpp otherwise bake the builder's home dir into the shipped .so. Rewrite the
# source/build roots to neutral relative tokens (applies to the llama.cpp subdir too).
add_compile_options(
    "-ffile-prefix-map=${CMAKE_SOURCE_DIR}=youforge"
    "-ffile-prefix-map=${CMAKE_BINARY_DIR}=build")

add_subdirectory(llama.cpp)

add_library(llama-android SHARED llama-android.cpp)

target_link_libraries(llama-android
    llama
    ggml
    android
    log)
