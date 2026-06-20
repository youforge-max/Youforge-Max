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

add_subdirectory(llama.cpp)

add_library(llama-android SHARED llama-android.cpp)

target_link_libraries(llama-android
    llama
    ggml
    android
    log)
