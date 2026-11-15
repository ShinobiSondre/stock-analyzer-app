cmake_minimum_required(VERSION 3.22.1)
project(sawhisper)

# whisper.cpp (MIT) – runs Whisper speech models on the phone (copied from FaithScroll). Here with
# the English base model, to transcribe videos that have no usable YouTube captions.
include(FetchContent)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(CMAKE_BUILD_TYPE Release)
if(ANDROID_ABI STREQUAL "arm64-v8a")
    # Modern phones (2018+): faster maths.
    set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -O3 -march=armv8.2-a+fp16+dotprod")
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -O3 -march=armv8.2-a+fp16+dotprod")
endif()
FetchContent_Declare(whisper URL https://github.com/ggml-org/whisper.cpp/archive/refs/tags/v1.7.4.tar.gz)
FetchContent_MakeAvailable(whisper)

add_library(sawhisper SHARED whisper_jni.cpp)
target_link_libraries(sawhisper whisper log)
