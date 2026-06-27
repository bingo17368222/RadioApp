cmake_minimum_required(VERSION 3.10)
project(whisper_jni)

add_library(whisper_jni SHARED whisper_jni.c)
find_library(log-lib log)
target_link_libraries(whisper_jni ${log-lib} dl)
