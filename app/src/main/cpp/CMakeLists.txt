cmake_minimum_required(VERSION 3.10)
project(whisper_jni)

# Include directory for whisper.h, ggml.h, ggml-cpu.h
include_directories(${CMAKE_CURRENT_SOURCE_DIR})

add_library(whisper_jni SHARED whisper_jni.c)
find_library(log-lib log)

# Link with log (Android logging) and dl (dlopen/dlsym)
target_link_libraries(whisper_jni ${log-lib} dl)
