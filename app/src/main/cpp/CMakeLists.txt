cmake_minimum_required(VERSION 3.10)
project(whisper_jni)

# Include directory for whisper.h, ggml.h, ggml-cpu.h
include_directories(${CMAKE_CURRENT_SOURCE_DIR})

add_library(whisper_jni SHARED whisper_jni.c)
find_library(log-lib log)

# Link with log (Android logging + __android_log_print) and dl (dlopen/dlsym)
target_link_libraries(whisper_jni ${log-lib} dl)

# [v2.4.27] MNN-LLM JNI bridge - uses dlopen/dlsym to load libllm.so at runtime
# No need to link against libMNN.so/libllm.so at build time
add_library(mnn_llm_jni SHARED mnn_llm_jni.cpp)
target_link_libraries(mnn_llm_jni ${log-lib} dl)
